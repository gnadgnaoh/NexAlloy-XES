package io.github.nexalloy.revanced.instagram.dm

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.lang.reflect.Modifier

private const val SAVE_ACTION = "SaveMedia"

private fun valueOfType(host: Any?, type: Class<*>): Any? {
    var c: Class<*>? = host?.javaClass ?: return null
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
            if (!f.type.isAssignableFrom(type) && !type.isAssignableFrom(f.type)) continue
            val v = runCatching { f.isAccessible = true; f.get(host) }.getOrNull()
            if (type.isInstance(v)) return v
        }
        c = c.superclass
    }
    return null
}

private fun firstHttpString(obj: Any): String? {
    var c: Class<*>? = obj.javaClass
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type != String::class.java) continue
            val v = runCatching { f.isAccessible = true; f.get(obj) as? String }.getOrNull()
            if (v != null && v.startsWith("http")) return v
        }
        c = c.superclass
    }
    return null
}

val DownloadVoiceMessages = patch(
    name = "Download voice messages",
    description = "Adds Save to the menu you get when you hold a voice message in a chat. It saves the recording as an audio file.",
) {
    val voiceField = ::messageVoiceMediaField.field.apply { isAccessible = true }
    val playable = ::voicePlayableMethod.method.apply { isAccessible = true }
    val messageClass = voiceField.declaringClass
    val saveFlag = ::messageMenuActionGuardsFingerprint.fieldList.chunked(2).firstOrNull { pair ->
        val action = pair.getOrNull(1) ?: return@firstOrNull false
        runCatching { action.isAccessible = true; action.get(null)?.toString() == SAVE_ACTION }.getOrDefault(false)
    }?.first()?.apply { isAccessible = true } ?: error("no Save action guard in the message menu")
    val entry = ::directMediaSaverEntryFingerprint.method
    val menu = ::messageActionMenuFingerprint.method
    val lookup = ::messageMenuLookupFingerprint.method

    val model = ThreadLocal<Any?>()
    menu.hookMethod {
        before { param -> model.set(param.args.firstOrNull { saveFlag.declaringClass.isInstance(it) }) }
        after { model.remove() }
    }
    ScopedFrameworkHook(lookup, after = { param ->
        val target = model.get() ?: return@ScopedFrameworkHook
        val message = param.result?.takeIf { messageClass.isInstance(it) } ?: return@ScopedFrameworkHook
        if (runCatching { voiceField.get(message) }.getOrNull() != null) saveFlag.setBoolean(target, true)
    }).installOn(menu)

    entry.hookMethod {
        before { param ->
            val message = param.args.firstNotNullOfOrNull { valueOfType(it, messageClass) } ?: return@before
            val voice = runCatching { voiceField.get(message) }.getOrNull() ?: return@before
            val ctx: Context = param.args.firstNotNullOfOrNull { valueOfType(it, Activity::class.java) as? Context }
                ?: AndroidAppHelper.currentApplication() ?: return@before
            param.result = null
            MediaSaver.executor.submit {
                val url = runCatching { playable.invoke(null, voice)?.let(::firstHttpString) }.getOrNull()
                if (url == null) return@submit MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_no_audio))
                MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_audio))
                runCatching { MediaSaver.saveAudio(ctx, url, "instagram_voice_${System.currentTimeMillis()}.m4a", null) }
                    .onSuccess { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_audio_saved)) }
                    .onFailure { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, it.message ?: it.javaClass.simpleName)) }
            }
        }
    }
}
