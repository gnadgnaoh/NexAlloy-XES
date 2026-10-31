package io.github.nexalloy.revanced.instagram.dm

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import io.github.nexalloy.R
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

private const val SAVE_MEDIA_ACTION = "SaveMedia"
private const val ANIMATED_MEDIA = "com.instagram.model.direct.gifs.DirectAnimatedMedia"
private const val GIF_URL = "com.instagram.model.mediasize.GifUrlImpl"
private const val STORE_STICKER = "com.instagram.model.direct.stickerstore.DirectStoreSticker"
private const val TYPED_IMAGE_URL = "com.instagram.model.direct.stickerstore.TypedImageUrl"
private const val IMAGE_URL = "com.instagram.common.typedurl.ImageUrl"
private const val MAX_NODES = 300
private const val MAX_DEPTH = 3

private class StickerTypes(val animated: Class<*>, val gif: Class<*>, val store: Class<*>, val typed: Class<*>, val image: Class<*>)

private fun instanceFields(type: Class<*>): List<Field> =
    generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }.toList()

private fun StickerTypes.animatedUrl(media: Any): String? {
    var best: String? = null
    for (f in instanceFields(animated)) {
        if (f.type != gif) continue
        val url = f.get(media) ?: continue
        for (g in instanceFields(gif)) {
            if (g.type != String::class.java) continue
            val u = g.get(url) as? String ?: continue
            if (!u.startsWith("http")) continue
            if (u.substringBefore('?').lowercase().endsWith(".gif")) return u
            if (best == null) best = u
        }
    }
    return best
}

private fun StickerTypes.storeUrl(sticker: Any): String? {
    val getUrl = image.getMethod("getUrl")
    val getWidth = image.getMethod("getWidth")
    val getHeight = image.getMethod("getHeight")
    var best: String? = null
    var bestArea = -1L
    for (f in instanceFields(store)) {
        if (f.type != typed) continue
        val entry = f.get(sticker) ?: continue
        val img = instanceFields(typed).firstNotNullOfOrNull { g -> g.get(entry)?.takeIf { image.isInstance(it) } } ?: continue
        val url = runCatching { getUrl.invoke(img) as? String }.getOrNull() ?: continue
        val area = runCatching { (getWidth.invoke(img) as Int).toLong() * (getHeight.invoke(img) as Int) }.getOrDefault(0L)
        if (url.startsWith("http") && area > bestArea) { best = url; bestArea = area }
    }
    return best
}

private fun StickerTypes.urlOf(message: Any): String? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(message to 0)
    while (queue.isNotEmpty() && seen.size < MAX_NODES) {
        val (o, depth) = queue.poll()
        if (!seen.add(o)) continue
        if (store.isInstance(o)) { storeUrl(o)?.let { return it }; continue }
        if (animated.isInstance(o)) return animatedUrl(o)
        if (depth >= MAX_DEPTH) continue
        val name = o.javaClass.name
        if (!name.startsWith("X.") && !name.startsWith("com.instagram.")) continue
        for (f in instanceFields(o.javaClass)) {
            if (f.type.isPrimitive || f.type == String::class.java) continue
            runCatching { f.get(o) }.getOrNull()?.let { queue.add(it to depth + 1) }
        }
    }
    return null
}

val SaveChatStickers = patch(
    name = "Save chat stickers and GIFs",
    description = "Adds Save to the menu you get when you hold a sticker or GIF in a chat. GIFs keep their animation " +
        "and transparency; sticker pack stickers save at their largest size.",
) {
    val types = StickerTypes(
        ANIMATED_MEDIA.findClassOrNull(classLoader) ?: error("$ANIMATED_MEDIA not found"),
        GIF_URL.findClassOrNull(classLoader) ?: error("$GIF_URL not found"),
        STORE_STICKER.findClassOrNull(classLoader) ?: error("$STORE_STICKER not found"),
        TYPED_IMAGE_URL.findClassOrNull(classLoader) ?: error("$TYPED_IMAGE_URL not found"),
        IMAGE_URL.findClassOrNull(classLoader) ?: error("$IMAGE_URL not found"),
    )
    val messageClass = ::messageVoiceMediaField.field.declaringClass
    val saveFlag = ::messageMenuActionGuardsFingerprint.fieldList.chunked(2).firstOrNull { pair ->
        val action = pair.getOrNull(1) ?: return@firstOrNull false
        runCatching { action.isAccessible = true; action.get(null)?.toString() == SAVE_MEDIA_ACTION }.getOrDefault(false)
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
        if (runCatching { types.urlOf(message) }.getOrNull() != null) saveFlag.setBoolean(target, true)
    }).installOn(menu)

    entry.hookMethod {
        before { param ->
            val message = param.args.firstNotNullOfOrNull { valueOfType(it, messageClass) } ?: return@before
            val url = runCatching { types.urlOf(message) }.getOrNull() ?: return@before
            val ctx: Context = param.args.firstNotNullOfOrNull { valueOfType(it, Activity::class.java) as? Context }
                ?: AndroidAppHelper.currentApplication() ?: return@before
            param.result = null
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo))
            MediaSaver.executor.submit {
                runCatching { MediaSaver.saveAnimated(ctx, url, "instagram_sticker_${System.currentTimeMillis()}", null) }
                    .onSuccess { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_photo_saved)) }
                    .onFailure { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, it.message ?: it.javaClass.simpleName)) }
            }
        }
    }
}
