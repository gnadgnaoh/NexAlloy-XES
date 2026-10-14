package io.github.nexalloy.revanced.instagram.dm

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexField
import java.lang.reflect.Field
import java.util.Collections

val SaveDeletedMessages = patch(
    name = "Save deleted messages",
    description = "Keeps direct messages visible after the person who sent them unsends. " +
        "Blocks Instagram from dropping the message from the conversation and from deleting it " +
        "locally. Unsending your own messages still works.",
) {
    val message = MessageAccess(
        classLoader = classLoader,
        itemId = ::messageItemIdField.dexField,
        clientContext = ::messageClientContextField.dexField,
        userId = ::messageUserIdField.dexField,
        sentByViewer = ::messageSentByViewerField.dexField,
        hideInThread = ::messageHideInThreadField.dexField,
    )
    val kept = KeptMessages()

    val findThread = runCatching { ::threadStateLookupMethod.method.apply { isAccessible = true } }
        .onFailure { Logger.printException({ "SaveDeletedMessages: thread lookup unresolved" }, it) }
        .getOrNull()
    val findMessage = runCatching { ::threadFindMessageMethod.method.apply { isAccessible = true } }
        .onFailure { Logger.printException({ "SaveDeletedMessages: message lookup unresolved" }, it) }
        .getOrNull()

    ::threadRemoveMessageMethod.hookMethod {
        before { param ->
            if (kept.mentionedIn(param.args)) {
                param.result = null
                return@before
            }
            findThread ?: return@before
            findMessage ?: return@before

            val store = param.thisObject ?: return@before
            val threadKey = param.args?.getOrNull(0) ?: return@before
            val thread = runCatching { findThread.invoke(store, threadKey) }.getOrNull()
                ?: return@before

            for (arg in param.args.orEmpty()) {
                val id = (arg as? String)?.takeIf { it.isNotEmpty() } ?: continue
                val target = runCatching { findMessage.invoke(thread, id) }.getOrNull() ?: continue
                if (!message.isFromSomeoneElse(target)) continue

                message.unhide(target)
                kept.protect(message.itemId(target), message.clientContext(target), id)
                Logger.printDebug { "SaveDeletedMessages: kept unsent message $id" }
                param.result = null
                return@before
            }
        }
    }

    ::listRemoveMessageMethod.hookMethod {
        before { param ->
            val id = param.args?.getOrNull(0) as? String ?: return@before
            val messages = param.args?.getOrNull(1) as? Collection<*> ?: return@before

            val target = messages.firstOrNull { message.hasId(it, id) } ?: return@before
            if (!message.isFromSomeoneElse(target)) return@before

            message.unhide(target)
            kept.protect(message.itemId(target), message.clientContext(target), id)
            Logger.printDebug { "SaveDeletedMessages: kept unsent message $id" }
            param.result = false
        }
    }

    ::messageRowDeleteMethod.hookMethod {
        before { param -> if (kept.mentionedIn(param.args)) param.result = null }
    }

    ::messageParseMethod.hookMethod {
        after { param ->
            val parsed = param.result ?: return@after
            if (message.isFromSomeoneElse(parsed)) message.unhide(parsed)
        }
    }
}

private class MessageAccess(
    private val classLoader: ClassLoader,
    itemId: DexField,
    clientContext: DexField,
    userId: DexField,
    sentByViewer: DexField,
    hideInThread: DexField,
) {
    private fun resolve(ref: DexField): Field? = runCatching {
        classLoader.loadClass(ref.className)
            .getDeclaredField(ref.name)
            .apply { isAccessible = true }
    }.getOrNull()

    private val messageClass: Class<*> = classLoader.loadClass(itemId.className)

    private val itemIdField = resolve(itemId)
    private val clientContextField = resolve(clientContext)
    private val userIdField = resolve(userId)
    private val sentByViewerField = resolve(sentByViewer)
    private val hideInThreadField = resolve(hideInThread)

    fun itemId(candidate: Any?): String? = readString(itemIdField, candidate)

    fun clientContext(candidate: Any?): String? = readString(clientContextField, candidate)

    fun hasId(candidate: Any?, id: String): Boolean {
        if (!messageClass.isInstance(candidate)) return false
        return itemId(candidate) == id || clientContext(candidate) == id
    }

    fun isFromSomeoneElse(candidate: Any?): Boolean {
        if (!messageClass.isInstance(candidate)) return false
        if (itemId(candidate) == null) return false
        if (runCatching { sentByViewerField?.get(candidate) as? Boolean }.getOrNull() == true) {
            rememberViewer(readString(userIdField, candidate))
            return false
        }
        val sender = readString(userIdField, candidate)
        return sender == null || sender != viewerUserId
    }

    fun unhide(candidate: Any?) {
        if (!messageClass.isInstance(candidate)) return
        runCatching { hideInThreadField?.set(candidate, false) }
    }

    private fun readString(field: Field?, target: Any?): String? =
        runCatching { (field?.get(target) as? CharSequence)?.toString()?.takeIf { it.isNotEmpty() } }
            .getOrNull()

    @Volatile
    private var viewerUserId: String? = null

    private fun rememberViewer(userId: String?) {
        if (!userId.isNullOrEmpty()) viewerUserId = userId
    }
}

private class KeptMessages {
    private val ids: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > 2000
        }
    )

    fun protect(vararg candidates: String?) {
        candidates.forEach { id -> if (!id.isNullOrEmpty()) ids.put(id, true) }
    }

    fun mentionedIn(args: Array<Any?>?): Boolean {
        args ?: return false
        return args.any { arg -> (arg as? String)?.let { ids.containsKey(it) } == true }
    }
}
