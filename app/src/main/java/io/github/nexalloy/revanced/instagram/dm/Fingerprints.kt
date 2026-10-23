package io.github.nexalloy.revanced.instagram.dm

import app.morphe.extension.shared.Logger
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
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
    "timestamp",
    "media",
    "voice_media",
    "raven_media",
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
    for (key in MESSAGE_FIELD_KEYS) {
        if (found.containsKey(key)) continue
        val pools = findMethod {
            matcher { usingEqStrings(key); paramTypes("int"); returnType = STRING }
        }.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
        if (pools.isEmpty()) continue
        val sites = parser.methods.flatMap { method ->
            val code = runCatching { method.instructions }.getOrNull() ?: return@flatMap emptyList()
            code.indices.filter { at ->
                code[at].methodRef?.descriptor in pools && code.drop(at + 1).take(4)
                    .any { it.methodRef?.let { m -> m.name == "equals" && m.declaredClassName == STRING } == true }
            }.map { at -> code.drop(at + 1).firstOrNull { it.opcode in INSTANCE_FIELD_WRITES }?.fieldRef }
        }
        sites.singleOrNull()?.let { found[key] = it }
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

internal val messageTimestampField = findFieldDirect { messageField("timestamp") }

internal val messageMediaField = findFieldDirect { messageField("media") }

internal val messageVoiceMediaField = findFieldDirect { messageField("voice_media") }

internal val messageRavenMediaField = findFieldDirect { messageField("raven_media") }

private fun DexKitBridge.voiceClassName(): String = once("voiceClassName") {
    messageField("voice_media").typeName
}

internal val voiceLocalPathMethod = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(voiceClassName())
            paramCount = 0
            returnType = STRING
        }
    }.single()
}

internal val voicePlayableMethod = findMethodDirect {
    val voice = voiceClassName()
    val localPath = voiceLocalPathMethod(this)
    findMethod {
        matcher {
            paramTypes(voice)
            addInvoke(localPath.descriptor)
        }
    }.single { it.className != voice && it.returnTypeName != "void" && it.isStaticMethod() }
}

private fun MethodData.isStaticMethod() = java.lang.reflect.Modifier.isStatic(modifiers)

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

internal val threadMergeMethod = findMethodDirect {
    methodsUsingStrings(listOf("messageIsDirectlyCreatedFromEBResponse", " will replace cached message")) {
        returnType = "void"
        paramCount = 6
    }.single()
}

internal val messageToRowMethod = findMethodDirect {
    val table = messageRowDelete().className
    findMethod {
        matcher {
            declaredClass(table)
            returnType = "android.content.ContentValues"
            paramCount = 2
        }
    }.single {
        it.paramTypeNames[1] == "java.io.ByteArrayOutputStream" &&
            it.paramTypeNames[0] != "java.lang.Object"
    }
}

internal val messageFromBlobMethod = findMethodDirect {
    val table = messageRowDelete().className
    methodsUsingStrings(listOf("DirectMessageSQLiteTable.parseMessage")) {
        declaredClass(table)
    }.single { it.paramTypeNames == listOf("byte[]") }
}

internal val messageTableGetterMethod = findMethodDirect {
    val table = messageRowDelete().className
    findMethod {
        matcher {
            declaredClass(table)
            paramTypes("com.instagram.common.session.UserSession")
            returnType = table
        }
    }.single { java.lang.reflect.Modifier.isStatic(it.modifiers) }
}

private const val OP_IGET_WIDE = 0x53
private const val OP_SGET_WIDE = 0x61

internal val presenceTextMethod = findMethodDirect {
    findMethod {
        matcher {
            paramTypes("android.content.res.Resources", "java.util.List")
            returnType = STRING
            usingNumbers(480, 10080)
        }
    }.single { !it.isStaticMethod() }
}

internal val presenceLastActiveField = findFieldDirect {
    presenceTextMethod().instructions.first { it.opcode == OP_IGET_WIDE }.fieldRef!!
}

internal val presenceActiveCheckMethod = findMethodDirect {
    val item = presenceLastActiveField().declaredClassName
    presenceTextMethod().instructions.mapNotNull { it.methodRef }.first {
        it.isStaticMethod() && it.returnTypeName == "boolean" && it.paramTypeNames == listOf(item, "long")
    }
}

internal val presenceActiveWindowField = findFieldDirect {
    presenceTextMethod().instructions.first { it.opcode == OP_SGET_WIDE }.fieldRef!!
}

private const val OP_IGET_BOOLEAN = 0x55
private const val OP_SGET_OBJECT = 0x62
private val OP_IF_ZERO = setOf(0x38, 0x39)

private fun DexKitBridge.messageActionMenu(): MethodData =
    findMethod { matcher { usingEqStrings("DirectThreadFragment.showMessageActionDialog") } }.single()

internal val messageMenuActionGuardsFingerprint = findFieldListDirect {
    val menu = messageActionMenu()
    val models = menu.paramTypeNames.toSet()
    val code = menu.instructions
    code.indices.filter { at ->
        val f = code[at].fieldRef
        code[at].opcode == OP_SGET_OBJECT && f != null && f.typeName == f.declaredClassName
    }.mapNotNull { at ->
        var back = at - 1
        while (back >= 0 && code[back].opcode in OP_IF_ZERO) back--
        val guard = code.getOrNull(back)?.takeIf { it.opcode == OP_IGET_BOOLEAN }?.fieldRef
            ?.takeIf { it.declaredClassName in models } ?: return@mapNotNull null
        guard to code[at].fieldRef!!
    }.distinctBy { (g, a) -> g.descriptor + a.descriptor }.flatMap { (g, a) -> listOf(g, a) }
}

internal val messageMenuModelBuildersFingerprint = findMethodListDirect {
    val model = messageMenuActionGuardsFingerprint().first().declaredClassName
    findMethod {
        matcher {
            name = "invoke"
            paramCount = 0
            addInvoke { declaredClass(model); name = "<init>" }
        }
    }
}

internal val directMediaSaverEntryFingerprint = findMethodDirect {
    val check = findMethod { matcher { usingEqStrings("Invalid message contentType: ") } }.single()
    findMethod {
        matcher { declaredClass(check.declaredClassName); paramTypes(check.paramTypeNames); addInvoke(check.descriptor) }
    }.single { it.isStaticMethod() }
}

internal val messageActionMenuFingerprint = findMethodDirect { messageActionMenu() }

internal val messageMenuLookupFingerprint = findMethodDirect {
    val code = messageActionMenu().instructions
    val at = code.indexOfFirst { it.string == "DirectThreadFragment.showMessageActionDialog" }
    code.drop(at + 1).firstNotNullOf { i -> i.methodRef?.takeIf { '.' in it.returnTypeName && it.returnTypeName != "java.lang.String" } }
}
