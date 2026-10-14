package io.github.nexalloy.revanced.instagram.dm

import app.morphe.extension.shared.Logger
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData

private const val DIRECT_THREAD_KEY = "com.instagram.model.direct.DirectThreadKey"
private const val STRING = "java.lang.String"
private const val LIST = "java.util.List"

private val INSTANCE_FIELD_WRITES: Set<Int> = setOf(
    Opcode.IPUT,
    Opcode.IPUT_WIDE,
    Opcode.IPUT_OBJECT,
    Opcode.IPUT_BOOLEAN,
    Opcode.IPUT_BYTE,
    Opcode.IPUT_CHAR,
    Opcode.IPUT_SHORT,
).mapTo(mutableSetOf()) { it.opCode }

private object ResolveCache {
    private var owner: DexKitBridge? = null
    private val entries = HashMap<String, Any>()

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(bridge: DexKitBridge, key: String, compute: DexKitBridge.() -> T): T {
        if (owner !== bridge) {
            owner = bridge
            entries.clear()
        }
        entries[key]?.let { return it as T }
        return bridge.compute().also { entries[key] = it }
    }
}

private fun <T : Any> DexKitBridge.once(key: String, compute: DexKitBridge.() -> T): T =
    ResolveCache.resolve(this, key, compute)

private fun DexKitBridge.methodsUsingStrings(
    strings: List<String>,
    extra: MethodMatcher.() -> Unit = {},
): List<MethodData> {
    val exact = findMethod {
        matcher {
            usingStrings(strings, StringMatchType.Equals)
            extra()
        }
    }
    if (exact.isNotEmpty()) return exact

    return findMethod {
        matcher {
            usingStrings(strings, StringMatchType.Contains)
            extra()
        }
    }
}

private val MESSAGE_MARKER_STRINGS = listOf(
    "item_id", "user_id", "text", "timestamp", "hide_in_thread", "thread_key",
)

private fun DexKitBridge.messageParserClass(): ClassData = once("messageParserClass") {
    val candidates = methodsUsingStrings(MESSAGE_MARKER_STRINGS)
    if (candidates.isEmpty()) throw Exception("Direct message deserializer not found")

    val preferred = candidates.firstOrNull { it.declaredClass?.methods?.any(::isJsonEntryPoint) == true }
    (preferred ?: candidates.first()).declaredClass
        ?: throw Exception("Direct message deserializer has no declaring class")
}

private fun isJsonEntryPoint(method: MethodData) =
    method.name == "parseFromJson" || method.name == "unsafeParseFromJson"

private val MESSAGE_FIELD_KEYS = listOf(
    "item_id",
    "client_context",
    "user_id",
    "is_sent_by_viewer",
    "hide_in_thread",
)

private fun DexKitBridge.messageFields(): Map<String, FieldData> = once("messageFields") {
    val parser = messageParserClass()
    val found = HashMap<String, FieldData>(MESSAGE_FIELD_KEYS.size)

    for (method in parser.methods) {
        if (found.size == MESSAGE_FIELD_KEYS.size) break

        val used = runCatching { method.usingStrings.toHashSet() }.getOrNull()
        if (used != null && MESSAGE_FIELD_KEYS.none { !found.containsKey(it) && it in used }) continue

        val instructions = runCatching { method.instructions }.getOrNull() ?: continue

        for (key in MESSAGE_FIELD_KEYS) {
            if (found.containsKey(key)) continue
            val keyIndex = instructions.indexOfFirst { it.string == key }
            if (keyIndex < 0) continue

            instructions.asSequence()
                .drop(keyIndex + 1)
                .firstOrNull { it.opcode in INSTANCE_FIELD_WRITES }
                ?.fieldRef
                ?.let { found[key] = it }
        }
    }
    found
}

private fun DexKitBridge.messageField(key: String): FieldData =
    messageFields()[key] ?: throw Exception("No field stored for direct message JSON key '$key'")

