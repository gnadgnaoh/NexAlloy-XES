package io.github.nexalloy.revanced.zalo.download

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

private const val MENU_ITEM_ID = 20056
private const val ALL_ITEM_ID = 20057
private const val DEFAULTS_MASK = 0x1f0
private const val DOWNLOAD_ICON = "ic_bottom_sheet_story_download"
private const val DOWNLOAD_TITLE = "str_bottom_sheet_story_download_tittle"
private const val MEDIA_ICON = "ic_bottom_sheet_menu_download"
private const val PHOTO_TITLE = "str_bottom_sheet_menu_photo_download"
private const val VIDEO_TITLE = "str_bottom_sheet_menu_video_download"
private const val BUNDLE_DATA = "com.zing.zalo.social.features.menu_feed.model.BottomSheetMenuBundleData"
private const val PHOTO_SHEET = 6
private const val VIDEO_SHEET = 7

val Download = patch(
    name = "Download",
    description = "Adds Download to the menu of friends' stories, photos and videos, including profile pictures, and Download or Download all to timeline posts.",
) {
    val builder = ::storyMenuBuilderFingerprint.method
    val factory = ::menuItemFactoryFingerprint.method.apply { isAccessible = true }
    val click = ::menuItemClickFingerprint.method
    val toJson = ::storyJsonFingerprint.method.apply { isAccessible = true }
    val storyClass = toJson.declaringClass
    val companionType = factory.parameterTypes[0]
    val companion = factory.returnType.declaredFields
        .single { Modifier.isStatic(it.modifiers) && it.type == companionType }
        .apply { isAccessible = true }.get(null) ?: error("menu item companion not found")
    val stories = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    builder.hookMethod {
        after { param ->
            @Suppress("UNCHECKED_CAST")
            val list = param.result as? MutableList<Any?> ?: return@after
            val data = param.args.firstOrNull() ?: return@after
            val ctx = ZaloModel.valueOfType(data, Context::class.java) as? Context ?: return@after
            val story = ZaloModel.reachable(data, storyClass) ?: return@after
            val res = ctx.resources
            @SuppressLint("DiscouragedApi")
            val icon = res.getIdentifier(DOWNLOAD_ICON, "drawable", ctx.packageName)
            @SuppressLint("DiscouragedApi")
            val title = res.getIdentifier(DOWNLOAD_TITLE, "string", ctx.packageName)
            if (icon == 0 || title == 0) return@after
            if (list.any { it != null && ZaloModel.holdsInt(it, icon) }) return@after
            val item = factory.invoke(null, companion, MENU_ITEM_ID, icon, res.getString(title), null, false, null, null, null, DEFAULTS_MASK)
                ?: return@after
            stories[item] = story
            list.add(item)
        }
    }

    val viewfull = ::viewfullMenuFingerprint.method
    val bundleType = viewfull.declaringClass.classLoader!!.loadClass(BUNDLE_DATA)
    val sheetType = bundleType.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .apply { isAccessible = true }
    val presenterGetter = viewfull.declaringClass.declaredMethods.single { m ->
        m.parameterCount == 0 && m.returnType.declaredFields.any { it.type == bundleType }
    }.apply { isAccessible = true }
    val presenterType = presenterGetter.returnType
    val presenterBundle = presenterType.declaredFields.single { it.type == bundleType }.apply { isAccessible = true }
    val presenterItems = presenterType.declaredFields.single { it.type == List::class.java }.apply { isAccessible = true }

    val feedJson = ::feedJsonFingerprint.method.apply { isAccessible = true }
    val feedClass = feedJson.declaringClass
    val feeds = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    viewfull.hookMethod {
        after { param ->
            val presenter = presenterGetter.invoke(param.thisObject) ?: return@after
            val bundle = presenterBundle.get(presenter) ?: return@after
            val list = ArrayList((presenterItems.get(presenter) as? List<*>) ?: return@after)
            val ctx = AndroidAppHelper.currentApplication() ?: return@after
            val res = ctx.resources
            @SuppressLint("DiscouragedApi")
            val icon = res.getIdentifier(MEDIA_ICON, "drawable", ctx.packageName)
            if (icon == 0) return@after
            var changed = false
            val native = when (sheetType.getInt(bundle)) {
                PHOTO_SHEET -> PHOTO_DOWNLOAD_ID to PHOTO_TITLE
                VIDEO_SHEET -> VIDEO_DOWNLOAD_ID to VIDEO_TITLE
                else -> null
            }
            if (native != null) {
                val existing = list.indexOfFirst { it != null && ZaloModel.holdsInt(it, icon) }
                @SuppressLint("DiscouragedApi")
                val title = res.getIdentifier(native.second, "string", ctx.packageName)
                if (existing < 0 && title != 0) {
                    factory.invoke(null, companion, native.first, icon, res.getString(title), null, false, null, null, null, DEFAULTS_MASK)
                        ?.let { list.add(0, it); changed = true }
                }
            }
            val media = if (native == null) ZaloModel.find(bundle, feedClass)?.let { FeedMedia.of(feedJson, it) } else null
            if (media != null && media.urls.isNotEmpty()) {
                val single = media.urls.size == 1 && media.missing == 0
                val title = when {
                    !single -> ZaloSaver.t(ctx, R.string.zl_dl_all_menu, media.urls.size + media.missing)
                    else -> {
                        @SuppressLint("DiscouragedApi")
                        val id = res.getIdentifier(if (media.videos > 0) VIDEO_TITLE else PHOTO_TITLE, "string", ctx.packageName)
                        if (id == 0) "" else res.getString(id)
                    }
                }
                if (title.isNotEmpty()) {
                    factory.invoke(null, companion, ALL_ITEM_ID, icon, title, null, false, null, null, null, DEFAULTS_MASK)?.let {
                        feeds[it] = media
                        list.add(0, it)
                        changed = true
                    }
                }
            }
            if (changed) presenterItems.set(presenter, list)
        }
    }

    click.hookMethod {
        before { param ->
            val item = param.args.firstOrNull() ?: return@before
            val ctx = AndroidAppHelper.currentApplication() ?: return@before
            stories[item]?.let { return@before saveStory(ctx, toJson, it) }
            val media = feeds[item] as? FeedMedia ?: return@before
            if (media.urls.size == 1 && media.missing == 0) ZaloSaver.save(ctx, media.urls[0], "zalo_${media.id}")
            else ZaloSaver.saveAll(ctx, media.urls, "zalo_${media.id}", media.missing, media.streams)
            if (closeSheet(param.thisObject)) param.result = null
        }
    }
}

private fun saveStory(ctx: Context, toJson: Method, story: Any) {
    val json = runCatching { toJson.invoke(story) as? JSONObject }.getOrNull()
        ?: return ZaloSaver.toast(ctx, ZaloSaver.t(ctx, R.string.zl_dl_no_media))
    val url = json.optString("url").takeIf { it.startsWith("http") && !it.contains(".m3u8") }
    if (url == null) {
        val stream = json.optString("urlHls").isNotBlank()
        return ZaloSaver.toast(ctx, ZaloSaver.t(ctx, if (stream) R.string.zl_dl_stream_only else R.string.zl_dl_no_media))
    }
    val owner = json.optString("ownerId").filter { it.isLetterOrDigit() }.ifEmpty { "user" }
    val id = json.optString("id").filter { it.isLetterOrDigit() }.ifEmpty { System.currentTimeMillis().toString() }
    ZaloSaver.save(ctx, url, "zalo_story_${owner}_$id")
}

private fun closeSheet(sheet: Any): Boolean {
    val close = generateSequence<Class<*>>(sheet.javaClass) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredMethods.firstOrNull { it.name == "close" && it.parameterCount == 0 } }
        ?: return false
    return runCatching { close.isAccessible = true; close.invoke(sheet) }.isSuccess
}
