package io.github.nexalloy.revanced.messenger.privacy

import android.os.SystemClock
import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Opening a chat doesn't send Seen and leaves it unread. Mark as read still sends Seen, " +
        "and replying in a chat lets that chat show Seen. Works in end-to-end encrypted chats.",
) {
    val threadKey = classLoader.loadClass(THREAD_KEY)

    ::chatSeenBroadcastFingerprint.hookMethod {
        before { param ->
            val thread = ReadReceipts.field(param.args[1], threadKey)
            if (ReadReceipts.repliedRecently(param.args[0], thread)) return@before
            Logger.printDebug { "Messenger: chat Seen held" }
            param.result = null
        }
    }

    ::replyDispatchFingerprint.hookMethod {
        after { param ->
            if (param.throwable != null) return@after
            ReadReceipts.noteSent(param.args[0], ReadReceipts.getter(param.args[7], threadKey))
        }
    }
}

internal object ReadReceipts {
    private const val REPLY_READ_MS = 5_000L

    private class Reply(session: Any, thread: Any, val at: Long) {
        val session = WeakReference(session)
        val thread = WeakReference(thread)
    }

    @Volatile
    private var last: Reply? = null

    fun noteSent(session: Any?, thread: Any?) {
        if (session != null && thread != null) last = Reply(session, thread, SystemClock.elapsedRealtime())
    }

    fun repliedRecently(session: Any?, thread: Any?): Boolean {
        val r = last ?: return false
        if (session == null || thread == null || r.session.get() !== session) return false
        val since = SystemClock.elapsedRealtime() - r.at
        return since in 0 until REPLY_READ_MS && r.thread.get() == thread
    }

    private val fields = ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Field>>()
    private val getters = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()

    fun field(owner: Any?, type: Class<*>): Any? = runCatching {
        owner ?: return null
        fields.getOrPut(owner.javaClass) {
            java.util.Optional.ofNullable(owner.javaClass.declaredFields.firstOrNull { it.type == type }?.apply { isAccessible = true })
        }.orElse(null)?.get(owner)
    }.getOrNull()

    fun getter(owner: Any?, type: Class<*>): Any? = runCatching {
        owner ?: return null
        getters.getOrPut(owner.javaClass) {
            java.util.Optional.ofNullable(owner.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType == type })
        }.orElse(null)?.invoke(owner)
    }.getOrNull()
}