internal val messageItemIdField = findFieldDirect { messageField("item_id") }

internal val messageClientContextField = findFieldDirect { messageField("client_context") }

internal val messageUserIdField = findFieldDirect { messageField("user_id") }

internal val messageSentByViewerField = findFieldDirect { messageField("is_sent_by_viewer") }

internal val messageHideInThreadField = findFieldDirect { messageField("hide_in_thread") }

internal val messageParseMethod = findMethodDirect {
    messageParserClass().methods.first(::isJsonEntryPoint)
}

private fun DexKitBridge.messageRowDelete(): MethodData = once("messageRowDelete") {
    val rowSignature: MethodMatcher.() -> Unit = {
        paramTypes(DIRECT_THREAD_KEY, STRING, STRING)
        returnType = "void"
    }

    methodsUsingStrings(listOf("server_item_id=='", "client_item_id=='"), rowSignature)
        .firstOrNull()
        ?: methodsUsingStrings(listOf("Both message ID and client context is null."), rowSignature)
            .first()
}

internal val messageRowDeleteMethod = findMethodDirect { messageRowDelete() }

private fun DexKitBridge.threadRemoveMessage(): MethodData = once<MethodData>("threadRemoveMessage") {
    val rowDelete = messageRowDelete()
    val diskJobs = rowDelete.callers
    Logger.printInfo { "SaveDeletedMessages: disk delete jobs -> ${diskJobs.size}" }

    val visitedJobClasses = HashSet<String>()

    for (job in diskJobs) {
        val jobClass = job.declaredClass ?: continue
        if (!visitedJobClasses.add(jobClass.name)) continue

        for (constructor in jobClass.methods) {
            if (!constructor.isConstructor) continue
            val creators = runCatching { constructor.callers }.getOrNull() ?: continue

            for (creator in creators) {
                val params = creator.paramTypeNames
                val looksLikeRemoval = creator.returnTypeName == "void" &&
                    params.any { it == DIRECT_THREAD_KEY } &&
                    params.count { it == STRING } >= 2
                if (!looksLikeRemoval) continue

                Logger.printInfo {
                    "SaveDeletedMessages: removal method -> ${creator.className}.${creator.name}"
                }
                return@once creator
            }
        }
    }
    throw Exception("Thread removal method not found")
}

internal val threadRemoveMessageMethod = findMethodDirect { threadRemoveMessage() }

internal val listRemoveMessageMethod = findMethodDirect {
    val removal = threadRemoveMessage()
    removal.invokes.firstOrNull {
        it.returnTypeName == "boolean" && it.paramTypeNames == listOf(STRING, LIST)
    } ?: findMethod {
        matcher {
            addCaller(removal.descriptor)
            paramTypes(STRING, LIST)
            returnType = "boolean"
        }
    }.first()
}

private fun DexKitBridge.messageClassName(): String = once("messageClassName") {
    val base = messageField("item_id").declaredClassName
    findClass { matcher { superClass(base) } }.firstOrNull()?.name ?: base
}

internal val threadStateLookupMethod = findMethodDirect {
    val removal = threadRemoveMessage()
    removal.invokes.firstOrNull {
        it.className == removal.className && it.paramTypeNames == listOf(DIRECT_THREAD_KEY)
    } ?: findMethod {
        matcher {
            addCaller(removal.descriptor)
            declaredClass(removal.className)
            paramTypes(DIRECT_THREAD_KEY)
        }
    }.first()
}

internal val threadFindMessageMethod = findMethodDirect {
    val removal = threadRemoveMessage()
    val messageClass = messageClassName()
    removal.invokes.firstOrNull {
        it.paramTypeNames == listOf(STRING) && it.returnTypeName == messageClass
    } ?: findMethod {
        matcher {
            addCaller(removal.descriptor)
            paramTypes(STRING)
            returnType = messageClass
        }
    }.first()
}
