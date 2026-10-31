package io.github.nexalloy.morphe.tiktok.unsent

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.patch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "[TikTok unsent]"
private const val MIN_TEXT = 2
private const val OWN_ID_MS = 30_000L
private fun hasPayload(payload: Any?): Boolean =
    payload != null && payload.toString().let { it.isNotEmpty() && it != "[size=0]" }

val SaveUnsentMessages = patch(
    name = "Keep unsent messages",
    description = "Keeps chat messages the other person unsent, including photos, videos and stickers, " +
        "if this phone already received them. Your own unsent messages disappear as usual.",
) {
    TikTokServices.init(classLoader)
    val message = ::imMessageClassFingerprint.clazz
    val recallers = ::recallHandlerMethodsFingerprint.dexMethodList.map { it.className }.toSet()
    val getContent = message.getMethod("getContent")
    val getAttachments = message.getMethod("getAttachments")
    val getContentPB = message.getMethod("getContentPB")
    val getSender = message.getMethod("getSender")
    val getUuid = message.getMethod("getUuid")
    val status = message.getMethod("getMessageStatus")
    val available = status.returnType.enumConstants?.firstOrNull { (it as Enum<*>).name == "AVAILABLE" }
        ?: error("message status has no AVAILABLE")
    val kept = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    var ownId: String? = null
    var ownIdAt = 0L
    fun ownId(): String? {
        val now = android.os.SystemClock.uptimeMillis()
        if (ownId == null || now - ownIdAt > OWN_ID_MS) { ownId = TikTokServices.currentUserId(); ownIdAt = now }
        return ownId
    }

    fun mine(msg: Any): Boolean {
        val own = ownId() ?: return false
        return own == getSender.invoke(msg)?.toString()
    }

    fun keepable(msg: Any): Boolean {
        val text = (getContent.invoke(msg) as? String)?.trim().orEmpty()
        val files = getAttachments.invoke(msg) as? Collection<*>
        if (text.length <= MIN_TEXT && files.isNullOrEmpty() && !hasPayload(getContentPB.invoke(msg))) return false
        return !mine(msg)
    }

    fun fromRecall(): Boolean = Throwable().stackTrace.any { it.className in recallers }

    message.getMethod("isRecalled").hookMethod {
        after { param ->
            if (param.result == true && runCatching { keepable(param.thisObject) }.getOrDefault(false)) param.result = false
        }
    }
    status.hookMethod {
        after { param ->
            val now = param.result as? Enum<*> ?: return@after
            if (now.name == "RECALLED" && runCatching { keepable(param.thisObject) }.getOrDefault(false)) param.result = available
        }
    }

    listOf("setContent", "setContentPB", "setAttachments").forEach { name ->
        message.declaredMethods.filter { it.name == name && it.parameterCount == 1 }.forEach { setter ->
            setter.hookMethod {
                before { param ->
                    runCatching {
                        if (!keepable(param.thisObject) || !fromRecall()) return@runCatching
                        param.result = null
                        (getUuid.invoke(param.thisObject) as? String)?.let { kept.add(it) }
                    }.onFailure { Logger.printException({ "$TAG guard failed" }, it) }
                }
            }
        }
    }

    recallers.mapNotNull { runCatching { classLoader.loadClass(it) }.getOrNull() }.forEach { handler ->
        handler.declaredMethods.filter { m -> m.parameterTypes.any { message.isAssignableFrom(it) } }.forEach { entry ->
            entry.hookMethod {
                before { param ->
                    param.args.forEach { arg ->
                        if (arg == null || !message.isInstance(arg)) return@forEach
                        runCatching { if (keepable(arg)) (getUuid.invoke(arg) as? String)?.let { kept.add(it) } }
                    }
                }
            }
        }
    }

    ::attachmentTableDeleteMethodsFingerprint.dexMethodList.map { it.toMethod() }.forEach { delete ->
        delete.hookMethod {
            before { param ->
                val uuid = param.args[0] as? String ?: return@before
                if (uuid in kept && fromRecall()) param.result = null
            }
        }
    }
    Logger.printDebug { "$TAG model=${message.name} recallers=${recallers.size}" }
}
