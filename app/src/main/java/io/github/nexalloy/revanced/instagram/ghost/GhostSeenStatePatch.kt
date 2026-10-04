package io.github.nexalloy.revanced.instagram.ghost

import android.os.Handler
import android.os.Looper
import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

val GhostSeenState = patch(
    name = "Ghost seen state",
    description = "Blocks DM read receipts from being sent. Chats you open still show as read on your phone.",
) {
    ::seenMutationSenderFingerprint.hookMethod {
        before { param ->
            val callback = param.args.getOrNull(1) ?: return@before
            val mutation = param.args.getOrNull(2)

            if (GhostSeenBypass.consume(mutation)) {
                Logger.printDebug { "Ghost: DM seen state sent on request" }
                return@before
            }

            param.result = null
            Logger.printDebug { "Ghost: DM seen state kept on this phone, not sent" }
            reportUploaded(callback)
        }
    }
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

private fun reportUploaded(callback: Any) {
    val result = resultMethodOf(callback.javaClass)
    if (result == null) {
        Logger.printInfo { "Ghost: no result method on ${callback.javaClass.name}; the chat may stay unread" }
        return
    }
    mainHandler.postDelayed({
        runCatching { result.invoke(callback, null, null) }
            .onFailure { Logger.printException({ "Ghost: could not report the seen mutation as sent" }, it) }
    }, 150)
}

private val resultMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
private val NONE = Any()

private fun resultMethodOf(type: Class<*>): Method? =
    resultMethods.getOrPut(type) {
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { allInterfaces(it) }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                Modifier.isAbstract(m.modifiers) && m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[1] == String::class.java &&
                    !m.parameterTypes[0].isPrimitive && m.parameterTypes[0] != String::class.java
            }
            ?.apply { isAccessible = true }
            ?: NONE
    } as? Method

private fun allInterfaces(type: Class<*>): Sequence<Class<*>> =
    type.interfaces.asSequence().flatMap { sequenceOf(it) + allInterfaces(it) }

internal object GhostSeenBypass {
    private const val WINDOW_MS = 30_000L

    @Volatile private var threadId: String? = null
    @Volatile private var armedAt = 0L

    fun arm(threadId: String?) {
        this.threadId = threadId
        armedAt = System.currentTimeMillis()
    }

    fun consume(mutation: Any?): Boolean {
        if (armedAt == 0L) return false
        if (System.currentTimeMillis() - armedAt > WINDOW_MS) {
            armedAt = 0L
            return false
        }
        val wanted = threadId
        if (wanted != null && mutation != null) {
            val ids = threadIdsOf(mutation)
            if (ids != null && wanted !in ids) return false
        }
        armedAt = 0L
        return true
    }

    private fun threadIdsOf(mutation: Any): Set<String>? = runCatching {
        val getter = generateSequence<Class<*>>(mutation.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.parameterTypes.isEmpty() && it.returnType.name == DIRECT_THREAD_KEY }
            ?: return null
        getter.isAccessible = true
        val key = getter.invoke(mutation) ?: return null
        key.javaClass.declaredFields
            .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
            .mapNotNullTo(HashSet()) { f -> f.isAccessible = true; f.get(key) as? String }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private const val DIRECT_THREAD_KEY = "com.instagram.model.direct.DirectThreadKey"
}
