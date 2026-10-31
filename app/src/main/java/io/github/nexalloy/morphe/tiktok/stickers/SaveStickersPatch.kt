package io.github.nexalloy.morphe.tiktok.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import app.morphe.extension.shared.Logger
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.downloads.TikTokSaver
import io.github.nexalloy.morphe.tiktok.shared.Model
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.patch
import java.lang.reflect.Method

private const val TAG = "TikTok stickers"

val SaveStickers = patch(
    name = "Save stickers",
    description = "Tap a sticker in a chat or in the comments for a menu to view it as usual or save it to your phone.",
) {
    val template = classLoader.loadClass(STICKER_TEMPLATE)
    val commentSticker = classLoader.loadClass(COMMENT_STICKER)
    val passing = ThreadLocal<Boolean>()

    fun menu(anchor: View, replay: () -> Unit, save: () -> Unit) {
        val items = listOf(
            TikTokUi.t(R.string.tt_sticker_view) to replay,
            TikTokUi.t(R.string.tt_sticker_save) to save,
        )
        if (items.any { it.first.isEmpty() }) return replay()
        val popup = PopupMenu(anchor.context, anchor)
        items.forEachIndexed { i, (title, _) -> popup.menu.add(0, i, i, title) }
        popup.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        popup.show()
    }

    fun intercept(method: Method, target: Any?, args: Array<Any?>, anchor: View, save: () -> Unit): Boolean {
        if (passing.get() == true) return false
        val replay = {
            passing.set(true)
            try { method.isAccessible = true; method.invoke(target, *args) }
            catch (e: Throwable) { Logger.printException({ "$TAG: replay failed" }, e) }
            finally { passing.remove() }
            Unit
        }
        return runCatching { menu(anchor, replay, save); true }.getOrDefault(false)
    }

    ::chatStickerClickFingerprint.hookMethod {
        before { param ->
            val anchor = param.args.firstOrNull() as? View ?: return@before
            val sticker = param.args.lastOrNull()?.takeIf { template.isInstance(it) } ?: return@before
            val image = Model.field(sticker, "imageComponent") ?: return@before
            val urls = (Model.field(image, "urls") as? List<*>)?.filterIsInstance<String>().orEmpty()
            val encrypted = !(Model.field(image, "decryptKey") as? String).isNullOrBlank()
            val id = Model.field(image, "id") as? String
            if (urls.isEmpty() && !encrypted) return@before
            val save = {
                if (encrypted || urls.isEmpty()) snapshot(anchor)?.let { TikTokSaver.saveStickerBitmap(it, id) }
                    ?: TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
                else TikTokSaver.saveSticker(urls, id)
            }
            if (intercept(param.method as Method, param.thisObject, param.args, anchor, save)) param.result = null
        }
    }

    ::commentStickerClickFingerprint.dexMethodList.map { it.toMethod() }.forEach { click ->
        click.hookMethod {
            before { param ->
                val anchor = param.args.lastOrNull() as? View ?: return@before
                val holder = param.thisObject ?: param.args.firstOrNull()
                val sticker = Model.ofType(holder, commentSticker) ?: return@before
                val urls = stickerUrls(sticker)
                if (urls.isEmpty()) {
                    de.robv.android.xposed.XposedBridge.log("[$TAG] comment sticker has no address")
                    return@before
                }
                val id = Model.string(sticker, "getId", "id")
                if (intercept(param.method as Method, param.thisObject, param.args, anchor) { TikTokSaver.saveSticker(urls, id) }) {
                    param.result = null
                }
            }
        }
    }
}

private fun stickerUrls(sticker: Any): List<String> {
    val sources = listOf("animatedUrl", "staticUrl").mapNotNull { Model.get(sticker, "get" + it.replaceFirstChar(Char::uppercase), it) }
    return sources.flatMap { source ->
        listOf("highResolutionUrl", "midResolutionUrl", "lowResolutionUrl").flatMap { name ->
            Model.urls(Model.get(source, "get" + name.replaceFirstChar(Char::uppercase), name))
        }
    }.distinct()
}

private fun snapshot(root: View): Bitmap? {
    val image = findImage(root) ?: return null
    val drawable = image.drawable ?: return null
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: image.width
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: image.height
    if (w <= 0 || h <= 0) return null
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        val old = drawable.copyBounds()
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        drawable.bounds = old
    }
}

private fun findImage(view: View): ImageView? {
    if (view is ImageView && view.drawable != null) return view
    if (view is ViewGroup) for (i in 0 until view.childCount) findImage(view.getChildAt(i))?.let { return it }
    return null
}
