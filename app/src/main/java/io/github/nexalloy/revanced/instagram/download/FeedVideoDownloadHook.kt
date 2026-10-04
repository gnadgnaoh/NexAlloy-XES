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

    @Volatile
    private var currentDownloadUsername: String? = null

    @Volatile
    private var currentDownloadMediaId: String? = null

    fun install(classLoader: ClassLoader) {
        try {
            val media = classLoader.loadClass("com.instagram.feed.media.Media")
            mediaClass = media
            val extKt = classLoader.loadClass("com.instagram.feed.media.MediaExtKt")
            mediaExtKtClass = extKt
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
        
        try {
            val intf = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf")
            val getUrl = intf.getMethod("getUrl")
            videoVersionIntfClass = intf
            videoVersionGetUrl = { obj -> getUrl.invoke(obj) }
        } catch (ignored: Throwable) {
        }

    }

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
                            val existing = parent.findViewWithTag<View>(DOWNLOAD_BTN_TAG)
                            if (existing != null) {
                                synchronized(buttonUrls) { buttonUrls.put(existing, snapshot) }
                                return
                            }
                            injectDownloadButton(view, parent, ctx, snapshot)
                        } else {
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

    @SuppressLint("DiscouragedApi")
    @Suppress("UNUSED_PARAMETER")
    private fun resolveUrls(likeBtn: View, downloadBtn: View?): List<String> {
        var urls = urlsFromSaveBtnListener(likeBtn)
        ModuleLog.line("(NA|DL) Tier-1a urls=" + urls.size)
        if (urls.isNotEmpty()) return urls
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
                    break
                }
                i++
                p = vg.parent
            }
        }

        return ArrayList()
    }

    @SuppressLint("DiscouragedApi")
    private fun getUsernameFromView(likeBtn: View?): String? {
        if (likeBtn == null || mediaClass == null) return null

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

        val resolvedMedia = media ?: return null

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

        val userObj = findFieldOfType(resolvedMedia, userClass, 3)
        if (userObj != null) {
            val name = UserUtils.callUsernameGetter(userObj)
            if (name != null) return name
        }

        return scanObjectForUsername(
            resolvedMedia, 0, Collections.newSetFromMap(IdentityHashMap())
        )
    }

    private fun getMediaFromListener(listener: Any?): Any? {
        if (listener == null || mediaClass == null) return null
        return findFieldOfType(listener, mediaClass, 4)
    }

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
                    startDirectDownload(ctx, images[0], false)
                } else {
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

        @JvmField
        val TAG_REEL_MEDIA = "ie_reel_media".hashCode()

        private var mediaExtKtClass: Class<*>? = null
        private var mediaClass: Class<*>? = null

        @JvmStatic
        var mutableMediaDictIntfClass: Class<*>? = null
            private set

        private var liveTreeMediaDictClass: Class<*>? = null
        private var mediaModel: MediaModelResolver.Result? = null
        private val resolvedVideoVersionsGetters = ArrayList<Method>()
        private var resolvedIsVideoMethod: Method? = null

        private var methodImageUrl: Method? = null

        private var carouselMediaGetter: Method? = null

        internal var videoVersionIntfClass: Class<*>? = null

        internal var videoVersionGetUrl: ((Any?) -> Any?)? = null

        fun bindVideoUrlModel(modelClass: Class<*>, urlField: java.lang.reflect.Field) {
            if (videoVersionIntfClass != null && videoVersionGetUrl != null) return
            urlField.isAccessible = true
            videoVersionIntfClass = modelClass
            videoVersionGetUrl = { obj -> urlField.get(obj) }
        }

        fun onVideoUrlConstructed(url: Any?) {
            if (!FeatureFlags.enablePostDownload) return
            val value = url as? String ?: return
            if (!isCdnMediaUrl(value)) return
            rememberVideoUrl(value)
        }

        internal val carouselCandidates = ArrayList<Method>()

        private var userClass: Class<*>? = null

        private var dictUserGetter: Method? = null

        private var mediaAuthorGetter: Method? = null

        private class UrlEntry(u: String) {
            @JvmField val url: String = u

            @JvmField val time: Long = System.currentTimeMillis()
        }

        private const val MAX_URLS = 200
        private val urlBuffer: Deque<UrlEntry> = ArrayDeque()

        private val videoUrlBuffer: Deque<UrlEntry> = ArrayDeque()

        private val buttonUrls = WeakHashMap<View, List<String>>()

        @JvmField
        internal val executor: ExecutorService = Executors.newCachedThreadPool()

        @JvmField
        internal val mainHandler = Handler(Looper.getMainLooper())

        private fun urlsFromSaveBtnListener(saveBtn: View): List<String> {
            try {
                val listener = getOnClickListener(saveBtn) ?: return ArrayList()

                val urls = ArrayList<String>()
                val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
                scanForCdnUrls(listener, urls, 0, visited)

                if (mediaClass != null) {
                    val media = findFieldOfType(listener, mediaClass, 4)

                    if (media != null) {
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

                                            val itemVideo = findVideoUrlInObject(
                                                item,
                                                Collections.newSetFromMap(IdentityHashMap()), 0
                                            )
                                            if (itemVideo != null) {
                                                carouselUrls.add(itemVideo)
                                                continue
                                            }

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

                                            val probed = probeCdnUrlViaStringMethods(item)
                                            ModuleLog.line(
                                                "(NA|Car) item[" + idx + "] probed=" + probed
                                            )
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

                                        ModuleLog.line(
                                            "(NA|Car) carouselUrls=" + carouselUrls.size
                                        )
                                        if (carouselUrls.size >= 2) return carouselUrls
                                    } catch (ignored: Throwable) {
                                    }
                                }
                            }
                        }

                        val imgMethod = methodImageUrl
                        if (imgMethod != null) {
                            try {
                                val img = imgMethod.invoke(null, saveBtn.context, media)
                                if (img is String && isCdnMediaUrl(img)) return listOf(img)
                            } catch (ignored: Throwable) {
                            }
                        }
                    }

                    val images = ArrayList<String>()
                    for (u in urls) if (!isVideoUrl(u)) images.add(u)
                    if (images.isNotEmpty()) return listOf(pickBestImageUrl(images))
                }

                return urls
            } catch (t: Throwable) {
                return ArrayList()
            }
        }

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

        internal fun findVideoUrlInObject(
            obj: Any?,
            visited: MutableSet<Any>,
            depth: Int,
        ): String? {
            if (obj == null || depth > 5 || !visited.add(obj)) return null
            val intfClass = videoVersionIntfClass ?: return null
            val getUrl = videoVersionGetUrl ?: return null

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

        internal fun collectAllVideoUrls(
            obj: Any?,
            out: MutableList<String>,
            visited: MutableSet<Any>,
            depth: Int,
        ) {
            if (obj == null || depth > 7 || !visited.add(obj)) return

            if (looksLikeVideoVersion(obj)) {
                addVideoVersionUrl(obj, out)
                return
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

        internal fun bestVideoUrlFromMedia(media: Any?): String? {
            val all = ArrayList<String>()
            collectVideoUrlsFromDictionary(media, all)
            if (all.isEmpty()) {
                val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
                collectAllVideoUrls(media, all, visited, 0)
            }
            if (all.isEmpty()) return null
            for (u in all) if (u.contains("/m86/") || u.contains("%2Fm86%2F")) return u
            return all[0]
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

        private fun collectVideoUrlsFromDictionary(media: Any?, out: MutableList<String>) {
            for (getter in resolvedVideoVersionsGetters) {
                val owner = if (getter.declaringClass.isInstance(media)) {
                    media
                } else {
                    MediaModelResolver.findObjectOfType(media, getter.declaringClass, 7)
                }
                if (owner != null) collectUrlsFromVideoVersionsMethod(owner, getter, out, true)
            }

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
            val fieldUrl = videoUrlFromStringFields(item)
            if (fieldUrl != null) {
                rememberVideoUrl(fieldUrl)
                return fieldUrl
            }
            return null
        }

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

        private fun tryGetUrl(obj: Any?): String? {
            if (obj == null) return null
            return try {
                val m = obj.javaClass.getMethod("getUrl")
                m.invoke(obj) as? String
            } catch (ignored: Throwable) {
                null
            }
        }

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

        fun bindIsVideoMethod(method: Method?) {
            if (method == null) return
            method.isAccessible = true
            resolvedIsVideoMethod = method
        }

        fun bindUserClass(resolved: Class<*>?) {
            userClass = resolved
            if (resolved != null) ModuleLog.line("(NA|DL) userClass=" + resolved.name)
        }

        fun bindUsernameGetter(method: Method?) {
            if (method == null) return
            method.isAccessible = true
            UserUtils.userUsernameGetter = method
            ModuleLog.line("(NA|DL) usernameGetter=" + method.name)
        }

        fun bindMediaAuthorGetter(method: Method?) {
            if (method == null || userClass == null) return
            method.isAccessible = true
            mediaAuthorGetter = method
            ModuleLog.line("(NA|DL) mediaAuthorGetter=" + method.name)
        }

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
        
        fun onVideoUrlReturned(param: XC_MethodHook.MethodHookParam) {
            if (!FeatureFlags.enablePostDownload) return
            val url = param.result as? String ?: return
            if (!isCdnMediaUrl(url)) return
            rememberVideoUrl(url)
        }

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

        @Throws(Exception::class)
        internal fun openOutputStream(
            ctx: Context,
            filename: String,
            isVideo: Boolean,
            username: String?,
        ): OutputStream {
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

            if (FeatureFlags.downloaderCustomPath.isNotEmpty()) {
                try {
                    return openRawPathOutputStream(filename, username)
                } catch (e: Exception) {
                    ModuleLog.line(
                        "(NA|DL) Raw path failed, falling back to MediaStore: " + e.message
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return openMediaStoreOutputStream(ctx, filename, mimeType, username)
            }

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

        private val MS_ROOTS = hashSetOf(
            "Download", "Downloads", "Pictures", "DCIM", "Movies", "Music",
            "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks"
        )

        private fun buildMediaStoreRelPath(username: String?): String {
            val customPath = FeatureFlags.downloaderCustomPath
            var base = "Download/NexAlloy"

            if (customPath.isNotEmpty() && !customPath.startsWith("content://")) {
                @Suppress("DEPRECATION")
                val extBase = Environment.getExternalStorageDirectory().absolutePath
                if (customPath.startsWith("$extBase/")) {
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

        @Throws(Exception::class)
        internal fun downloadAndSave(
            ctx: Context,
            url: String?,
            filename: String,
            isVideo: Boolean,
            username: String?,
        ): Boolean {
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

        internal fun collectCdnUrls(obj: Any?): List<String> {
            val out = ArrayList<String>()
            scanForCdnUrls(obj, out, 0, Collections.newSetFromMap(IdentityHashMap()))
            return out
        }

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

        internal fun extractAllUrlsFromMedia(ctx: Context?, media: Any?): List<String> {
            if (media == null) return ArrayList()

            val carousel = extractCarouselUrls(ctx, media)
            if (carousel != null && carousel.size >= 2) return carousel

            val videoUrl = bestVideoUrlFromMedia(media)
            if (videoUrl != null) return arrayListOf(videoUrl)

            ModuleLog.line(
                "(NA|Post|DEBUG) carousel check: mutableMediaDictIntfClass=" +
                    (mutableMediaDictIntfClass?.name ?: "null") +
                    " carouselCandidates=" + carouselCandidates.size
            )

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

            val imageUrl = imageUrlFromMedia(ctx, media)
            if (imageUrl != null) return arrayListOf(imageUrl)

            val cdnUrls = collectCdnUrls(media)
            if (cdnUrls.isNotEmpty()) return arrayListOf(cdnUrls[0])

            return ArrayList()
        }

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

            val n = urls.size
            val safeIdx = if (currentIndex in 0 until n) currentIndex else 0
            showCarouselBottomSheet(ctx, urls, username, mediaId, n, safeIdx)
        }

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

                val handle = View(ctx)
                val handleLp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
                handleLp.gravity = Gravity.CENTER_HORIZONTAL
                handleLp.bottomMargin = (16 * dp).toInt()
                handle.layoutParams = handleLp
                handle.background = roundRect(handleClr, 2f, ctx)
                sheet.addView(handle)

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

        internal fun copyLinkToClipboard(ctx: Context, url: String) {
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

        internal fun extractUsernameFromMediaObject(media: Any?): String? {
            if (media == null) return null
            val authorGetter = mediaAuthorGetter
            if (authorGetter != null && mediaClass?.isInstance(media) == true) {
                try {
                    val user = authorGetter.invoke(media)
                    val name = UserUtils.callUsernameGetter(user)
                    if (name != null) return name
                } catch (ignored: Throwable) {
                }
            }
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

        private fun scanObjectForUsername(
            obj: Any?,
            depth: Int,
            visited: MutableSet<Any>,
        ): String? {
            if (obj == null || depth > 3 || visited.contains(obj)) return null
            visited.add(obj)

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

        internal fun isCdnMediaUrl(url: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false
            if (url.contains("/t51.") && url.contains("-19/")) return false
            if (url.contains("t51.39750")) return false
            return true
        }

        internal fun isVideoUrl(url: String?): Boolean {
            if (url == null) return false
            if (wasCapturedAsVideo(url)) return true
            val lower = url.lowercase(Locale.US)
            if (lower.contains("t50.")) return true
            if (lower.contains("/o1/") || lower.contains("%2fo1%2f")) return true
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
