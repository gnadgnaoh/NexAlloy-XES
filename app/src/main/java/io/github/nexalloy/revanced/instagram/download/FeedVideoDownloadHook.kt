package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.Deque
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class FeedVideoDownloadHook {

    // Username + media ID resolved at download trigger time
    @Volatile
    private var currentDownloadUsername: String? = null

    @Volatile
    private var currentDownloadMediaId: String? = null

    // ── Entry point ──────────────────────────────────────────────────────────

    fun install(classLoader: ClassLoader) {
        // Load Media and MediaExtKt
        try {
            val media = classLoader.loadClass("com.instagram.feed.media.Media")
            mediaClass = media
            val extKt = classLoader.loadClass("com.instagram.feed.media.MediaExtKt")
            mediaExtKtClass = extKt
            // Find static (Context, Media) -> String method (name changes every version)
            for (m in extKt.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 2 &&
                    "android.content.Context" == p[0].name &&
                    p[1] == media &&
                    m.returnType == String::class.java
                ) {
                    m.isAccessible = true
                    methodImageUrl = m
                    break
                }
            }
        } catch (ignored: Throwable) {
        }

        // Load VideoVersionIntf (stable public interface with getUrl())
        try {
            val intf = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf")
            videoVersionIntfClass = intf
            videoVersionGetUrl = intf.getMethod("getUrl")
        } catch (ignored: Throwable) {
        }

        // The model itself is bound by the patch right after this, through
        // bindMediaModel, which can also pass the dictionary class it found.
    }

    // Hook 1 in InstaEclipse captured every string passed to Uri.parse into urlBuffer, as a
    // last-resort source of CDN URLs for the floating download button. That button was
    // superseded by the context-menu entry, so nothing reads urlBuffer any more — and hooking
    // Uri.parse made the whole app pay for it, since Instagram parses URIs constantly. The
    // buffer that is still used is videoUrlBuffer, filled from getUrl() and the media model.

    // ── Hook 2: View.onAttachedToWindow ──────────────────────────────────────

    @Suppress("unused")
    private fun installViewHook() {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java, "onAttachedToWindow",
                object : XC_MethodHook() {
                    @SuppressLint("DiscouragedApi")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FeatureFlags.enablePostDownload) return
                        val view = param.thisObject as View
                        val ctx = view.context

                        val feedLikeId = ctx.resources.getIdentifier(
                            "row_feed_button_like", "id", ctx.packageName
                        )
                        val reelLikeId = ctx.resources.getIdentifier(
                            "like_button", "id", ctx.packageName
                        )
                        val clipsUfiId = ctx.resources.getIdentifier(
                            "clips_ufi_component", "id", ctx.packageName
                        )

                        val viewId = view.id
                        val isFeedLike = feedLikeId != 0 && viewId == feedLikeId
                        val isReelLike = reelLikeId != 0 && viewId == reelLikeId &&
                            hasAncestorWithId(view, clipsUfiId)

                        if (!isFeedLike && !isReelLike) return
                        val parent = view.parent as? ViewGroup ?: return

                        val now = System.currentTimeMillis()
                        val snapshot = snapshotUrlsSince(now - 10_000)

                        if (isFeedLike) {
                            // Feed post: inject floating download button
                            val existing = parent.findViewWithTag<View>(DOWNLOAD_BTN_TAG)
                            if (existing != null) {
                                synchronized(buttonUrls) { buttonUrls.put(existing, snapshot) }
                                return
                            }
                            injectDownloadButton(view, parent, ctx, snapshot)
                        } else {
                            // Reel: long-press the like button to download.
                            // ReelDownloadHook tags this view with the Media object via
                            // TAG_REEL_MEDIA.
                            view.setOnLongClickListener { lv ->
                                if (!FeatureFlags.enablePostDownload) {
                                    return@setOnLongClickListener false
                                }
                                val media = lv.getTag(TAG_REEL_MEDIA)
                                if (media != null) {
                                    val url = bestVideoUrlFromMedia(media)
                                    if (url != null) {
                                        ModuleLog.line("(NA|Reel) media tag hit, url=$url")
                                        onDownloadClicked(ctx, listOf(url), lv)
                                        return@setOnLongClickListener true
                                    }
                                    ModuleLog.line(
                                        "(NA|Reel) media tag set but no video URL found in object"
                                    )
                                }
                                // Fallback: filter buffer for m86 URLs only
                                // (combined stream, one per reel)
                                val all = snapshotUrlsSince(System.currentTimeMillis() - 60_000)
                                val m86 = ArrayList<String>()
                                for (u in all) {
                                    if (u.contains("/m86/") || u.contains("%2Fm86%2F")) m86.add(u)
                                }
                                val pick = if (m86.isEmpty()) all else m86
                                if (pick.isEmpty()) {
                                    Toast.makeText(
                                        ctx, I18n.t(ctx, R.string.ig_toast_no_reel_url_scroll),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@setOnLongClickListener true
                                }
                                ModuleLog.line(
                                    "(NA|Reel) buffer fallback, m86=" + m86.size +
                                        " total=" + all.size
                                )
                                // Take only the most recent URL (first in deque = newest)
                                onDownloadClicked(ctx, listOf(pick[0]), lv)
                                true
                            }
                            ModuleLog.line("(NA|Reel) long-press hook set on like_button")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            ModuleLog.line("(NexAlloy | MediaDownload): ❌ View hook: $t")
        }
    }

    // ── Button injection ──────────────────────────────────────────────────────

    private fun injectDownloadButton(
        saveBtn: View,
        parent: ViewGroup,
        ctx: Context,
        snapshot: List<String>,
    ) {
        val btn = ImageButton(ctx)
        btn.tag = DOWNLOAD_BTN_TAG
        btn.setImageResource(android.R.drawable.stat_sys_download)
        btn.setColorFilter(Color.WHITE)
        btn.background = null
        btn.contentDescription = "Download media"

        val size = dp(ctx, 34)
        val lp: ViewGroup.LayoutParams
        if (parent is LinearLayout) {
            val llp = LinearLayout.LayoutParams(size, size)
            llp.gravity = Gravity.CENTER_VERTICAL
            llp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0)
            lp = llp
        } else {
            val flp = FrameLayout.LayoutParams(size, size)
            flp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            flp.setMargins(0, 0, dp(ctx, 8), 0)
            lp = flp
        }
        btn.layoutParams = lp
        synchronized(buttonUrls) { buttonUrls.put(btn, snapshot) }

        btn.setOnClickListener { v ->
            val urls = resolveUrls(saveBtn, v)
            if (urls.isEmpty()) {
                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            onDownloadClicked(ctx, urls, saveBtn)
        }

        // Long-press the like button as fallback download trigger.
        // This is the primary path when LithoViews prevents button injection.
        saveBtn.setOnLongClickListener {
            if (!FeatureFlags.enablePostDownload) return@setOnLongClickListener false
            val urls = resolveUrls(saveBtn, btn)
            if (urls.isEmpty()) {
                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_toast_no_media), Toast.LENGTH_SHORT
                ).show()
                return@setOnLongClickListener true
            }
            onDownloadClicked(ctx, urls, saveBtn)
            true
        }

        parent.post {
            try {
                parent.addView(btn)
                btn.bringToFront()
            } catch (e: Exception) {
                ModuleLog.line("(NA|DL) Cannot inject download button: " + e.message)
            }
        }
    }

    // ── URL resolution — three-tier ───────────────────────────────────────────
    //
    // Tier 1: Reflect on the save button's click listener to find the exact Media
    //   object captured in its closure. Extract video URL via VideoVersionIntf.getUrl()
    //   or image URL via MediaExtKt helper. This is per-post with no timing ambiguity.
    //
    // Tier 2: buttonUrls snapshot taken when row_feed_button_save attached.
    //
    // Tier 3: Last 30 s of the Uri.parse buffer (catches lazy-loaded carousels).

    @SuppressLint("DiscouragedApi")
    @Suppress("UNUSED_PARAMETER")
    private fun resolveUrls(likeBtn: View, downloadBtn: View?): List<String> {
        // Tier-1a: like button's listener (works for standard feed posts)
        var urls = urlsFromSaveBtnListener(likeBtn)
        ModuleLog.line("(NA|DL) Tier-1a urls=" + urls.size)
        if (urls.isNotEmpty()) return urls

        // Tier-1b: bookmark/save button's listener.
        // The save button always captures the Media object (it needs it for
        // save-to-collection). IMPORTANT: row_feed_button_save is NOT a sibling of the like
        // button — it sits in the action bar parent (one level above the left-buttons group).
        // Walk up up to 4 parent levels so we reach the action bar container and find it there.
        val ctx = likeBtn.context
        val saveResId = ctx.resources.getIdentifier(
            "row_feed_button_save", "id", ctx.packageName
        )
        if (saveResId != 0) {
            var p: ViewParent? = likeBtn.parent
            var i = 0
            while (i < 4 && p is ViewGroup) {
                val vg: ViewGroup = p
                val realSaveBtn = vg.findViewById<View>(saveResId)
                if (realSaveBtn != null) {
                    ModuleLog.line("(NA|DL) Tier-1b found save btn at parent level $i")
                    urls = urlsFromSaveBtnListener(realSaveBtn)
                    ModuleLog.line("(NA|DL) Tier-1b urls=" + urls.size)
                    if (urls.isNotEmpty()) return urls
                    // found the button but listener had no URLs — no point going wider
                    break
                }
                i++
                p = vg.parent
            }
        }

        return ArrayList()
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    /**
     * Resolves the post author's username by scanning the media object already captured
     * in the save/like button's click listener closure.
     * Strategy: like button listener → if no media, walk up to save button → then scan
     * the media object graph (depth ≤ 2) for an object with getUsername().
     */
    @SuppressLint("DiscouragedApi")
    private fun getUsernameFromView(likeBtn: View?): String? {
        if (likeBtn == null || mediaClass == null) return null

        var media = getMediaFromListener(getOnClickListener(likeBtn))

        // Fallback to save button if like button listener is empty
        if (media == null) {
            val ctx = likeBtn.context
            val saveResId = ctx.resources.getIdentifier(
                "row_feed_button_save", "id", ctx.packageName
            )
            if (saveResId != 0) {
                var p: ViewParent? = likeBtn.parent
                var i = 0
                while (i < 4 && p is ViewGroup) {
                    val vg: ViewGroup = p
                    val saveBtn = vg.findViewById<View>(saveResId)
                    if (saveBtn != null) {
                        media = getMediaFromListener(getOnClickListener(saveBtn))
                        if (media != null) break
                    }
                    i++
                    p = vg.parent
                }
            }
        }

        val resolvedMedia = media ?: return null

        // TIER 0: Media-direct author getter (IG 446+/447.0.0.39+). The author accessors moved
        // onto com.instagram.feed.media.Media itself as lazy Pando getters; invoke the "user"
        // one directly on the media object. These getters materialise the User on demand, so
        // the field-scan tiers below cannot find it until it's been called once — this must
        // run first.
        val authorGetter = mediaAuthorGetter
        if (authorGetter != null && mediaClass?.isInstance(resolvedMedia) == true) {
            try {
                val userObj = authorGetter.invoke(resolvedMedia)
                if (userObj != null) {
                    val name = UserUtils.callUsernameGetter(userObj)
                    if (name != null) return name
                }
            } catch (ignored: Throwable) {
            }
        }

        // TIER 1: Use the resolved Dictionary Getter
        val dictGetter = dictUserGetter
        if (dictGetter != null &&
            (mutableMediaDictIntfClass != null || liveTreeMediaDictClass != null)
        ) {
            try {
                val dictIntf = findMediaDictionary(resolvedMedia)
                if (dictIntf != null) {
                    val userObj = dictGetter.invoke(dictIntf)
                    if (userObj != null) {
                        val name = UserUtils.callUsernameGetter(userObj)
                        if (name != null) return name
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        // TIER 2: Direct Class Bridge (Best for newer LiveTree versions)
        // If we can't find the dictionary, search the Media object for ANY field
        // that matches the User class directly.
        val userObj = findFieldOfType(resolvedMedia, userClass, 3)
        if (userObj != null) {
            val name = UserUtils.callUsernameGetter(userObj)
            if (name != null) return name
        }

        // TIER 3: Last resort recursive scan
        return scanObjectForUsername(
            resolvedMedia, 0, Collections.newSetFromMap(IdentityHashMap())
        )
    }

    private fun getMediaFromListener(listener: Any?): Any? {
        if (listener == null || mediaClass == null) return null
        return findFieldOfType(listener, mediaClass, 4)
    }

    /**
     * Extracts the short media ID (first segment of the Instagram ID) from the view's media
     * object.
     */
    @SuppressLint("DiscouragedApi")
    private fun getMediaIdFromView(likeBtn: View?): String? {
        if (likeBtn == null || mediaClass == null) return null
        try {
            var media = getMediaFromListener(getOnClickListener(likeBtn))
            if (media == null) {
                val ctx = likeBtn.context
                val saveResId = ctx.resources.getIdentifier(
                    "row_feed_button_save", "id", ctx.packageName
                )
                if (saveResId != 0) {
                    var p: ViewParent? = likeBtn.parent
                    var i = 0
                    while (i < 4 && p is ViewGroup) {
                        val vg: ViewGroup = p
                        val saveBtn = vg.findViewById<View>(saveResId)
                        if (saveBtn != null) {
                            media = getMediaFromListener(getOnClickListener(saveBtn))
                            if (media != null) break
                        }
                        i++
                        p = vg.parent
                    }
                }
            }
            if (media == null) return null
            val id = media.javaClass.getMethod("getId").invoke(media)
            if (id is String && id.isNotEmpty()) return id.split("_")[0]
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun onDownloadClicked(ctx: Context, urls: List<String>, saveBtn: View?) {
        currentDownloadUsername = getUsernameFromView(saveBtn)
        currentDownloadMediaId = getMediaIdFromView(saveBtn)
        ModuleLog.line(
            "(NA|DL) onDownloadClicked username=" + currentDownloadUsername +
                " mediaId=" + currentDownloadMediaId
        )
        val videos = ArrayList<String>()
        val images = ArrayList<String>()
        for (url in urls) {
            if (isVideoUrl(url)) videos.add(url) else images.add(url)
        }
        ModuleLog.line(
            "(NA|DL) total=" + urls.size + " videos=" + videos.size + " images=" + images.size
        )
        for (i in videos.indices) ModuleLog.line("(NA|DL) video[" + i + "]=" + videos[i])
        for (i in images.indices) ModuleLog.line("(NA|DL) image[" + i + "]=" + images[i])

        if (videos.isNotEmpty() && images.isNotEmpty()) {
            handleMixedContent(ctx, urls, videos, images, saveBtn)
        } else if (videos.isNotEmpty()) {
            handleVideoDownload(ctx, videos, saveBtn)
        } else if (images.size > 1) {
            showCarouselDialog(ctx, images, saveBtn)
        } else if (images.isNotEmpty()) {
            startDirectDownload(ctx, images[0], false)
        }
    }

    private fun handleMixedContent(
        ctx: Context,
        allUrls: List<String>,
        videos: List<String>,
        images: List<String>,
        saveBtn: View?,
    ) {
        executor.submit {
            val videoUrl = videos[0]
            val t = probeUrl(videoUrl)
            ModuleLog.line(
                "(NA|DL) probeUrl=" + videoUrl +
                    " hasVideo=" + t.hasVideo + " hasAudio=" + t.hasAudio
            )
            mainHandler.post {
                if (!t.hasVideo && t.hasAudio) {
                    // Audio-only background track — download the image instead
                    startDirectDownload(ctx, images[0], false)
                } else {
                    // Real video mixed with images — show carousel dialog for all items
                    showCarouselDialog(ctx, allUrls, saveBtn)
                }
            }
        }
    }

    private fun handleVideoDownload(ctx: Context, videos: List<String>, saveBtn: View?) {
        if (videos.size == 1) {
            startDirectDownload(ctx, videos[0], true)
            return
        }
        // Multiple video URLs → video carousel, show selection dialog immediately.
        // (DASH streams only ever produce a single URL via our Step-A resolver;
        //  multiple URLs always come from Step-B carousel item extraction.)
        showCarouselDialog(ctx, videos, saveBtn)
    }

    private fun showCarouselDialog(ctx: Context, urls: List<String>, saveBtn: View?) {
        var idx = if (saveBtn != null) findCarouselPosition(saveBtn) else 0
        if (idx >= urls.size) idx = 0
        val current = idx
        val n = urls.size
        AlertDialog.Builder(ctx)
            .setTitle(I18n.t(ctx, R.string.ig_dl_title))
            .setItems(
                arrayOf<CharSequence>(
                    I18n.t(ctx, R.string.ig_dl_carousel_current, current + 1, n),
                    I18n.t(ctx, R.string.ig_dl_carousel_all, n)
                )
            ) { _, w ->
                if (w == 0) {
                    val url = urls[current]
                    startDirectDownload(ctx, url, isVideoUrl(url))
                } else {
                    for (u in urls) startDirectDownload(ctx, u, isVideoUrl(u))
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_downloading_n_items, n),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.show()
    }

    private fun startDirectDownload(ctx: Context, url: String, isVideo: Boolean) {
        val fn = buildFilename(currentDownloadUsername, "post", currentDownloadMediaId, isVideo)
        ModuleLog.line("(NA|DL) startDirectDownload file=$fn")
        Toast.makeText(
            ctx,
            if (isVideo) I18n.t(ctx, R.string.ig_toast_downloading_video)
            else I18n.t(ctx, R.string.ig_toast_downloading_photo),
            Toast.LENGTH_SHORT
        ).show()
        val username = currentDownloadUsername
        executor.submit {
            try {
                val delegated = downloadAndSave(ctx, url, fn, isVideo, username)
                if (!delegated) {
                    mainHandler.post {
                        Toast.makeText(
                            ctx,
                            if (isVideo) I18n.t(ctx, R.string.ig_toast_video_saved)
                            else I18n.t(ctx, R.string.ig_toast_photo_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                ModuleLog.line(
                    "(NA|DL) download failed: " + e.javaClass.simpleName + ": " + e.message
                )
                mainHandler.post {
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_download_failed, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    @Suppress("unused")
    private fun downloadAndMerge(ctx: Context, videoUrl: String, audioUrl: String) {
        Toast.makeText(
            ctx, I18n.t(ctx, R.string.ig_toast_merging_video_audio), Toast.LENGTH_SHORT
        ).show()
        val username = currentDownloadUsername
        val mediaId = currentDownloadMediaId
        executor.submit {
            // No custom folder — merge locally and save via openOutputStream.
            var tv: File? = null
            var ta: File? = null
            var merged: File? = null
            try {
                val cache = ctx.cacheDir
                val ts = System.currentTimeMillis()
                tv = File(cache, "ie_v_$ts.mp4")
                ta = File(cache, "ie_a_$ts.mp4")
                merged = File(cache, "ie_m_$ts.mp4")
                downloadToFile(videoUrl, tv)
                downloadToFile(audioUrl, ta)
                val fn = buildFilename(username, "post", mediaId, true)
                mergeVideoAudio(tv.absolutePath, ta.absolutePath, merged.absolutePath)
                saveFileToDestination(ctx, merged, fn, true, username)
                mainHandler.post {
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_video_saved), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                mainHandler.post { startDirectDownload(ctx, videoUrl, true) }
            } finally {
                tv?.delete()
                ta?.delete()
                merged?.delete()
            }
        }
    }

    companion object {

        private const val DOWNLOAD_BTN_TAG = "ie_media_download_btn"

        /**
         * View tag key used by ReelDownloadHook to bind a Media object to the reel like_button.
         */
        @JvmField
        val TAG_REEL_MEDIA = "ie_reel_media".hashCode()

        // ── Class/method refs resolved once at hook install time ─────────────────
        private var mediaExtKtClass: Class<*>? = null
        private var mediaClass: Class<*>? = null

        @JvmStatic
        var mutableMediaDictIntfClass: Class<*>? = null
            private set

        private var liveTreeMediaDictClass: Class<*>? = null
        private var mediaModel: MediaModelResolver.Result? = null
        private val resolvedVideoVersionsGetters = ArrayList<Method>()
        private var resolvedIsVideoMethod: Method? = null

        /** MediaExtKt: static (Context, Media) -> String */
        private var methodImageUrl: Method? = null

        /** Media."carousel_media" getter: () -> List&lt;Media&gt; (IG 447+) */
        private var carouselMediaGetter: Method? = null

        // VideoVersionIntf – stable public interface with getUrl()
        internal var videoVersionIntfClass: Class<*>? = null

        /** VideoVersionIntf.getUrl() -> String */
        internal var videoVersionGetUrl: Method? = null

        // All () -> List candidates from MutableMediaDictIntf + its superinterfaces
        internal val carouselCandidates = ArrayList<Method>()

        // User class + the method on MutableMediaDictIntf that returns it — resolved via DexKit
        private var userClass: Class<*>? = null

        /** () -> UserClass on MutableMediaDictIntf */
        private var dictUserGetter: Method? = null

        // IG 446+/447.0.0.39+ removed MutableMediaDictIntf/LiveTreeMediaDict — the author
        // getters moved directly onto com.instagram.feed.media.Media as lazy Pando accessors
        // (e.g. A3P reads the "user" field via getOptionalTreeValueByHashCode(3599307)). This
        // getter is invoked directly on the Media object. Resolved via the stable Pando
        // field-id 3599307 (== "user".hashCode()).
        /** () -> UserClass on com.instagram.feed.media.Media */
        private var mediaAuthorGetter: Method? = null
        // userUsernameGetter lives in UserUtils — resolved here and stored there

        // ── Uri.parse fallback buffer ─────────────────────────────────────────────
        private class UrlEntry(u: String) {
            @JvmField val url: String = u

            @JvmField val time: Long = System.currentTimeMillis()
        }

        private const val MAX_URLS = 200
        private val urlBuffer: Deque<UrlEntry> = ArrayDeque()

        /** DexKit-captured video URLs */
        private val videoUrlBuffer: Deque<UrlEntry> = ArrayDeque()

        private val buttonUrls = WeakHashMap<View, List<String>>()

        @JvmField
        internal val executor: ExecutorService = Executors.newCachedThreadPool()

        @JvmField
        internal val mainHandler = Handler(Looper.getMainLooper())

        // ── Tier 1: Save-button listener search ───────────────────────────────────
        //
        // Strategy:
        //   1. Get the OnClickListener set by Instagram on the save button.
        //   2. Find the captured Media object in its closure (depth-limited field scan).
        //   3. From the MutableMediaDictIntf on the Media object:
        //      a. Check if any () -> List candidate returns VideoVersionIntf items
        //         → single video post: extract URL via getUrl(), return it.
        //      b. Check if any () -> List candidate returns >= 2 non-video items
        //         → carousel: try to extract per-item URLs.
        //      c. Fall back to MediaExtKt image URL helper for single photo posts.

        private fun urlsFromSaveBtnListener(saveBtn: View): List<String> {
            try {
                val listener = getOnClickListener(saveBtn) ?: return ArrayList()

                // Broad CDN URL scan of the listener's object graph (for plain String fields)
                val urls = ArrayList<String>()
                val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
                scanForCdnUrls(listener, urls, 0, visited)

                if (mediaClass != null) {
                    val media = findFieldOfType(listener, mediaClass, 4)

                    if (media != null) {
                        // ── Step A: Video detection ────────────────────────────────
                        // Two sub-passes for robustness:
                        //  A1 – field-graph scan (fast, works when Pando cache is populated)
                        //  A2 – method invocation on carouselCandidates (reaches JNI-backed
                        //       data that isn't exposed as a Java field until DIS() is called)
                        var videoUrl = findVideoUrlInObject(
                            media, Collections.newSetFromMap(IdentityHashMap()), 0
                        )
                        ModuleLog.line(
                            "(NA|DL) stepA1 videoUrl=" +
                                (videoUrl?.substring(0, min(80, videoUrl.length)) ?: "null")
                        )

                        if (videoUrl == null &&
                            (
                                mutableMediaDictIntfClass != null ||
                                    liveTreeMediaDictClass != null
                                ) &&
                            carouselCandidates.isNotEmpty()
                        ) {
                            // A2: invoke every () -> List method; any that returns
                            //     VideoVersionIntf items is the video-versions list.
                            //     Size >= 1 is enough (single video post).
                            val dictIntf = findMediaDictionary(media)
                            val intfClass = videoVersionIntfClass
                            val getUrl = videoVersionGetUrl
                            if (dictIntf != null && intfClass != null && getUrl != null) {
                                outer@ for (candidate in carouselCandidates) {
                                    try {
                                        val listObj = candidate.invoke(dictIntf)
                                        val items = listObj as? List<*> ?: continue
                                        if (items.isEmpty()) continue
                                        if (!intfClass.isInstance(items[0])) continue
                                        for (item in items) {
                                            if (!intfClass.isInstance(item)) continue
                                            try {
                                                val u = getUrl.invoke(item) as? String
                                                if (u != null && isCdnMediaUrl(u)) {
                                                    rememberVideoUrl(u)
                                                    videoUrl = u
                                                    break@outer
                                                }
                                            } catch (ignored: Throwable) {
                                            }
                                        }
                                    } catch (ignored: Throwable) {
                                    }
                                }
                            }
                            val v = videoUrl
                            ModuleLog.line(
                                "(NA|DL) stepA2 videoUrl=" +
                                    (v?.substring(0, min(80, v.length)) ?: "null")
                            )
                        }
                        videoUrl?.let { return listOf(it) }

                        // ── Step B: Carousel detection ─────────────────────────────
                        // Try every () -> List method on MutableMediaDictIntf (and its direct
                        // superinterfaces) to find the carousel item list.
                        if ((
                                mutableMediaDictIntfClass != null ||
                                    liveTreeMediaDictClass != null
                                ) && carouselCandidates.isNotEmpty()
                        ) {
                            val dictIntf = findMediaDictionary(media)
                            ModuleLog.line(
                                "(NA|DL) dictIntf=" + (dictIntf?.javaClass?.name ?: "null")
                            )

                            if (dictIntf != null) {
                                for (candidate in carouselCandidates) {
                                    try {
                                        val listObj = candidate.invoke(dictIntf)
                                        val items = listObj as? List<*> ?: continue
                                        if (items.size < 2) continue
                                        // Skip VideoVersionIntf lists — already handled in Step A
                                        val intfClass = videoVersionIntfClass
                                        if (intfClass != null && items.isNotEmpty() &&
                                            intfClass.isInstance(items[0])
                                        ) continue

                                        ModuleLog.line(
                                            "(NA|Car) candidate=" + candidate.name +
                                                " items=" + items.size
                                        )
                                        val carouselUrls = ArrayList<String>()

                                        for (idx in items.indices) {
                                            val item = items[idx] ?: continue

                                            // 1. If item is a video carousel item — get its
                                            //    video URL
                                            val itemVideo = findVideoUrlInObject(
                                                item,
                                                Collections.newSetFromMap(IdentityHashMap()), 0
                                            )
                                            if (itemVideo != null) {
                                                carouselUrls.add(itemVideo)
                                                continue
                                            }

                                            // 2. Try MediaExtKt helper — works when items are
                                            //    Media objects (piko shows newer Instagram
                                            //    carousel items are Media objects)
                                            val imgMethod = methodImageUrl
                                            if (imgMethod != null) {
                                                try {
                                                    val r = imgMethod.invoke(
                                                        null, saveBtn.context, item
                                                    )
                                                    if (r is String && isCdnMediaUrl(r)) {
                                                        ModuleLog.line(
                                                            "(NA|Car) item[" + idx +
                                                                "] mediaExtKt=" +
                                                                r.substring(0, min(60, r.length))
                                                        )
                                                        carouselUrls.add(r)
                                                        continue
                                                    }
                                                } catch (ignored: Throwable) {
                                                }
                                            }

                                            // 3. Probe all no-param String methods
                                            //    (Pando JNI nodes: LX/VPC, LX/5q9)
                                            val probed = probeCdnUrlViaStringMethods(item)
                                            ModuleLog.line(
                                                "(NA|Car) item[" + idx + "] probed=" + probed
                                            )
                                            if (probed != null) {
                                                carouselUrls.add(probed)
                                                continue
                                            }

                                            // 4. Generic CDN field scan as last resort
                                            val scanned = ArrayList<String>()
                                            scanForCdnUrls(
                                                item, scanned, 0,
                                                Collections.newSetFromMap(IdentityHashMap())
                                            )
                                            if (scanned.isNotEmpty()) {
                                                carouselUrls.add(pickBestImageUrl(scanned))
                                            }
                                        }

                                        ModuleLog.line(
                                            "(NA|Car) carouselUrls=" + carouselUrls.size
                                        )
                                        if (carouselUrls.size >= 2) return carouselUrls
                                    } catch (ignored: Throwable) {
                                    }
                                }
                            }
                        }

                        // ── Step C: Single photo ───────────────────────────────────
                        val imgMethod = methodImageUrl
                        if (imgMethod != null) {
                            try {
                                val img = imgMethod.invoke(null, saveBtn.context, media)
                                if (img is String && isCdnMediaUrl(img)) return listOf(img)
                            } catch (ignored: Throwable) {
                            }
                        }
                    }

                    // Fallback: prefer non-video URLs found by the object graph scan
                    val images = ArrayList<String>()
                    for (u in urls) if (!isVideoUrl(u)) images.add(u)
                    if (images.isNotEmpty()) return listOf(pickBestImageUrl(images))
                }

                return urls
            } catch (t: Throwable) {
                return ArrayList()
            }
        }

        /**
         * Probes all no-parameter String-returning methods on `obj` (including superclass
         * declared methods) and returns the first one that yields an Instagram CDN URL.
         *
         * This is needed for Pando/LiveTree JNI nodes (LX/VPC carousel items, LX/5q9) whose
         * image URLs are only accessible via obfuscated JNI-backed methods, not via fields.
         */
        private fun probeCdnUrlViaStringMethods(obj: Any?): String? {
            if (obj == null) return null
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                val cn = cls.name
                if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") &&
                    !cn.startsWith("com.facebook.")
                ) break
                for (m in cls.declaredMethods) {
                    if (m.parameterCount != 0 || m.returnType != String::class.java) continue
                    try {
                        m.isAccessible = true
                        val r = m.invoke(obj)
                        if (r is String && isCdnMediaUrl(r)) return r
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        /**
         * Depth-limited field-graph scan for any VideoVersionIntf instance inside `obj`.
         * Returns the first CDN URL found via `VideoVersionIntf.getUrl()`, or null.
         *
         * This is the primary video-detection path. It is version-independent: it does not
         * depend on knowing the obfuscated name of the method that returns the video-version
         * list (DIS(), or whatever it is renamed to in newer Instagram builds).
         */
        internal fun findVideoUrlInObject(
            obj: Any?,
            visited: MutableSet<Any>,
            depth: Int,
        ): String? {
            if (obj == null || depth > 5 || !visited.add(obj)) return null
            val intfClass = videoVersionIntfClass ?: return null
            val getUrl = videoVersionGetUrl ?: return null

            // Direct hit: obj itself implements VideoVersionIntf
            if (intfClass.isInstance(obj)) {
                try {
                    val url = getUrl.invoke(obj) as? String
                    if (url != null && isCdnMediaUrl(url)) {
                        rememberVideoUrl(url)
                        return url
                    }
                } catch (ignored: Throwable) {
                }
            }

            var cls: Class<*>? = obj.javaClass
            val cn = obj.javaClass.name
            if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") &&
                !cn.startsWith("com.facebook.")
            ) return null

            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    try {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val value = f.get(obj) ?: continue

                        if (value is List<*>) {
                            // List field — check if any element is a VideoVersionIntf
                            for (elem in value) {
                                if (elem != null && intfClass.isInstance(elem)) {
                                    try {
                                        val url = getUrl.invoke(elem) as? String
                                        if (url != null && isCdnMediaUrl(url)) {
                                            rememberVideoUrl(url)
                                            return url
                                        }
                                    } catch (ignored: Throwable) {
                                    }
                                }
                            }
                        } else {
                            // Recurse into Instagram/Facebook objects only
                            val vcn = value.javaClass.name
                            if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.") ||
                                vcn.startsWith("com.facebook.")
                            ) {
                                val found = findVideoUrlInObject(value, visited, depth + 1)
                                if (found != null) return found
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        /**
         * Collects ALL CDN video URLs found by walking the VideoVersionIntf graph inside `obj`.
         * Prefers m86 URLs (combined audio+video stream) — those are sorted to the front of the
         * list.
         */
        internal fun collectAllVideoUrls(
            obj: Any?,
            out: MutableList<String>,
            visited: MutableSet<Any>,
            depth: Int,
        ) {
            if (obj == null || depth > 7 || !visited.add(obj)) return

            if (looksLikeVideoVersion(obj)) {
                addVideoVersionUrl(obj, out)
                return // don't recurse into VideoVersionIntf objects
            }

            if (obj is Map<*, *>) {
                for (value in obj.values) collectAllVideoUrls(value, out, visited, depth + 1)
                return
            }
            if (obj is Iterable<*>) {
                for (value in obj) collectAllVideoUrls(value, out, visited, depth + 1)
                return
            }
            if (obj is Array<*>) {
                for (value in obj) collectAllVideoUrls(value, out, visited, depth + 1)
                return
            }

            var cls: Class<*>? = obj.javaClass
            val cn = obj.javaClass.name
            if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") &&
                !cn.startsWith("com.facebook.")
            ) return

            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    try {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val value = f.get(obj) ?: continue
                        val vcn = value.javaClass.name
                        if (value is Iterable<*> || value is Map<*, *> || value is Array<*> ||
                            vcn.startsWith("X.") || vcn.startsWith("com.instagram.") ||
                            vcn.startsWith("com.facebook.")
                        ) {
                            collectAllVideoUrls(value, out, visited, depth + 1)
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }

            // Pando/LiveTree frequently keeps video_versions in native storage. Calling
            // its no-arg List getter materializes the VideoVersion objects for inspection.
            cls = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (method in cls.declaredMethods) {
                    if (method.parameterCount != 0 ||
                        !List::class.java.isAssignableFrom(method.returnType)
                    ) continue
                    try {
                        method.isAccessible = true
                        val result = method.invoke(obj)
                        val items = result as? List<*> ?: continue
                        if (items.isEmpty()) continue
                        if (isVideoVersionsList(items)) {
                            for (item in items) addVideoVersionUrl(item, out)
                        } else {
                            collectAllVideoUrls(items, out, visited, depth + 1)
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
        }

        private fun looksLikeVideoVersion(item: Any?): Boolean {
            if (item == null) return false
            val intfClass = videoVersionIntfClass
            if (intfClass != null && intfClass.isInstance(item)) return true
            val name = item.javaClass.name.lowercase(Locale.US)
            // "videourl" covers modern IG (442+/447)
            // com.instagram.model.mediasize.VideoUrlImpl.
            return name.contains("videoversion") || name.contains("video_version") ||
                name.contains("videourl")
        }

        private fun isVideoVersionsList(items: List<*>): Boolean {
            var checked = 0
            for (item in items) {
                if (item == null) continue
                checked++
                if (!looksLikeVideoVersion(item)) return false
            }
            return checked > 0
        }

        private fun addVideoVersionUrl(item: Any?, out: MutableList<String>) {
            val url = videoUrlFromVersionObject(item) ?: return
            rememberVideoUrl(url)
            if (!out.contains(url)) out.add(url)
        }

        /** Returns the best video URL from the media object: prefers m86 (combined stream). */
        internal fun bestVideoUrlFromMedia(media: Any?): String? {
            val all = ArrayList<String>()
            collectVideoUrlsFromDictionary(media, all)
            if (all.isEmpty()) {
                val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
                collectAllVideoUrls(media, all, visited, 0)
            }
            if (all.isEmpty()) return null
            for (u in all) if (u.contains("/m86/") || u.contains("%2Fm86%2F")) return u
            return all[0] // fallback: first found
        }

        internal fun isMediaVideo(media: Any?): Boolean {
            val isVideoMethod = resolvedIsVideoMethod
            if (media == null || isVideoMethod == null) return false
            return try {
                val target = if (isVideoMethod.declaringClass.isInstance(media)) {
                    media
                } else {
                    MediaModelResolver.findObjectOfType(
                        media, isVideoMethod.declaringClass, 5
                    )
                } ?: return false
                val result = isVideoMethod.invoke(target)
                result is Boolean && result
            } catch (ignored: Throwable) {
                false
            }
        }

        /**
         * Invokes the Pando-backed video_versions accessor. These values often do not exist as
         * Java fields until the JNI getter is called, so the regular object-graph walk misses
         * them on current Instagram builds.
         */
        private fun collectVideoUrlsFromDictionary(media: Any?, out: MutableList<String>) {
            for (getter in resolvedVideoVersionsGetters) {
                val owner = if (getter.declaringClass.isInstance(media)) {
                    media
                } else {
                    MediaModelResolver.findObjectOfType(media, getter.declaringClass, 7)
                }
                if (owner != null) collectUrlsFromVideoVersionsMethod(owner, getter, out, true)
            }

            // Structural fallback for builds where DexKit cannot identify video_versions:
            // only accept a list when every URL-bearing item resolves to a video URL.
            val dict = findMediaDictionary(media) ?: return
            for (candidate in carouselCandidates) {
                if (resolvedVideoVersionsGetters.contains(candidate)) continue
                val owner = if (candidate.declaringClass.isInstance(dict)) {
                    dict
                } else {
                    MediaModelResolver.findObjectOfType(media, candidate.declaringClass, 5)
                }
                if (owner != null) {
                    collectUrlsFromVideoVersionsMethod(owner, candidate, out, false)
                }
            }
        }

        private fun collectUrlsFromVideoVersionsMethod(
            dict: Any,
            getter: Method,
            out: MutableList<String>,
            trustedVideoList: Boolean,
        ) {
            try {
                val result = getter.invoke(dict)
                val items = result as? List<*> ?: return
                if (items.isEmpty()) return

                val found = ArrayList<String>()
                for (item in items) {
                    val url = videoUrlFromVersionObject(item) ?: continue
                    if (trustedVideoList || isVideoUrl(url)) found.add(url)
                }
                if (!trustedVideoList && (found.isEmpty() || found.size != items.size)) return
                for (url in found) {
                    rememberVideoUrl(url)
                    if (!out.contains(url)) out.add(url)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun videoUrlFromVersionObject(item: Any?): String? {
            if (item == null) return null
            val intfClass = videoVersionIntfClass
            val getUrl = videoVersionGetUrl
            if (intfClass != null && getUrl != null && intfClass.isInstance(item)) {
                try {
                    val result = getUrl.invoke(item)
                    if (result is String && isCdnMediaUrl(result)) {
                        rememberVideoUrl(result)
                        return result
                    }
                } catch (ignored: Throwable) {
                }
            }
            val url = tryGetUrl(item)
            if (url != null && isCdnMediaUrl(url)) {
                rememberVideoUrl(url)
                return url
            }
            // Modern Instagram (442+/447): the version element is
            // com.instagram.model.mediasize.VideoUrlImpl, whose interface exposes no
            // getUrl():String — the CDN URL is stored in a plain String field instead.
            // Reflect the item's String fields and pick the one that is a CDN media URL.
            val fieldUrl = videoUrlFromStringFields(item)
            if (fieldUrl != null) {
                rememberVideoUrl(fieldUrl)
                return fieldUrl
            }
            return null
        }

        /**
         * Fallback URL extraction for modern video-version models (e.g. VideoUrlImpl) that keep
         * the CDN URL in a String field rather than exposing a getUrl() accessor. Walks the
         * item's declared String fields across its class hierarchy and returns the first CDN
         * media URL. Version-agnostic: matches by URL shape ([isCdnMediaUrl]), not by
         * obfuscated name.
         */
        private fun videoUrlFromStringFields(item: Any?): String? {
            if (item == null) return null
            var cls: Class<*>? = item.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(item)
                        if (v is String && isCdnMediaUrl(v)) return v
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        /**
         * Tries to call getUrl() on an object if it's available (handles VideoVersionIntf
         * and any other object that exposes a stable getUrl() method).
         */
        private fun tryGetUrl(obj: Any?): String? {
            if (obj == null) return null
            return try {
                val m = obj.javaClass.getMethod("getUrl")
                m.invoke(obj) as? String
            } catch (ignored: Throwable) {
                null
            }
        }

        /** Among multiple resolutions of the same image, prefer the full-size original. */
        private fun pickBestImageUrl(images: List<String>): String {
            for (url in images) {
                if (!url.contains("/s150x") && !url.contains("/s240x") &&
                    !url.contains("/s320x") && !url.contains("/s480x") &&
                    !url.contains("/s640x") && !url.contains("_s.jpg")
                ) {
                    return url
                }
            }
            return images[0]
        }

        /** Reads View.mListenerInfo.mOnClickListener via reflection. */
        private fun getOnClickListener(view: View): Any? {
            return try {
                val liField = View::class.java.getDeclaredField("mListenerInfo")
                liField.isAccessible = true
                val li = liField.get(view) ?: return null
                val clField = li.javaClass.getDeclaredField("mOnClickListener")
                clField.isAccessible = true
                clField.get(li)
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * Recursively scans an object's fields for Instagram CDN URL strings.
         * Only descends into X.* / com.instagram.* / com.facebook.* objects.
         */
        private const val MAX_SCAN_DEPTH = 6
        private const val MAX_SCAN_URLS = 20

        private fun scanForCdnUrls(
            obj: Any?,
            out: MutableList<String>,
            depth: Int,
            visited: MutableSet<Any>,
        ) {
            if (obj == null || depth > MAX_SCAN_DEPTH || out.size >= MAX_SCAN_URLS) return
            if (!visited.add(obj)) return

            var cls: Class<*>? = obj.javaClass
            val cn = obj.javaClass.name
            if (cn.startsWith("android.") || cn.startsWith("java.lang.") ||
                cn.startsWith("java.util.concurrent.") || cn.startsWith("kotlin.")
            ) return

            // Also try getUrl() for Pando tree nodes that expose it via method (not field)
            val directUrl = tryGetUrl(obj)
            if (directUrl != null && isCdnMediaUrl(directUrl) && !out.contains(directUrl)) {
                out.add(directUrl)
            }

            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    try {
                        f.isAccessible = true
                        val value = f.get(obj) ?: continue

                        if (value is String) {
                            if (isCdnMediaUrl(value) && !out.contains(value)) out.add(value)
                        } else if (value is List<*>) {
                            for (item in value) scanForCdnUrls(item, out, depth + 1, visited)
                        } else if (value is Array<*>) {
                            for (item in value) scanForCdnUrls(item, out, depth + 1, visited)
                        } else {
                            val vcn = value.javaClass.name
                            if (vcn.startsWith("X.") ||
                                vcn.startsWith("com.instagram.") ||
                                vcn.startsWith("com.facebook.")
                            ) {
                                scanForCdnUrls(value, out, depth + 1, visited)
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
        }

        private fun findFieldOfType(obj: Any?, target: Class<*>?, depth: Int): Any? {
            if (obj == null || target == null || depth < 0) return null
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (target.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        try {
                            return f.get(obj)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                cls = cls.superclass
            }
            if (depth > 0) {
                cls = obj.javaClass
                while (cls != null && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        f.isAccessible = true
                        try {
                            val v = f.get(obj) ?: continue
                            val vcn = v.javaClass.name
                            if (!vcn.startsWith("X.") && !vcn.startsWith("com.instagram.") &&
                                !vcn.startsWith("com.facebook.")
                            ) continue
                            val r = findFieldOfType(v, target, depth - 1)
                            if (r != null) return r
                        } catch (ignored: Throwable) {
                        }
                    }
                    cls = cls.superclass
                }
            }
            return null
        }

        /**
         * Finds the first field on `obj` whose declared type is assignable to `targetType`.
         * Used to locate interface-typed fields.
         */
        internal fun findFieldAssignableTo(obj: Any?, targetType: Class<*>?): Any? {
            if (obj == null || targetType == null) return null
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (targetType.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        try {
                            val v = f.get(obj)
                            if (v != null) return v
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        private fun findMediaDictionary(media: Any?): Any? {
            val model = mediaModel
            if (model != null) {
                val dict = MediaModelResolver.findDictionary(media, model, 3)
                if (dict != null) return dict
            }
            val dict = findFieldAssignableTo(media, liveTreeMediaDictClass)
            return dict ?: findFieldAssignableTo(media, mutableMediaDictIntfClass)
        }

        // ── Buffer helpers ────────────────────────────────────────────────────────

        private fun snapshotUrlsSince(from: Long): List<String> {
            val r = ArrayList<String>()
            synchronized(urlBuffer) {
                for (e in urlBuffer) {
                    if (e.time >= from) r.add(e.url) else break
                }
            }
            return r
        }

        @Suppress("unused")
        private fun snapshotVideoUrlsSince(from: Long): List<String> {
            val r = ArrayList<String>()
            synchronized(videoUrlBuffer) {
                for (e in videoUrlBuffer) {
                    if (e.time >= from) r.add(e.url) else break
                }
            }
            return r
        }

        internal fun rememberVideoUrl(url: String?) {
            if (url == null || !isCdnMediaUrl(url)) return
            synchronized(videoUrlBuffer) {
                if (!videoUrlBuffer.isEmpty() && videoUrlBuffer.peekFirst()?.url == url) return
                videoUrlBuffer.removeIf { entry -> entry.url == url }
                videoUrlBuffer.addFirst(UrlEntry(url))
                while (videoUrlBuffer.size > MAX_URLS) videoUrlBuffer.removeLast()
            }
        }

        private fun wasCapturedAsVideo(url: String?): Boolean {
            if (url == null) return false
            synchronized(videoUrlBuffer) {
                for (entry in videoUrlBuffer) {
                    if (entry.url == url) return true
                }
            }
            return false
        }

        /**
         * Binds the media dictionary model.
         *
         * Instagram keeps the media payload in a dictionary model whose class name is
         * obfuscated, so the patch locates it structurally and hands the class in here. When it
         * cannot be found the resolver falls back to whatever legacy interfaces the build still
         * has.
         */
        fun bindMediaModel(discoveredDictClass: Class<*>?, classLoader: ClassLoader) {
            try {
                val model = MediaModelResolver.resolve(classLoader, discoveredDictClass)
                mediaModel = model
                mutableMediaDictIntfClass = model.mutableDictClass
                liveTreeMediaDictClass = model.liveTreeDictClass
                carouselCandidates.clear()
                carouselCandidates.addAll(model.listCandidates)
                ModuleLog.line(
                    "(NA|DL) media dict=" +
                        (liveTreeMediaDictClass?.name ?: "not found")
                )
            } catch (t: Throwable) {
                ModuleLog.line("(NA|DL) media model resolution failed: $t")
            }
        }

        /**
         * Binds the `video_versions` getters.
         *
         * Only the List-returning candidates are kept. On a build where the dictionary class was
         * not found on its own, the first getter's declaring class *is* the dictionary, so the
         * model is resolved a second time from it.
         */
        fun bindVideoVersionsGetters(getters: List<Method>, classLoader: ClassLoader) {
            resolvedVideoVersionsGetters.clear()
            val seen = HashSet<String>()
            for (method in getters) {
                try {
                    if (!List::class.java.isAssignableFrom(method.returnType)) continue
                    val key = method.declaringClass.name + '#' + method.name
                    if (!seen.add(key)) continue
                    method.isAccessible = true
                    resolvedVideoVersionsGetters.add(method)
                } catch (ignored: Throwable) {
                }
            }

            if (liveTreeMediaDictClass == null && resolvedVideoVersionsGetters.isNotEmpty()) {
                bindMediaModel(resolvedVideoVersionsGetters[0].declaringClass, classLoader)
            }

            for (getter in resolvedVideoVersionsGetters) {
                if (!carouselCandidates.contains(getter)) carouselCandidates.add(getter)
            }
            ModuleLog.line(
                "(NA|DL) video_versions getters=" + resolvedVideoVersionsGetters.size
            )
        }

        /**
         * Binds the carousel-children accessor, which returns one child media per slide.
         *
         * Absent on older builds, which keep harvesting slides from the dictionary instead.
         */
        fun bindCarouselGetter(candidates: List<Method>) {
            for (m in candidates) {
                try {
                    if (!List::class.java.isAssignableFrom(m.returnType)) continue
                    m.isAccessible = true
                    carouselMediaGetter = m
                    break
                } catch (ignored: Throwable) {
                }
            }
            val getter = carouselMediaGetter
            if (getter != null && !carouselCandidates.contains(getter)) {
                carouselCandidates.add(0, getter)
            }
            ModuleLog.line(
                "(NA|DL) carousel getter=" +
                    (
                        if (getter == null) "not found"
                        else getter.declaringClass.name + "." + getter.name
                        )
            )
        }

        /** Binds Media's is-this-a-video check. */
        fun bindIsVideoMethod(method: Method?) {
            if (method == null) return
            method.isAccessible = true
            resolvedIsVideoMethod = method
        }

        /** Binds the `User` model class, which the author getters are matched against. */
        fun bindUserClass(resolved: Class<*>?) {
            userClass = resolved
            if (resolved != null) ModuleLog.line("(NA|DL) userClass=" + resolved.name)
        }

        /** Binds `User`'s username getter. */
        fun bindUsernameGetter(method: Method?) {
            if (method == null) return
            method.isAccessible = true
            UserUtils.userUsernameGetter = method
            ModuleLog.line("(NA|DL) usernameGetter=" + method.name)
        }

        /** Binds the author getter Instagram 446+ moved onto `Media`. */
        fun bindMediaAuthorGetter(method: Method?) {
            if (method == null || userClass == null) return
            method.isAccessible = true
            mediaAuthorGetter = method
            ModuleLog.line("(NA|DL) mediaAuthorGetter=" + method.name)
        }

        /**
         * Binds the dictionary's author getter.
         *
         * Instagram 423+ often hides it in a parent interface, so the hierarchy is walked
         * first — that costs nothing and needs no lookup. Builds from 437 moved the accessor
         * onto the concrete class, where several zero-arg `User` getters sit side by side, so
         * `dexKitCandidates` carries the one reading the Pando `user` field.
         */
        fun bindDictUserGetter(dexKitCandidates: List<Method>) {
            val user = userClass
            if ((mutableMediaDictIntfClass == null && liveTreeMediaDictClass == null) ||
                user == null
            ) return

            val queue: Deque<Class<*>> = ArrayDeque()
            val visited = HashSet<Class<*>>()
            mutableMediaDictIntfClass?.let { queue.add(it) }

            while (!queue.isEmpty()) {
                val curr = queue.poll()
                if (curr == null || !visited.add(curr)) continue

                for (m in curr.declaredMethods) {
                    if (m.parameterCount == 0 && m.returnType == user) {
                        m.isAccessible = true
                        dictUserGetter = m
                        ModuleLog.line("(NA|DL) dictUserGetter (interface)=" + m.name)
                        return
                    }
                }
                Collections.addAll(queue, *curr.interfaces)
            }

            for (m in dexKitCandidates) {
                m.isAccessible = true
                dictUserGetter = m
                ModuleLog.line("(NA|DL) dictUserGetter (concrete class)=" + m.name)
                return
            }

            ModuleLog.line("(NA|DL) dictUserGetter unresolved")
        }

        /**
         * Captures a CDN URL from a `getUrl()` return value.
         *
         * Hooked on every `VideoVersionIntf` implementor, which makes the capture independent
         * of the obfuscated name of whatever returns the video-versions list.
         */
        fun onVideoUrlReturned(param: XC_MethodHook.MethodHookParam) {
            if (!FeatureFlags.enablePostDownload) return
            val url = param.result as? String ?: return
            if (!isCdnMediaUrl(url)) return
            rememberVideoUrl(url)
        }

        // ── Filename + directory helpers ──────────────────────────────────────────

        internal fun buildFilename(
            username: String?,
            type: String,
            mediaId: String?,
            isVideo: Boolean,
        ): String {
            val u = if (!username.isNullOrEmpty()) username else "unknown"
            val id = if (!mediaId.isNullOrEmpty()) {
                mediaId
            } else {
                System.currentTimeMillis().toString()
            }
            val ext = if (isVideo) ".mp4" else ".jpg"
            val sb = StringBuilder(u).append('_').append(type).append('_').append(id)
            if (FeatureFlags.downloaderAddTimestamp) {
                sb.append('_').append(
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                )
            }
            return sb.append(ext).toString()
        }

        /**
         * Opens a writable OutputStream for the download destination, handling all storage
         * strategies:
         *   1. Raw file path (custom folder, when one is configured)
         *   2. MediaStore Downloads (API 29+, default scoped-storage path)
         *   3. Legacy direct file (API < 29)
         */
        @Throws(Exception::class)
        internal fun openOutputStream(
            ctx: Context,
            filename: String,
            isVideo: Boolean,
            username: String?,
        ): OutputStream {
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

            // 1. Raw path — preferred when set
            if (FeatureFlags.downloaderCustomPath.isNotEmpty()) {
                try {
                    return openRawPathOutputStream(filename, username)
                } catch (e: Exception) {
                    ModuleLog.line(
                        "(NA|DL) Raw path failed, falling back to MediaStore: " + e.message
                    )
                }
            }

            // 2. MediaStore (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return openMediaStoreOutputStream(ctx, filename, mimeType, username)
            }

            // 3. Legacy API < 29: direct file write
            @Suppress("DEPRECATION")
            var dir = File(Environment.getExternalStorageDirectory(), "NexAlloy")
            if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) {
                dir = File(dir, username)
            }
            dir.mkdirs()
            return FileOutputStream(File(dir, filename))
        }

        @Throws(Exception::class)
        private fun openRawPathOutputStream(filename: String, username: String?): OutputStream {
            val rawPath = FeatureFlags.downloaderCustomPath
            // Reject if path conversion failed and we got a content URI string as fallback
            if (rawPath.startsWith("content://")) {
                throw Exception("Not a raw file path: $rawPath")
            }
            var dir = File(rawPath)
            if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) {
                dir = File(dir, username)
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw Exception("Cannot create dir: " + dir.absolutePath)
            }
            return FileOutputStream(File(dir, filename))
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        @SuppressLint("NewApi")
        @Throws(Exception::class)
        private fun openMediaStoreOutputStream(
            ctx: Context,
            filename: String,
            mimeType: String,
            username: String?,
        ): OutputStream {
            val relPath = buildMediaStoreRelPath(username)
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = ctx.contentResolver.insert(collection, values)
                ?: throw Exception("MediaStore insert failed")
            return ctx.contentResolver.openOutputStream(itemUri)
                ?: throw Exception("MediaStore openOutputStream returned null")
        }

        // Standard top-level directories that MediaStore.Downloads accepts as RELATIVE_PATH
        // roots
        private val MS_ROOTS = hashSetOf(
            "Download", "Downloads", "Pictures", "DCIM", "Movies", "Music",
            "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks"
        )

        /**
         * Derives the MediaStore RELATIVE_PATH for the download.
         * - If the custom path falls under a known MediaStore root (Download, Pictures, …),
         *   it is used directly (e.g. Pictures/IG).
         * - Otherwise the path is nested under Download/ (e.g. /sdcard/Test55 →
         *   Download/Test55).
         * - Falls back to Download/NexAlloy when no custom path is set.
         */
        private fun buildMediaStoreRelPath(username: String?): String {
            val customPath = FeatureFlags.downloaderCustomPath
            var base = "Download/NexAlloy" // default

            if (customPath.isNotEmpty() && !customPath.startsWith("content://")) {
                @Suppress("DEPRECATION")
                val extBase = Environment.getExternalStorageDirectory().absolutePath
                if (customPath.startsWith("$extBase/")) {
                    // e.g. "Test55" or "Pictures/IG"
                    val relative = customPath.substring(extBase.length + 1)
                    val topLevel = relative.split("/")[0]
                    base = if (MS_ROOTS.contains(topLevel)) relative else "Download/$relative"
                }
            }

            if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) {
                base += "/$username"
            }
            return base
        }

        /** Copies tempFile to the download destination. */
        @Throws(Exception::class)
        internal fun saveFileToDestination(
            ctx: Context,
            tempFile: File,
            filename: String,
            isVideo: Boolean,
            username: String?,
        ) {
            FileInputStream(tempFile).use { input ->
                openOutputStream(ctx, filename, isVideo, username).use { out ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }

        /**
         * Public helper: copies an already-downloaded LOCAL file into the gallery/download
         * destination off the UI thread, with success/failure toasts. Used by the cached-story
         * viewer (cross-package).
         */
        fun saveLocalFileToGallery(
            ctx: Context,
            localPath: String,
            author: String?,
            id: String?,
            video: Boolean,
            okMsg: String,
            failMsg: String,
        ) {
            executor.submit {
                try {
                    val src = File(localPath)
                    if (!src.exists()) return@submit
                    val fn = buildFilename(author, "story", id, video)
                    saveFileToDestination(ctx, src, fn, video, author)
                    mainHandler.post {
                        Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    mainHandler.post {
                        Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        /**
         * Downloads `url` and saves it with the configured destination.
         *
         * The file is written through [openOutputStream]: a raw custom folder when one is
         * configured, otherwise MediaStore (Download/NexAlloy by default).
         *
         * @return always `false` — the save happens inline, so the caller shows the toast.
         */
        @Throws(Exception::class)
        internal fun downloadAndSave(
            ctx: Context,
            url: String?,
            filename: String,
            isVideo: Boolean,
            username: String?,
        ): Boolean {
            // No custom folder configured → download to a neutral temporary file first.
            // The response and file signature decide the final MIME/extension; CDN URL text
            // alone is not reliable on recent Instagram versions.
            val temp = File.createTempFile("ie_dl_", ".bin", ctx.cacheDir)
            try {
                val responseType = downloadToFileAndGetType(url, temp)
                val detected = MediaTypeDetector.resolve(
                    temp, responseType, if (isVideo) "video/mp4" else "image/jpeg", filename
                )
                ModuleLog.line(
                    "(NA|DL|Type) requested=" + (if (isVideo) "video" else "image") +
                        " response=" + responseType + " detected=" + detected.kind +
                        " file=" + detected.filename
                )
                saveFileToDestination(
                    ctx, temp, detected.filename ?: filename, detected.isVideo(), username
                )
            } finally {
                temp.delete()
            }
            return false
        }

        /**
         * Collects Instagram CDN media URLs from the given object graph.
         * Used by PostDownloadContextMenuHook as a fallback URL source.
         */
        internal fun collectCdnUrls(obj: Any?): List<String> {
            val out = ArrayList<String>()
            scanForCdnUrls(obj, out, 0, Collections.newSetFromMap(IdentityHashMap()))
            return out
        }

        /**
         * Extracts the image URL from a Media object using the MediaExtKt helper.
         * Returns null if not available (e.g. MediaExtKt not resolved or media is a video-only
         * post).
         */
        internal fun imageUrlFromMedia(ctx: Context?, media: Any?): String? {
            val imgMethod = methodImageUrl
            if (imgMethod == null || ctx == null || media == null) return null
            return try {
                val r = imgMethod.invoke(null, ctx, media)
                if (r is String && isCdnMediaUrl(r)) r else null
            } catch (ignored: Throwable) {
                null
            }
        }

        /**
         * Extracts all downloadable URLs from a Media object.
         * Returns a single-entry list for plain photo/video posts, multi-entry for carousels.
         * Steps: (A) video, (B) carousel via MutableMediaDictIntf, (C) single photo, (D) CDN
         * scan.
         */
        internal fun extractAllUrlsFromMedia(ctx: Context?, media: Any?): List<String> {
            if (media == null) return ArrayList()

            // Step 0 (IG 447+): carousel-first. Must run BEFORE the single-video
            // short-circuit — otherwise a carousel that contains a video collapses to one URL
            // (the graph walk in bestVideoUrlFromMedia returns the first video/audio it finds
            // anywhere). Only fires when the "carousel_media" accessor resolved (absent on
            // older builds → falls through).
            val carousel = extractCarouselUrls(ctx, media)
            if (carousel != null && carousel.size >= 2) return carousel

            // Step A: single video
            val videoUrl = bestVideoUrlFromMedia(media)
            if (videoUrl != null) return arrayListOf(videoUrl)

            ModuleLog.line(
                "(NA|Post|DEBUG) carousel check: mutableMediaDictIntfClass=" +
                    (mutableMediaDictIntfClass?.name ?: "null") +
                    " carouselCandidates=" + carouselCandidates.size
            )

            // Step B: carousel (MutableMediaDictIntf candidates)
            if ((mutableMediaDictIntfClass != null || liveTreeMediaDictClass != null) &&
                carouselCandidates.isNotEmpty()
            ) {
                val dictIntf = findMediaDictionary(media)
                ModuleLog.line(
                    "(NA|Post|DEBUG) dictIntf=" + (dictIntf?.javaClass?.name ?: "null")
                )
                if (dictIntf != null) {
                    for (candidate in carouselCandidates) {
                        try {
                            val listObj = candidate.invoke(dictIntf)
                            val sz = (listObj as? List<*>)?.size ?: -1
                            ModuleLog.line(
                                "(NA|Post|DEBUG)   candidate=" + candidate.name +
                                    " resultType=" + (listObj?.javaClass?.name ?: "null") +
                                    " size=" + sz
                            )
                            val items = listObj as? List<*> ?: continue
                            if (items.size < 2) continue
                            val intfClass = videoVersionIntfClass
                            if (intfClass != null && items.isNotEmpty() &&
                                intfClass.isInstance(items[0])
                            ) continue

                            val carouselUrls = ArrayList<String>()
                            for (idx in items.indices) {
                                val item = items[idx] ?: continue
                                val itemVideo = bestVideoUrlFromMedia(item)
                                if (itemVideo != null) {
                                    carouselUrls.add(itemVideo)
                                    continue
                                }
                                val imgMethod = methodImageUrl
                                if (imgMethod != null && ctx != null) {
                                    try {
                                        val r = imgMethod.invoke(null, ctx, item)
                                        if (r is String && isCdnMediaUrl(r)) {
                                            carouselUrls.add(r)
                                            continue
                                        }
                                    } catch (ignored: Throwable) {
                                    }
                                }
                                val probed = probeCdnUrlViaStringMethods(item)
                                if (probed != null) {
                                    carouselUrls.add(probed)
                                    continue
                                }
                                val scanned = ArrayList<String>()
                                scanForCdnUrls(
                                    item, scanned, 0,
                                    Collections.newSetFromMap(IdentityHashMap())
                                )
                                if (scanned.isNotEmpty()) {
                                    carouselUrls.add(pickBestImageUrl(scanned))
                                }
                            }
                            if (carouselUrls.size >= 2) return carouselUrls
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            }

            // A Reel/video must never fall through to its image_versions2 cover. If exact
            // model extraction failed, only accept a URL that belongs to this media object's
            // own graph and was independently identified as video.
            if (isMediaVideo(media)) {
                val mediaUrls = collectCdnUrls(media)
                for (candidate in mediaUrls) {
                    if (isVideoUrl(candidate)) {
                        rememberVideoUrl(candidate)
                        return arrayListOf(candidate)
                    }
                }
                ModuleLog.line(
                    "(NA|Post|DL) media is video but no video URL was resolved; " +
                        "refusing image cover fallback"
                )
                return ArrayList()
            }

            // Step C: single photo
            val imageUrl = imageUrlFromMedia(ctx, media)
            if (imageUrl != null) return arrayListOf(imageUrl)

            // Step D: CDN scan fallback
            val cdnUrls = collectCdnUrls(media)
            if (cdnUrls.isNotEmpty()) return arrayListOf(cdnUrls[0])

            return ArrayList()
        }

        /**
         * IG 447+ carousel extraction: reads one child Media per slide via the "carousel_media"
         * accessor and resolves each slide's own URL (video slide → its video_versions; photo
         * slide → its image). Returns null when this isn't a carousel or the accessor is
         * unavailable, so the caller falls back to the legacy single-media / dict-based paths.
         */
        private fun extractCarouselUrls(ctx: Context?, media: Any?): List<String>? {
            val getter = carouselMediaGetter ?: return null
            return try {
                val owner = if (getter.declaringClass.isInstance(media)) {
                    media
                } else {
                    MediaModelResolver.findObjectOfType(media, getter.declaringClass, 5)
                } ?: return null

                val listObj = getter.invoke(owner)
                val items = listObj as? List<*> ?: return null
                if (items.size < 2) return null

                val urls = ArrayList<String>()
                for (child in items) {
                    if (child == null) continue
                    val v = bestVideoUrlFromMedia(child)
                    if (v != null) {
                        urls.add(v)
                        continue
                    }
                    val img = imageUrlFromMedia(ctx, child)
                    if (img != null) {
                        urls.add(img)
                        continue
                    }
                    val imgMethod = methodImageUrl
                    if (imgMethod != null && ctx != null) {
                        try {
                            val r = imgMethod.invoke(null, ctx, child)
                            if (r is String && isCdnMediaUrl(r)) urls.add(r)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                urls
            } catch (t: Throwable) {
                ModuleLog.line("(NA|Post) extractCarouselUrls: $t")
                null
            }
        }

        /**
         * Shows the download dialog for a post.
         * Single URL → direct download. Multiple (carousel) → "Download current / Download all"
         * dialog. currentIndex = the visible carousel slide (from findCarouselIndex). Must be
         * called on the main thread.
         */
        @SuppressLint("DefaultLocale")
        internal fun showPostDownloadDialog(
            ctx: Context,
            urls: List<String>,
            username: String?,
            mediaId: String?,
            currentIndex: Int,
        ) {
            if (urls.isEmpty()) {
                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_toast_post_url_not_found), Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (urls.size == 1) {
                val url = urls[0]
                val isVid = isVideoUrl(url)
                val fn = buildFilename(username, "post", mediaId, isVid)
                Toast.makeText(
                    ctx,
                    if (isVid) I18n.t(ctx, R.string.ig_toast_downloading_video)
                    else I18n.t(ctx, R.string.ig_toast_downloading_photo),
                    Toast.LENGTH_SHORT
                ).show()
                executor.submit {
                    try {
                        val delegated = downloadAndSave(ctx, url, fn, isVid, username)
                        if (!delegated) {
                            mainHandler.post {
                                Toast.makeText(
                                    ctx,
                                    if (isVid) I18n.t(ctx, R.string.ig_toast_video_saved)
                                    else I18n.t(ctx, R.string.ig_toast_photo_saved),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Throwable) {
                        ModuleLog.line("(NA|Post|DL) single failed: $e")
                        mainHandler.post {
                            Toast.makeText(
                                ctx,
                                I18n.t(ctx, R.string.ig_toast_download_failed, e.message),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return
            }

            // Carousel: modern bottom sheet with two pill buttons
            val n = urls.size
            val safeIdx = if (currentIndex in 0 until n) currentIndex else 0
            showCarouselBottomSheet(ctx, urls, username, mediaId, n, safeIdx)
        }

        // ── Modern bottom sheet for carousel download ─────────────────────────────

        private fun isDarkTheme(ctx: Context): Boolean =
            (
                ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES

        private fun roundRect(color: Int, radiusDp: Float, ctx: Context): GradientDrawable {
            val r = radiusDp * ctx.resources.displayMetrics.density
            val d = GradientDrawable()
            d.setColor(color)
            d.cornerRadius = r
            return d
        }

        private fun makePillButton(
            ctx: Context,
            label: String,
            bgColor: Int,
            textColor: Int,
            dp: Float,
        ): Button {
            val btn = Button(ctx)
            btn.text = label
            btn.setTextColor(textColor)
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            btn.setTypeface(null, Typeface.BOLD)
            btn.background = roundRect(bgColor, 14f, ctx)
            btn.isAllCaps = false
            btn.setPadding(
                (20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt()
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * dp).toInt()
            btn.layoutParams = lp
            return btn
        }

        private fun showCarouselBottomSheet(
            ctx: Context,
            urls: List<String>,
            username: String?,
            mediaId: String?,
            n: Int,
            safeIdx: Int,
        ) {
            try {
                val dp = ctx.resources.displayMetrics.density
                val dk = isDarkTheme(ctx)

                val sheetBg = if (dk) Color.parseColor("#1C1C1E") else Color.parseColor("#F2F2F7")
                val textPrim = if (dk) Color.WHITE else Color.parseColor("#1C1C1E")
                val textSec = if (dk) Color.parseColor("#AEAEB2") else Color.parseColor("#6C6C70")
                val accentBg = Color.parseColor("#0A84FF")
                val secondBg = if (dk) Color.parseColor("#3A3A3C") else Color.parseColor("#E5E5EA")
                val secondText = if (dk) Color.WHITE else Color.parseColor("#1C1C1E")
                val handleClr =
                    if (dk) Color.parseColor("#48484A") else Color.parseColor("#C7C7CC")

                val sheet = LinearLayout(ctx)
                sheet.orientation = LinearLayout.VERTICAL
                sheet.background = roundRect(sheetBg, 20f, ctx)
                val hPad = (20 * dp).toInt()
                sheet.setPadding(hPad, (12 * dp).toInt(), hPad, (28 * dp).toInt())

                // Drag handle
                val handle = View(ctx)
                val handleLp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
                handleLp.gravity = Gravity.CENTER_HORIZONTAL
                handleLp.bottomMargin = (16 * dp).toInt()
                handle.layoutParams = handleLp
                handle.background = roundRect(handleClr, 2f, ctx)
                sheet.addView(handle)

                // Title
                val title = TextView(ctx)
                title.text = I18n.t(ctx, R.string.ig_dl_title)
                title.setTextColor(textPrim)
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                title.setTypeface(null, Typeface.BOLD)
                val titleLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                titleLp.bottomMargin = (4 * dp).toInt()
                title.layoutParams = titleLp
                sheet.addView(title)

                // Subtitle
                val subtitle = TextView(ctx)
                subtitle.text = I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n)
                subtitle.setTextColor(textSec)
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val subLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                subLp.bottomMargin = (14 * dp).toInt()
                subtitle.layoutParams = subLp
                sheet.addView(subtitle)

                val dialog = Dialog(ctx)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                // Button: Download current
                val currentLabel =
                    I18n.t(ctx, R.string.ig_dl_carousel_current, safeIdx + 1, n)
                val btnCurrent = makePillButton(ctx, currentLabel, accentBg, Color.WHITE, dp)
                btnCurrent.setOnClickListener {
                    dialog.dismiss()
                    val url = urls[safeIdx]
                    val isVid = isVideoUrl(url)
                    val fn = buildFilename(username, "post", mediaId, isVid)
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_downloading), Toast.LENGTH_SHORT
                    ).show()
                    executor.submit {
                        try {
                            val delegated = downloadAndSave(ctx, url, fn, isVid, username)
                            if (!delegated) {
                                mainHandler.post {
                                    Toast.makeText(
                                        ctx, I18n.t(ctx, R.string.ig_toast_saved),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Throwable) {
                            mainHandler.post {
                                Toast.makeText(
                                    ctx,
                                    I18n.t(ctx, R.string.ig_toast_download_failed, e.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                sheet.addView(btnCurrent)

                // Button: Download all
                val btnAll = makePillButton(
                    ctx, I18n.t(ctx, R.string.ig_dl_carousel_all, n), secondBg, secondText, dp
                )
                btnAll.setOnClickListener {
                    dialog.dismiss()
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_toast_downloading_all_n_items, n),
                        Toast.LENGTH_SHORT
                    ).show()
                    executor.submit {
                        var failed = 0
                        for (url in urls) {
                            val isVid = isVideoUrl(url)
                            val fn = buildFilename(username, "post", mediaId, isVid)
                            try {
                                downloadAndSave(ctx, url, fn, isVid, username)
                            } catch (e: Throwable) {
                                failed++
                                ModuleLog.line("(NA|Post|DL) item failed: $e")
                            }
                        }
                        val finalFailed = failed
                        mainHandler.post {
                            if (finalFailed == 0) {
                                Toast.makeText(
                                    ctx, I18n.t(ctx, R.string.ig_toast_all_items_saved, n),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    ctx,
                                    I18n.t(
                                        ctx, R.string.ig_toast_items_partial_saved,
                                        n - finalFailed, n, finalFailed
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                sheet.addView(btnAll)

                dialog.setContentView(sheet)
                val w = dialog.window
                if (w != null) {
                    w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    w.setGravity(Gravity.BOTTOM)
                    w.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                    val wlp = w.attributes
                    val margin = (12 * dp).toInt()
                    wlp.x = margin
                    wlp.y = margin
                    w.attributes = wlp
                }
                dialog.show()
            } catch (t: Throwable) {
                ModuleLog.line("(NA|Post) ❌ showCarouselBottomSheet: $t")
            }
        }

        // ── Copy Media Link (#117) ────────────────────────────────────────────────
        //
        // Single URL (reel / single post) → copy straight to clipboard. Carousel → a chooser
        // sheet with one pill per slide plus "copy all", so the user picks the exact slide
        // (the visible-slide index can't be resolved reliably when several feed carousels are
        // on screen at once, so we don't guess — we let the user choose).

        internal fun copyLinkToClipboard(ctx: Context, url: String) {
            // Defer the actual setPrimaryClip: writing the clipboard synchronously re-enters
            // IG's own OnPrimaryClipChangedListener on the main thread. On a carousel that
            // collides with IG's realtime request-stream executor being torn down (the visible
            // slide's prefetch scope), and IG's native TigonRepeatingForwardingRequestToken then
            // schedules on the dead executor → RejectedExecutionException (a fatal in IG's own
            // code, not ours). Posting the write a beat later (past the ~200ms clip-listener
            // debounce + the sheet-dismiss frame) moves it out of that teardown window.
            // Framework-only; clipboard contents unchanged.
            mainHandler.postDelayed({
                try {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("NexAlloy", url))
                    Toast.makeText(
                        ctx, I18n.t(ctx, R.string.ig_copy_link_copied), Toast.LENGTH_SHORT
                    ).show()
                } catch (t: Throwable) {
                    ModuleLog.line("(NA|Post) ❌ copyLinkToClipboard: $t")
                }
            }, 350)
        }

        @SuppressLint("DefaultLocale")
        internal fun showCopyLinkSheet(ctx: Context, urls: List<String>?) {
            if (urls == null || urls.isEmpty()) {
                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (urls.size == 1) {
                copyLinkToClipboard(ctx, urls[0])
                return
            }

            try {
                val dp = ctx.resources.displayMetrics.density
                val dk = isDarkTheme(ctx)

                val sheetBg = if (dk) Color.parseColor("#1C1C1E") else Color.parseColor("#F2F2F7")
                val textPrim = if (dk) Color.WHITE else Color.parseColor("#1C1C1E")
                val textSec = if (dk) Color.parseColor("#AEAEB2") else Color.parseColor("#6C6C70")
                val accentBg = Color.parseColor("#0A84FF")
                val secondBg = if (dk) Color.parseColor("#3A3A3C") else Color.parseColor("#E5E5EA")
                val secondText = if (dk) Color.WHITE else Color.parseColor("#1C1C1E")
                val handleClr =
                    if (dk) Color.parseColor("#48484A") else Color.parseColor("#C7C7CC")

                val n = urls.size

                val sheet = LinearLayout(ctx)
                sheet.orientation = LinearLayout.VERTICAL
                sheet.background = roundRect(sheetBg, 20f, ctx)
                val hPad = (20 * dp).toInt()
                sheet.setPadding(hPad, (12 * dp).toInt(), hPad, (28 * dp).toInt())

                val handle = View(ctx)
                val handleLp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
                handleLp.gravity = Gravity.CENTER_HORIZONTAL
                handleLp.bottomMargin = (16 * dp).toInt()
                handle.layoutParams = handleLp
                handle.background = roundRect(handleClr, 2f, ctx)
                sheet.addView(handle)

                val title = TextView(ctx)
                title.text = I18n.t(ctx, R.string.ig_copy_link_title)
                title.setTextColor(textPrim)
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                title.setTypeface(null, Typeface.BOLD)
                sheet.addView(title)

                val subtitle = TextView(ctx)
                subtitle.text = I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n)
                subtitle.setTextColor(textSec)
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val subLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                subLp.bottomMargin = (10 * dp).toInt()
                subtitle.layoutParams = subLp
                sheet.addView(subtitle)

                val dialog = Dialog(ctx)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                // One pill per slide (scrollable, capped height for long carousels)
                val pillList = LinearLayout(ctx)
                pillList.orientation = LinearLayout.VERTICAL
                for (i in 0 until n) {
                    val idx = i
                    val b = makePillButton(
                        ctx, I18n.t(ctx, R.string.ig_copy_link_slide, i + 1, n),
                        secondBg, secondText, dp
                    )
                    b.setOnClickListener {
                        dialog.dismiss()
                        copyLinkToClipboard(ctx, urls[idx])
                    }
                    pillList.addView(b)
                }
                val scroller = ScrollView(ctx)
                scroller.addView(pillList)
                val svLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                svLp.weight = 1f
                scroller.layoutParams = svLp
                // Cap so many slides don't push the "copy all" pill off-screen
                scroller.viewTreeObserver.addOnGlobalLayoutListener {
                    val cap = (300 * dp).toInt()
                    if (scroller.height > cap && scroller.layoutParams.height != cap) {
                        scroller.layoutParams.height = cap
                        scroller.requestLayout()
                    }
                }
                sheet.addView(scroller)

                val btnAll = makePillButton(
                    ctx, I18n.t(ctx, R.string.ig_copy_link_all, n), accentBg, Color.WHITE, dp
                )
                btnAll.setOnClickListener {
                    dialog.dismiss()
                    val sb = StringBuilder()
                    for (u in urls) sb.append(u).append('\n')
                    val allText = sb.toString().trim()
                    // Deferred like copyLinkToClipboard — keep the clipboard write out of the
                    // carousel realtime-stream teardown window (see that method's note).
                    mainHandler.postDelayed({
                        try {
                            val cb =
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("NexAlloy", allText))
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_copy_link_copied_all, n),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (t: Throwable) {
                            ModuleLog.line("(NA|Post) ❌ copy all links: $t")
                        }
                    }, 350)
                }
                sheet.addView(btnAll)

                dialog.setContentView(sheet)
                val w = dialog.window
                if (w != null) {
                    w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    w.setGravity(Gravity.BOTTOM)
                    w.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                    val wlp = w.attributes
                    val margin = (12 * dp).toInt()
                    wlp.x = margin
                    wlp.y = margin
                    w.attributes = wlp
                }
                dialog.show()
            } catch (t: Throwable) {
                ModuleLog.line("(NA|Post) ❌ showCopyLinkSheet: $t")
            }
        }

        /**
         * Extracts the username from a com.instagram.feed.media.Media object using the
         * DexKit-resolved dictUserGetter. Used by StoryDownloadHook.
         */
        internal fun extractUsernameFromMediaObject(media: Any?): String? {
            if (media == null) return null
            // IG 446+/447.0.0.39+: author getter lives directly on Media
            // (see resolveMediaAuthorGetter).
            val authorGetter = mediaAuthorGetter
            if (authorGetter != null && mediaClass?.isInstance(media) == true) {
                try {
                    val user = authorGetter.invoke(media)
                    val name = UserUtils.callUsernameGetter(user)
                    if (name != null) return name
                } catch (ignored: Throwable) {
                }
            }
            // Older builds: author getter on the MutableMediaDictIntf/LiveTreeMediaDict object.
            val dictGetter = dictUserGetter
            if (dictGetter != null &&
                (mutableMediaDictIntfClass != null || liveTreeMediaDictClass != null)
            ) {
                try {
                    val dictIntf = findMediaDictionary(media)
                    if (dictIntf != null) {
                        val user = dictGetter.invoke(dictIntf)
                        val name = UserUtils.callUsernameGetter(user)
                        if (name != null) return name
                    }
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        @Deprecated(
            "Use UserUtils.callUsernameGetter directly.",
            ReplaceWith("UserUtils.callUsernameGetter(user)")
        )
        fun callUsernameGetter(user: Any?): String? = UserUtils.callUsernameGetter(user)

        /**
         * Walks the object graph up to depth 3 looking for any object that has a
         * no-arg getUsername() method returning a valid Instagram username string.
         * At depth 0 (the Media object itself), logs all field names + types to
         * help diagnose where the user object is nested.
         */
        private fun scanObjectForUsername(
            obj: Any?,
            depth: Int,
            visited: MutableSet<Any>,
        ): String? {
            if (obj == null || depth > 3 || visited.contains(obj)) return null
            visited.add(obj)

            // Try getUsername() on this object directly
            try {
                val result = obj.javaClass.getMethod("getUsername").invoke(obj)
                if (result is String && result.isNotEmpty() &&
                    result.matches(Regex("[a-zA-Z0-9._]{1,30}"))
                ) {
                    return result
                }
            } catch (ignored: Throwable) {
            }

            if (depth >= 3) return null

            // Scan all non-primitive, non-String, non-array fields — no class filter,
            // rely on depth limit + visited set to prevent runaway recursion
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    val ft = f.type
                    if (ft.isPrimitive || ft == String::class.java || ft.isArray) continue
                    f.isAccessible = true
                    try {
                        val value = f.get(obj) ?: continue
                        val u = scanObjectForUsername(value, depth + 1, visited)
                        if (u != null) return u
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        private fun findCarouselPosition(anchor: View): Int {
            var container: View = anchor
            var i = 0
            while (i < 8) {
                val p = container.parent
                if (p !is View) break
                container = p
                i++
            }
            val vg = container as? ViewGroup ?: return 0
            val pos = searchForPager(vg, 0)
            return if (pos >= 0) pos else 0
        }

        private fun searchForPager(group: ViewGroup, depth: Int): Int {
            if (depth > 8) return -1
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                for (methodName in arrayOf("getCurrentItem", "getCurrentDataIndex")) {
                    try {
                        val m = child.javaClass.getMethod(methodName)
                        val r = m.invoke(child)
                        if (r is Int && r >= 0) return r
                    } catch (ignored: Throwable) {
                    }
                }
                if (child is ViewGroup) {
                    val r = searchForPager(child, depth + 1)
                    if (r >= 0) return r
                }
            }
            return -1
        }

        @Throws(Exception::class)
        private fun downloadToFile(url: String, dest: File) {
            downloadToFileAndGetType(url, dest)
        }

        @Throws(Exception::class)
        private fun downloadToFileAndGetType(url: String?, dest: File): String? {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty(
                "User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
            )
            conn.connect()
            val contentType = conn.contentType
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { fos ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            return contentType
        }

        @Suppress("unused")
        @Throws(Exception::class)
        internal fun downloadToStream(url: String, out: OutputStream) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty(
                "User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
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

        @Throws(Exception::class)
        private fun mergeVideoAudio(vp: String, ap: String, op: String) {
            val vEx = MediaExtractor()
            val aEx = MediaExtractor()
            val mux = MediaMuxer(op, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                vEx.setDataSource(vp)
                aEx.setDataSource(ap)
                val vi = selectTrack(vEx, "video/")
                val ai = selectTrack(aEx, "audio/")
                if (vi < 0 || ai < 0) throw Exception("Missing tracks")
                val vo = mux.addTrack(vEx.getTrackFormat(vi))
                val ao = mux.addTrack(aEx.getTrackFormat(ai))
                mux.start()
                val buf = ByteBuffer.allocate(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                copyTrack(vEx, mux, vo, buf, info)
                copyTrack(aEx, mux, ao, buf, info)
                mux.stop()
            } finally {
                vEx.release()
                aEx.release()
                mux.release()
            }
        }

        private fun selectTrack(ex: MediaExtractor, mime: String): Int {
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith(mime)) {
                    ex.selectTrack(i)
                    return i
                }
            }
            return -1
        }

        @SuppressLint("WrongConstant")
        private fun copyTrack(
            ex: MediaExtractor,
            mux: MediaMuxer,
            out: Int,
            buf: ByteBuffer,
            info: MediaCodec.BufferInfo,
        ) {
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val sz = ex.readSampleData(buf, 0)
                if (sz < 0) break
                info.offset = 0
                info.size = sz
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                mux.writeSampleData(out, buf, info)
                ex.advance()
            }
        }

        private fun probeUrl(url: String): TrackInfo {
            val ex = MediaExtractor()
            var hv = false
            var ha = false
            try {
                ex.setDataSource(url)
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) hv = true
                    if (m.startsWith("audio/")) ha = true
                }
            } catch (ignored: Throwable) {
            } finally {
                ex.release()
            }
            return TrackInfo(hv, ha)
        }

        private class TrackInfo(
            @JvmField val hasVideo: Boolean,
            @JvmField val hasAudio: Boolean,
        )

        /**
         * Returns true if this CDN URL points to an Instagram feed media item
         * (photo or video) — not a profile picture, UI asset, or other non-media content.
         *
         * Key CDN path segments:
         *   t51.2885-15  = feed photo (INCLUDE)
         *   t51.2885-19  = profile picture (EXCLUDE)
         *   t50.2886-16  = feed video (INCLUDE)
         *   t51.39750    = exclude (story thumbnails / non-feed content)
         */
        internal fun isCdnMediaUrl(url: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false
            // Exclude profile pictures: the t51 CDN path always uses suffix -19 for avatars
            // regardless of the bucket number (t51.2885-19, t51.82787-19, etc.)
            // Pattern: /t51.<digits>-19/
            if (url.contains("/t51.") && url.contains("-19/")) return false
            // Exclude other known non-feed content
            if (url.contains("t51.39750")) return false
            return true
        }

        /**
         * Returns true if this CDN URL is a video (not a still image or audio-only track).
         *
         * Instagram CDN naming convention:
         *   t50.xxxx = all video CDN path segments (t50.2886-16, t50.29441-2, t50.16800-16, …)
         *   t51.xxxx = image content
         *   /o1/     = Reels/Clips video (path may omit t50 segment)
         *
         * Known audio-only (exclude):
         *   /o1/v/t2/ = background music track for Reels
         */
        internal fun isVideoUrl(url: String?): Boolean {
            if (url == null) return false
            // Source-aware classification: a URL returned by VideoVersionIntf or by the
            // Pando video_versions getter is a video even when the CDN path is opaque.
            if (wasCapturedAsVideo(url)) return true
            val lower = url.lowercase(Locale.US)
            // All Instagram video CDN path segments begin with t50.
            // Covers all variants: t50.2886-16, t50.29441-2, t50.16800-16, etc.
            if (lower.contains("t50.")) return true
            // Reels/Clips CDN paths use /o1/ regardless of whether they carry a t50 segment.
            // Note: /o1/v/t2/ is NOT audio-only — it is the standard Reels progressive MP4 path.
            if (lower.contains("/o1/") || lower.contains("%2fo1%2f")) return true
            // Newer CDN variants may omit t50/o1 while retaining the explicit container or MIME.
            return lower.contains(".mp4") ||
                lower.contains("mime_type=video") ||
                lower.contains("mime%2ftype=video")
        }

        private fun hasAncestorWithId(view: View, targetId: Int): Boolean {
            if (targetId == 0) return false
            var p: ViewParent? = view.parent
            var i = 0
            while (i < 6 && p is View) {
                if (p.id == targetId) return true
                i++
                p = p.parent
            }
            return false
        }

        private fun dp(ctx: Context, v: Int): Int =
            (v * ctx.resources.displayMetrics.density).toInt()
    }
}
