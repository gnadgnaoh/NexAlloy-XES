package io.github.nexalloy.revanced.instagram.dm

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.lang.reflect.Field

private const val SAVE_ACTION = "SaveMedia"

val DownloadDisappearingMedia = patch(
    name = "Download disappearing media",
    description = "Shows Instagram's Save option when you hold a photo or video (including " +
        "view-once) sent in vanish mode / disappearing messages.",
) {
    val mediaField = ::messageMediaField.field.apply { isAccessible = true }
    val messageClass = mediaField.declaringClass
    val mediaFields = listOf(mediaField) + listOf(
        ::messageVisualMediaField,
        ::messageRavenMediaField,
    ).mapNotNull { fp ->
        runCatching { fp.field.apply { isAccessible = true } }
            .onFailure { Logger.printInfo { "DownloadDisappearingMedia: ${fp.name} not found: ${it.message}" } }
            .getOrNull()
    }

    val vanishFields = listOf(
        ::messageIsDisappearingField,
        ::messageEphemeralLifetimeField,
        ::messageExpirationTimestampField,
    ).mapNotNull { fp ->
        runCatching { fp.field.apply { isAccessible = true } }
            .onFailure { Logger.printInfo { "DownloadDisappearingMedia: ${fp.name} not found: ${it.message}" } }
            .getOrNull()
    }
    check(vanishFields.isNotEmpty()) { "no vanish-mode marker field on the direct message model" }

    val saveFlag = ::messageMenuActionGuardsFingerprint.fieldList.chunked(2).firstOrNull { pair ->
        val action = pair.getOrNull(1) ?: return@firstOrNull false
        runCatching { action.isAccessible = true; action.get(null)?.toString() == SAVE_ACTION }.getOrDefault(false)
    }?.first()?.apply { isAccessible = true } ?: error("no Save action guard in the message menu")

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
        if (saveFlag.getBoolean(target)) return@ScopedFrameworkHook
        if (!isDisappearing(message, vanishFields)) return@ScopedFrameworkHook
        if (!hasMedia(message, mediaFields)) return@ScopedFrameworkHook
        saveFlag.setBoolean(target, true)
        Logger.printDebug { "DownloadDisappearingMedia: Save enabled for a disappearing message" }
    }).installOn(menu)
}

private fun isDisappearing(message: Any, fields: List<Field>): Boolean = fields.any { f ->
    when (val v = runCatching { f.get(message) }.getOrNull()) {
        is Boolean -> v
        is Long -> v > 0L
        else -> false
    }
}

private fun hasMedia(message: Any, fields: List<Field>): Boolean =
    fields.any { f -> runCatching { f.get(message) }.getOrNull() != null }
