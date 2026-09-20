package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.os.SystemClock
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Save a received Instant (#184). "Instants" is internally `quicksnap`, and its viewer is Jetpack
 * Compose (so no button can be injected). An Instant item is the model whose Media lives in field
 * A01; the module's existing downloader understands that Media directly.
 *
 * Two literal-named Compose classes (stable across obfuscation — Kotlin package names survive):
 *  - QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$16$1$8$1 — the double-tap coroutine
 *    for the CURRENT card; its constructor receives the current Instant item as arg[6]. We hook
 *    the ctor (after) and cache that item's Media.
 *  - PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1 — the viewer's tap/long-press pointer
 *    handler (package com.instagram.quicksnap.viewer.compose, so it only fires inside the Instant
 *    viewer). Its constructor stores the onLongPress callback in field A09 (a kotlin Function0).
 *    We wrap that Function0 so a long-press saves the cached Instant, then still runs IG's original.
 *
 * The item/Media field names (A01/A06/A09) are the obfuscated field letters read reflectively;
 * we do NOT hardcode any obfuscated CLASS name — the two hooked classes are literal-named.
 */
class InstantSaveHook {

    fun install(cl: ClassLoader) {
        cacheCurrentInstant(cl)
        wrapLongPress(cl)
    }

    /** Hook the current-card coroutine ctor and cache arg[6]'s Media (field A01). */
    private fun cacheCurrentInstant(cl: ClassLoader) {
        try {
            val lambda = XposedHelpers.findClass(VIEWER_LAMBDA, cl)
            for (c in lambda.declaredConstructors) {
                XposedBridge.hookMethod(c, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val media = findInstantMedia(p.args)
                            if (media != null) currentMedia = media
                        } catch (ignored: Throwable) {
                        }
                    }
                })
            }
            ModuleLog.line("(NA|InstantSave) ✅ viewer cache hooked")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|InstantSave) ⚠️ viewer cache: " + t.message)
        }
    }

    /**
     * Hook the viewer pointer handler and wrap its onLongPress callback (field A0A — verified
     * on-device: A0A fires exactly once per gesture at the long-press threshold, while A0B is the
     * ordinary press/tap callback that fires repeatedly). On long-press we save the cached Instant,
     * then still run IG's original callback so its own behaviour (pause, etc.) is preserved.
     */
    private fun wrapLongPress(cl: ClassLoader) {
        try {
            val function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl)
            val handler = XposedHelpers.findClass(LONGPRESS_LAMBDA, cl)
            for (c in handler.declaredConstructors) {
                XposedBridge.hookMethod(c, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val orig = XposedHelpers.getObjectField(p.thisObject, "A0A")
                            if (orig == null || !function1.isInstance(orig)) return
                            // already wrapped
                            if (orig.javaClass.name.startsWith("\$Proxy")) return
                            val wrapper = Proxy.newProxyInstance(
                                cl, arrayOf(function1),
                                InvocationHandler { _, method, a ->
                                    if ("invoke" == method.name && FeatureFlags.saveInstants) {
                                        val now = SystemClock.uptimeMillis()
                                        if (now - lastSaveMs > 1000) {
                                            lastSaveMs = now
                                            saveCurrent()
                                        }
                                    }
                                    // Java passed the args array straight through; Kotlin needs the
                                    // spread operator, and Proxy hands null for no-arg methods.
                                    method.invoke(orig, *(a ?: emptyArray()))
                                }
                            )
                            XposedHelpers.setObjectField(p.thisObject, "A0A", wrapper)
                        } catch (ignored: Throwable) {
                        }
                    }
                })
            }
            FeatureStatusTracker.setHooked("SaveInstants")
            ModuleLog.line("(NA|InstantSave) ✅ long-press hooked")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|InstantSave) ⚠️ long-press: " + t.message)
        }
    }

    companion object {

        private const val VIEWER_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.QuickSnapMediaViewerScreenKt" +
                "\$QuickSnapMediaViewerScreen\$16\$1\$8\$1"
        private const val LONGPRESS_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.PointerTouchKt" +
                "\$handleTapAndLongPressWithRelease\$2\$1\$1\$1"

        // The current on-screen Instant's Media (com.instagram.feed.media.Media),
        // cached at bind time.
        @Volatile
        private var currentMedia: Any? = null

        // Debounce so both preloaded viewer instances (and any duplicate invoke)
        // don't double-save.
        @Volatile
        private var lastSaveMs = 0L

        /**
         * Finds the current Instant's Media among the ctor args by FIELD TYPE, not by the
         * obfuscated field letter — the arg is the Instant item, and its Media lives in the
         * (only) field whose type is com.instagram.feed.media.Media. This survives
         * field-letter/arg-order drift across versions (nothing here is hardcoded to
         * "A01"/index 6).
         */
        private fun findInstantMedia(args: Array<Any?>?): Any? {
            if (args == null) return null
            for (a in args) {
                if (a == null) continue
                val cn = a.javaClass.name
                if (cn.startsWith("java.") || cn.startsWith("android") ||
                    cn.startsWith("kotlin")
                ) continue
                for (f: Field in a.javaClass.declaredFields) {
                    if ("com.instagram.feed.media.Media" != f.type.name) continue
                    try {
                        f.isAccessible = true
                        val media = f.get(a)
                        if (media != null) return media
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return null
        }

        /** Saves the cached current Instant's media via the module's downloader. */
        private fun saveCurrent() {
            val media = currentMedia
            val ctx = AndroidAppHelper.currentApplication() ?: return
            if (media == null) {
                FeedVideoDownloadHook.mainHandler.post {
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_instant_save_none), Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
            FeedVideoDownloadHook.executor.submit {
                try {
                    val urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media)
                    if (urls.isEmpty()) {
                        FeedVideoDownloadHook.mainHandler.post {
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_instant_save_none),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@submit
                    }
                    val url = urls[0]
                    val isVid = FeedVideoDownloadHook.isVideoUrl(url)
                    var username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media)
                    if (username == null) username = "instant"
                    var mediaId = "0"
                    try {
                        val id = media.javaClass.getMethod("getId").invoke(media)
                        if (id is String && id.isNotEmpty()) mediaId = id
                    } catch (ignored: Throwable) {
                    }
                    val fn = FeedVideoDownloadHook.buildFilename(
                        username, "instant", mediaId, isVid
                    )
                    val fUser = username
                    FeedVideoDownloadHook.mainHandler.post {
                        Toast.makeText(
                            ctx, I18n.t(ctx, R.string.ig_instant_saving), Toast.LENGTH_SHORT
                        ).show()
                    }
                    val delegated =
                        FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVid, fUser)
                    if (!delegated) {
                        FeedVideoDownloadHook.mainHandler.post {
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_instant_saved), Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Throwable) {
                    ModuleLog.line("(NA|InstantSave) ❌ save failed: $e")
                }
            }
        }
    }
}
