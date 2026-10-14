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

class InstantSaveHook {

    fun install(cl: ClassLoader) {
        cacheCurrentInstant(cl)
        wrapLongPress(cl)
    }

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
      
        @Volatile
        private var currentMedia: Any? = null

        @Volatile
        private var lastSaveMs = 0L


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
