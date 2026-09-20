package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Profile Picture Downloader
 *
 * Strategy:
 *   Hook View.onAttachedToWindow() globally, filter for "expanded_profile_pic" by resource name
 *   (cached as an int ID after first resolution). When found, attach a long-press listener that
 *   reads the ImageUrl field (getUrl()) from IgImageView and downloads via FeedVideoDownloadHook
 *   helpers.
 *
 * Gated by FeatureFlags.enableProfileDownload.
 */
object ProfilePicDownloadHook {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val HOOKED_TAG = "ie_profile_dl"

    /** Cached resource ID for "expanded_profile_pic"; 0 = not resolved yet. */
    @Volatile
    private var expandedPicViewId = 0

    /** Set once the lookup below has run, so a missing id is not looked up on every attach. */
    @Volatile
    private var expandedPicViewIdResolved = false

    // ── Install ───────────────────────────────────────────────────────────────

    fun install() {
        // Mark status before hook setup so the toast shows correctly
        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled(
                "ProfileDownload", R.string.ig_dialog_downloader_profiles
            )
            FeatureStatusTracker.setHooked("ProfileDownload")
        }

        // Hook View.onAttachedToWindow — fires once per view attachment, works for any
        // window type (Activity, Dialog, BottomSheet) without relying on layout listeners.
        XposedHelpers.findAndHookMethod(
            View::class.java, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureFlags.enableProfileDownload) return
                    val v = param.thisObject as View
                    val vid = v.id
                    if (vid == View.NO_ID) return

                    // This fires for every view the app attaches, so the body has to stay down
                    // to an int comparison. Looking the id up by name once, on the first attach,
                    // keeps it that way; asking the resource table for each view's entry name
                    // instead would cost a lookup per attached view for the whole session.
                    var wanted = expandedPicViewId
                    if (wanted == 0) {
                        wanted = resolveExpandedPicViewId(v)
                        if (wanted == 0) return
                    }
                    if (vid != wanted) return

                    injectLongPress(v)
                }
            }
        )
    }

    /**
     * Looks up the id of the expanded profile picture view, once per process.
     *
     * Returns 0 when the name is not in this build's resources, and remembers that, so the
     * hook falls straight through on every later attach instead of retrying the lookup.
     */
    @SuppressLint("DiscouragedApi")
    private fun resolveExpandedPicViewId(view: View): Int {
        if (expandedPicViewIdResolved) return expandedPicViewId
        synchronized(ProfilePicDownloadHook::class.java) {
            if (expandedPicViewIdResolved) return expandedPicViewId
            var id = 0
            try {
                val ctx = view.context
                id = ctx.resources.getIdentifier(
                    "expanded_profile_pic", "id", ctx.packageName
                )
            } catch (ignored: Throwable) {
            }
            expandedPicViewId = id
            expandedPicViewIdResolved = true
            if (id == 0) {
                ModuleLog.line("(NA|ProfileDL) expanded_profile_pic id not present in this build")
            }
            return id
        }
    }

    // ── UI injection ──────────────────────────────────────────────────────────

    private fun injectLongPress(view: View) {
        try {
            view.tag = HOOKED_TAG
            view.setOnLongClickListener { v ->
                // Resolve activity lazily at tap time — context is valid at this point
                val ctx = v.context
                val activity = activityFromContext(ctx)

                val url = extractUrl(v)
                if (url == null) {
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found),
                        Toast.LENGTH_SHORT
                    ).show()
                    ModuleLog.line("(NA|ProfileDL) ❌ URL extraction failed")
                    return@setOnLongClickListener true
                }
                val username = if (activity != null) extractUsername(activity) else null
                val filename =
                    FeedVideoDownloadHook.buildFilename(username, "profile", null, false)

                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_toast_downloading_profile_pic),
                    Toast.LENGTH_SHORT
                ).show()
                Thread {
                    try {
                        val delegated = FeedVideoDownloadHook.downloadAndSave(
                            ctx, url, filename, false, username
                        )
                        if (!delegated) {
                            mainHandler.post {
                                Toast.makeText(
                                    ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_saved),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Throwable) {
                        ModuleLog.line("(NA|ProfileDL) ❌ download: " + e.message)
                        mainHandler.post {
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_toast_download_failed, e.message),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
                true
            }
        } catch (t: Throwable) {
            ModuleLog.line("(NA|ProfileDL) ❌ injectLongPress: " + t.message)
        }
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    /**
     * Extracts the image URL from the profile pic view (CircularImageView extends IgImageView).
     * Scans known ImageUrl-typed fields by name; tries multiple candidates in order.
     */
    private fun extractUrl(view: View): String? {
        for (fieldName in arrayOf("A0E", "A0D", "A0c")) {
            try {
                val url = getUrlFromImageUrlField(view, fieldName)
                if (url != null) return url
            } catch (ignored: Throwable) {
            }
        }

        // Fallback: tag-based URI
        try {
            val tag = view.tag
            if (tag is Uri) return tag.toString()
            if (tag is String && tag.startsWith("http")) return tag
        } catch (ignored: Throwable) {
        }

        ModuleLog.line(
            "(NA|ProfileDL) ❌ all URL strategies failed for " + view.javaClass.name
        )
        return null
    }

    /**
     * Walks the class hierarchy to find a field by name, reads it as an ImageUrl,
     * then calls getUrl() on it (ImageUrl is a non-obfuscated interface).
     */
    private fun getUrlFromImageUrlField(view: View, fieldName: String): String? {
        var cls: Class<*>? = view.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                val imageUrl = f.get(view) ?: return null
                val getUrl = imageUrl.javaClass.getMethod("getUrl")
                val result = getUrl.invoke(imageUrl)
                if (result is String && result.startsWith("http")) return result
                return null
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    // ── Username extraction ───────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    private fun extractUsername(activity: Activity): String? {
        try {
            val ab = activity.actionBar
            if (ab?.title != null) {
                val t = ab.title.toString().trim()
                if (looksLikeUsername(t)) return t
            }
        } catch (ignored: Throwable) {
        }

        try {
            val titleId = activity.resources
                .getIdentifier("action_bar_title", "id", activity.packageName)
            if (titleId != 0) {
                val tv = activity.findViewById<TextView>(titleId)
                if (tv != null) {
                    val t = tv.text.toString().trim()
                    if (looksLikeUsername(t)) return t
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val t = activity.title
            if (t != null && looksLikeUsername(t.toString().trim())) return t.toString().trim()
        } catch (ignored: Throwable) {
        }

        return null
    }

    private fun looksLikeUsername(s: String?): Boolean =
        s != null && s.isNotEmpty() && s.length <= 30 &&
            s.matches(Regex("[a-zA-Z0-9._]+")) &&
            !s.matches(Regex("\\d+"))

    // ── Context → Activity ────────────────────────────────────────────────────

    private fun activityFromContext(context: Context): Activity? {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Suppress("unused")
    @Throws(Exception::class)
    private fun downloadToStream(url: String, out: OutputStream) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
        )
        conn.connect()
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(32768)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
