package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.io.OutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap

object StoryDownloadHook {

    private var legacyVideoVersionClass: Class<*>? = null
    private var legacyVideoVersionGetUrl: ((Any?) -> Any?)? = null

    private val videoVersionIntfClass: Class<*>?
        get() = legacyVideoVersionClass ?: FeedVideoDownloadHook.videoVersionIntfClass
    private val videoVersionGetUrl: ((Any?) -> Any?)?
        get() = legacyVideoVersionGetUrl ?: FeedVideoDownloadHook.videoVersionGetUrl

    private val mainHandler = Handler(Looper.getMainLooper())

    private class StoryMedia(
        @JvmField val url: String,
        @JvmField val video: Boolean,
    )

    private class StoryMediaOptions(
        @JvmField val imageUrl: String?,
        @JvmField val videoUrl: String?,
        @JvmField val modelSaysVideo: Boolean,
    )

    fun init(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf")
            val getUrl = cls.getMethod("getUrl")
            legacyVideoVersionClass = cls
            legacyVideoVersionGetUrl = { obj -> getUrl.invoke(obj) }
        } catch (ignored: Throwable) {
        }
    }

    fun onStoryOptionsBuilt(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enableStoryDownload) return
        val original = param.result as? Array<*> ?: return
        if (original.isNotEmpty() && original.any { it != null && it !is CharSequence }) return

        val dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title)
        for (cs in original) {
            if (cs is CharSequence && dlLabel.contentEquals(cs)) return
        }

        val extended = arrayOfNulls<CharSequence>(original.size + 1)
        for (i in original.indices) extended[i] = original[i] as CharSequence?
        extended[original.size] = dlLabel
        param.result = extended
    }

    fun buildsOptionLabels(builder: Method): Boolean {
        val returnType = builder.returnType
        val component = returnType.componentType
        return returnType.isArray && component != null &&
            CharSequence::class.java.isAssignableFrom(component)
    }

    fun onStoryOptionClick(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enableStoryDownload) return

        var tapped: CharSequence? = null
        for (arg in param.args) {
            if (arg is CharSequence) {
                tapped = arg
                break
            }
        }
        val dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title)
        if (tapped == null || !dlLabel.contentEquals(tapped)) return

        param.result = null

        val holder = findReelItemHolder(param)
        val effectiveHolder = holder ?: param.thisObject

        val ctx = findContextAcrossParam(param, effectiveHolder)
        if (ctx == null) {
            ModuleLog.line("(NA|Story) Context not found")
            return
        }

        val media = extractStoryMediaOptions(ctx, effectiveHolder)
        if (media == null) {
            Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found), Toast.LENGTH_SHORT
            ).show()
            return
        }

        val username = extractUsernameFromReelItemHolder(effectiveHolder)
        val mediaId = extractMediaIdFromReelItemHolder(effectiveHolder)
        handleStoryMedia(ctx, media, username, mediaId)
    }

    private fun findContextAcrossParam(
        param: XC_MethodHook.MethodHookParam,
        preferred: Any?,
    ): Context? {
        var c = findContext(preferred)
        if (c != null) return c
        if (param.thisObject !== preferred) {
            c = findContext(param.thisObject)
            if (c != null) return c
        }
        for (arg in param.args) {
            if (arg is Context) return arg
            c = findContext(arg)
            if (c != null) return c
        }
        return null
    }
    
    private fun findReelItemHolder(param: XC_MethodHook.MethodHookParam): Any? {
        if (hasReelItemField(param.thisObject)) return param.thisObject
        for (arg in param.args) {
            if (arg != null && hasReelItemField(arg)) return arg
        }
        return null
    }

    private fun hasReelItemField(obj: Any?): Boolean {
        if (obj == null) return false
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type.name == "com.instagram.model.reels.ReelItem") return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun extractStoryMediaOptions(ctx: Context, holder: Any?): StoryMediaOptions? {
        if (holder == null) return null
        try {
            val reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem")
            ModuleLog.line(
                "(NA|Story) reelItem=" + (reelItem?.javaClass?.name ?: "null")
            )

            val target = reelItem ?: holder
            val mediaObject = findMediaObject(target)
            val modelTarget = mediaObject ?: target
            val modelSaysVideo = FeedVideoDownloadHook.isMediaVideo(modelTarget) ||
                (modelTarget !== target && FeedVideoDownloadHook.isMediaVideo(target))

            var videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(modelTarget)
            if (videoUrl == null && modelTarget !== target) {
                videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(target)
            }

            if (videoUrl == null && videoVersionIntfClass != null && videoVersionGetUrl != null) {
                videoUrl = findVideoUrl(
                    target, Collections.newSetFromMap(IdentityHashMap()), 0
                )
                if (videoUrl != null) {
                    FeedVideoDownloadHook.rememberVideoUrl(videoUrl)
                }
            }

            var imageUrl = if (mediaObject != null) {
                FeedVideoDownloadHook.imageUrlFromMedia(ctx, mediaObject)
            } else {
                null
            }
            if (imageUrl != null && FeedVideoDownloadHook.isVideoUrl(imageUrl)) {
                imageUrl = null
            }

            if (imageUrl == null) {
                val candidates = ArrayList<CandidateInfo>()
                collectImageCandidates(
                    target, candidates, Collections.newSetFromMap(IdentityHashMap()), 0
                )
                ModuleLog.line("(NA|Story) imageCandidates=" + candidates.size)
                if (candidates.isNotEmpty()) {
                    candidates.sortWith { a, b -> b.area.compareTo(a.area) }
                    imageUrl = candidates[0].url
                    ModuleLog.line("(NA|Story) bestCandidate area=" + candidates[0].area)
                }
            }

            val cdnUrls = ArrayList<String>()
            scanCdnUrls(target, cdnUrls, 0, Collections.newSetFromMap(IdentityHashMap()))
            val imageUrls = ArrayList<String>()
            for (candidate in cdnUrls) {
                if (FeedVideoDownloadHook.isVideoUrl(candidate)) {
                    if (videoUrl == null) {
                        videoUrl = candidate
                        FeedVideoDownloadHook.rememberVideoUrl(candidate)
                    }
                } else {
                    imageUrls.add(candidate)
                }
            }
            if (imageUrl == null && imageUrls.isNotEmpty()) imageUrl = pickBestUrl(imageUrls)

            if (imageUrl == null && videoUrl == null) return null
            return StoryMediaOptions(imageUrl, videoUrl, modelSaysVideo)
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Story) extractStoryMediaOptions error: $t")
        }
        return null
    }

    private fun findMediaObject(obj: Any?): Any? {
        if (obj == null) return null
        if (obj.javaClass.name == "com.instagram.feed.media.Media") return obj

        val direct = readFieldByTypeName(obj, "com.instagram.feed.media.Media")
        if (direct != null) return direct

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (method in cls.declaredMethods) {
                if (method.parameterCount != 0 || Modifier.isStatic(method.modifiers)) continue
                val returnType = method.returnType
                if (returnType.isPrimitive || returnType == String::class.java ||
                    returnType == Void.TYPE
                ) continue
                try {
                    method.isAccessible = true
                    val result = method.invoke(obj)
                    if (result != null &&
                        result.javaClass.name == "com.instagram.feed.media.Media"
                    ) return result
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun readFieldByTypeName(obj: Any, typeName: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type.name == typeName) {
                    f.isAccessible = true
                    try {
                        return f.get(obj)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findVideoUrl(obj: Any?, visited: MutableSet<Any>, depth: Int): String? {
        if (obj == null || depth > 5 || !visited.add(obj)) return null
        val intfClass = videoVersionIntfClass ?: return null
        val getUrl = videoVersionGetUrl ?: return null

        if (intfClass.isInstance(obj)) {
            try {
                val url = getUrl.invoke(obj) as? String
                if (url != null && isCdnUrl(url)) {
                    FeedVideoDownloadHook.rememberVideoUrl(url)
                    return url
                }
            } catch (ignored: Throwable) {
            }
        }

        val cn = obj.javaClass.name
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") &&
            !cn.startsWith("com.facebook.")
        ) return null

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is List<*>) {
                        for (elem in value) {
                            if (intfClass.isInstance(elem)) {
                                try {
                                    val url = getUrl.invoke(elem) as? String
                                    if (url != null && isCdnUrl(url)) {
                                        FeedVideoDownloadHook.rememberVideoUrl(url)
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
                            val found = findVideoUrl(value, visited, depth + 1)
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

    private fun scanCdnUrls(
        obj: Any?,
        out: MutableList<String>,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        if (obj == null || depth > 5 || out.size >= 20) return
        if (!visited.add(obj)) return
        val cn = obj.javaClass.name
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") ||
            cn.startsWith("kotlin.")
        ) return

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is String) {
                        if (isCdnUrl(value) && !out.contains(value)) out.add(value)
                    } else if (value is List<*>) {
                        for (item in value) scanCdnUrls(item, out, depth + 1, visited)
                    } else {
                        val vcn = value.javaClass.name
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.") ||
                            vcn.startsWith("com.facebook.")
                        ) {
                            scanCdnUrls(value, out, depth + 1, visited)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }
    }

    private class CandidateInfo(
        @JvmField val url: String,
        @JvmField val area: Int,
    )

    private fun collectImageCandidates(
        obj: Any?,
        out: MutableList<CandidateInfo>,
        visited: MutableSet<Any>,
        depth: Int,
    ) {
        if (obj == null || depth > 7 || out.size >= 40) return
        if (!visited.add(obj)) return

        val cn = obj.javaClass.name
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") ||
            cn.startsWith("kotlin.")
        ) return

        var candidateUrl: String? = null
        val dims = ArrayList<Int>()

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                try {
                    if (f.type == String::class.java) {
                        val v = f.get(obj) as? String
                        if (v != null && isCdnUrl(v) && !isVideoUrl(v)) candidateUrl = v
                    } else if (f.type == Int::class.javaPrimitiveType) {
                        val v = f.getInt(obj)
                        if (v in 50..20_000) dims.add(v)
                    } else if (f.type == Long::class.javaPrimitiveType) {
                        val v = f.getLong(obj)
                        if (v in 50..20_000) dims.add(v.toInt())
                    }
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }

        if (cn.startsWith("X.") || cn.startsWith("com.instagram.") ||
            cn.startsWith("com.facebook.")
        ) {
            cls = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (m in cls.declaredMethods) {
                    if (m.parameterCount != 0) continue
                    try {
                        m.isAccessible = true
                        val ret = m.returnType
                        if (ret == String::class.java) {
                            val r = m.invoke(obj)
                            if (r is String && isCdnUrl(r) && !isVideoUrl(r) &&
                                candidateUrl == null
                            ) candidateUrl = r
                        } else if (ret == Int::class.javaPrimitiveType) {
                            val r = m.invoke(obj)
                            if (r is Int && r >= 50 && r <= 20_000) dims.add(r)
                        } else if (ret == Long::class.javaPrimitiveType) {
                            val r = m.invoke(obj)
                            if (r is Long && r >= 50 && r <= 20_000) dims.add(r.toInt())
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
        }

        val resolvedUrl = candidateUrl
        if (resolvedUrl != null && dims.size >= 2) {
            dims.sortWith(Collections.reverseOrder())
            out.add(CandidateInfo(resolvedUrl, dims[0] * dims[1]))
            return
        }

        cls = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is List<*>) {
                        for (item in value) {
                            collectImageCandidates(item, out, visited, depth + 1)
                        }
                    } else if (value !is String) {
                        val vcn = value.javaClass.name
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.") ||
                            vcn.startsWith("com.facebook.")
                        ) {
                            collectImageCandidates(value, out, visited, depth + 1)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }
    }

    private fun findContext(obj: Any?): Context? {
        if (obj == null) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (Context::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    try {
                        val v = f.get(obj)
                        if (v is Context) return v
                    } catch (ignored: Throwable) {
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isCdnUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false
        if (url.contains("/t51.") && url.contains("-19/")) return false
        return true
    }

    private fun isVideoUrl(url: String?): Boolean = FeedVideoDownloadHook.isVideoUrl(url)
    
    private fun pickBestUrl(urls: List<String>): String {
        for (u in urls) if (isVideoUrl(u)) return u
        var best: String? = null
        var bestArea = 0
        for (u in urls) {
            val area = parseUrlArea(u)
            if (area > bestArea) {
                bestArea = area
                best = u
            }
        }
        return best ?: urls[0]
    }

    private fun parseUrlArea(url: String): Int {
        var maxArea = 0
        var i = 0
        while (i < url.length) {
            if (!url[i].isDigit()) {
                i++
                continue
            }
            val numStart = i
            while (i < url.length && url[i].isDigit()) i++
            if (i >= url.length || url[i] != 'x') continue
            i++
            if (i >= url.length || !url[i].isDigit()) continue
            val numMid = i
            while (i < url.length && url[i].isDigit()) i++
            try {
                val w = url.substring(numStart, numMid - 1).toInt()
                val h = url.substring(numMid, i).toInt()
                val area = w * h
                if (area > maxArea) maxArea = area
            } catch (ignored: NumberFormatException) {
            }
        }
        return maxArea
    }

    private fun extractUsernameFromReelItemHolder(holder: Any?): String? {
        if (holder == null) {
            ModuleLog.line("(NA|Story|Username) holder is null")
            return null
        }
        ModuleLog.line(
            "(NA|Story|Username) searching in holder=" + holder.javaClass.name
        )
        try {
            var reelItem: Any? = null
            var cls: Class<*>? = holder.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    try {
                        val value = f.get(holder)
                        if (value != null &&
                            value.javaClass.name == "com.instagram.model.reels.ReelItem"
                        ) {
                            reelItem = value
                            ModuleLog.line(
                                "(NA|Story|Username) found ReelItem in field=" +
                                    f.name + " on " + cls.name
                            )
                            break
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                if (reelItem != null) break
                cls = cls.superclass
            }
            if (reelItem == null &&
                holder.javaClass.name == "com.instagram.model.reels.ReelItem"
            ) {
                reelItem = holder
                ModuleLog.line("(NA|Story|Username) holder is itself a ReelItem")
            }
            if (reelItem == null) {
                ModuleLog.line("(NA|Story|Username) ❌ ReelItem not found in holder")
                return null
            }

            for (m in reelItem.javaClass.declaredMethods) {
                if (m.parameterCount != 0) continue
                val ret = m.returnType
                if (ret.isPrimitive || ret == String::class.java || ret == Void.TYPE) continue
                try {
                    m.isAccessible = true
                    val candidate = m.invoke(reelItem) ?: continue

                    val candidateClass = candidate.javaClass.name

                    if (candidateClass == "com.instagram.user.model.User") {
                        val username = UserUtils.callUsernameGetter(candidate)
                        if (username != null) {
                            ModuleLog.line(
                                "(NA|Story|Username) reelItem." + m.name +
                                    "() [User] → " + username
                            )
                            return username
                        }
                        continue
                    }

                    if (candidateClass == "com.instagram.feed.media.Media") {
                        val username =
                            FeedVideoDownloadHook.extractUsernameFromMediaObject(candidate)
                        if (username != null) {
                            ModuleLog.line(
                                "(NA|Story|Username) reelItem." + m.name +
                                    "() [Media] → " + username
                            )
                            return username
                        }
                        continue
                    }
                } catch (ignored: Throwable) {
                }
            }

            ModuleLog.line("(NA|Story|Username) ❌ username not found on ReelItem methods")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Story|Username) ❌ Exception: $t")
        }
        return null
    }

    @Suppress("unused")
    private fun looksLikeUsername(s: String?): Boolean =
        s != null && s.length >= 2 && s.length <= 30 &&
            s.matches(Regex("[a-zA-Z0-9._]+")) &&
            !s.matches(Regex("\\d+"))

    private fun extractMediaIdFromReelItemHolder(holder: Any?): String? {
        if (holder == null) return null
        try {
            var reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem")
            if (reelItem == null &&
                holder.javaClass.name == "com.instagram.model.reels.ReelItem"
            ) {
                reelItem = holder
            }
            if (reelItem == null) return null
            val id = reelItem.javaClass.getMethod("getId").invoke(reelItem)
            if (id is String && id.isNotEmpty()) return id.split("_")[0]
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun handleStoryMedia(
        ctx: Context,
        media: StoryMediaOptions,
        username: String?,
        mediaId: String?,
    ) {
        val decision = StoryDownloadChoicePolicy.decide(
            media.imageUrl != null, media.videoUrl != null, media.modelSaysVideo
        )
        when (decision) {
            StoryDownloadChoicePolicy.Decision.DOWNLOAD_PHOTO ->
                startDownload(ctx, media.imageUrl, false, username, mediaId)

            StoryDownloadChoicePolicy.Decision.DOWNLOAD_VIDEO ->
                startDownload(ctx, media.videoUrl, true, username, mediaId)

            StoryDownloadChoicePolicy.Decision.ASK ->
                showStoryFormatDialog(ctx, media, username, mediaId)

            StoryDownloadChoicePolicy.Decision.NOT_FOUND -> Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showStoryFormatDialog(
        ctx: Context,
        media: StoryMediaOptions,
        username: String?,
        mediaId: String?,
    ) {
        val labels = ArrayList<CharSequence>()
        val choices = ArrayList<StoryMedia>()

        media.imageUrl?.let {
            labels.add(I18n.t(ctx, R.string.ig_story_download_photo))
            choices.add(StoryMedia(it, false))
        }
        media.videoUrl?.let {
            labels.add(I18n.t(ctx, R.string.ig_story_download_video_music))
            choices.add(StoryMedia(it, true))
        }

        try {
            val dp = ctx.resources.displayMetrics.density
            val dk = (
                ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES

            val sheetBg = if (dk) Color.parseColor("#1C1C1E") else Color.parseColor("#F2F2F7")
            val cardBg = if (dk) Color.parseColor("#2C2C2E") else Color.parseColor("#FFFFFF")
            val textPrim = if (dk) Color.WHITE else Color.parseColor("#1C1C1E")
            val textSec = if (dk) Color.parseColor("#AEAEB2") else Color.parseColor("#6C6C70")
            val handleClr = if (dk) Color.parseColor("#48484A") else Color.parseColor("#C7C7CC")

            val sheet = LinearLayout(ctx)
            sheet.orientation = LinearLayout.VERTICAL
            sheet.background = roundRect(sheetBg, 20f, dp)
            val hPad = (20 * dp).toInt()
            sheet.setPadding(hPad, (12 * dp).toInt(), hPad, (28 * dp).toInt())

            val handle = View(ctx)
            val handleLp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
            handleLp.gravity = Gravity.CENTER_HORIZONTAL
            handleLp.bottomMargin = (16 * dp).toInt()
            handle.layoutParams = handleLp
            handle.background = roundRect(handleClr, 2f, dp)
            sheet.addView(handle)

            val title = TextView(ctx)
            title.text = I18n.t(ctx, R.string.ig_story_download_choice_title)
            title.setTextColor(textPrim)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            title.setTypeface(null, Typeface.BOLD)
            sheet.addView(title)

            val subtitle = TextView(ctx)
            subtitle.text = I18n.t(ctx, R.string.ig_story_download_choice_subtitle)
            subtitle.setTextColor(textSec)
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val subLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            subLp.topMargin = (2 * dp).toInt()
            subLp.bottomMargin = (14 * dp).toInt()
            subtitle.layoutParams = subLp
            sheet.addView(subtitle)

            val dialog = Dialog(ctx)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            for (i in choices.indices) {
                val choice = choices[i]
                val row = TextView(ctx)
                row.text = (if (choice.video) "🎬  " else "📷  ").plus(labels[i])
                row.setTextColor(textPrim)
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                row.setTypeface(null, Typeface.BOLD)
                val rowPad = (16 * dp).toInt()
                row.setPadding(rowPad, rowPad, rowPad, rowPad)
                row.background = roundRect(cardBg, 12f, dp)
                val rowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rowLp.bottomMargin = (8 * dp).toInt()
                row.layoutParams = rowLp
                row.setOnClickListener {
                    dialog.dismiss()
                    startDownload(ctx, choice.url, choice.video, username, mediaId)
                }
                sheet.addView(row)
            }

            val cancel = makePillButton(
                ctx, ctx.getString(android.R.string.cancel), cardBg, textPrim, dp
            )
            cancel.setOnClickListener { dialog.dismiss() }
            sheet.addView(cancel)

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
            ModuleLog.line("(NA|Story) format dialog failed: $t")
            Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_toast_download_failed, t.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun roundRect(color: Int, radiusDp: Float, dp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusDp * dp
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
        btn.background = roundRect(bgColor, 14f, dp)
        btn.isAllCaps = false
        btn.setPadding(
            (20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt()
        )
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (10 * dp).toInt()
        btn.layoutParams = lp
        return btn
    }

    private fun startDownload(
        ctx: Context,
        url: String?,
        isVideo: Boolean,
        username: String?,
        mediaId: String?,
    ) {
        val fn = FeedVideoDownloadHook.buildFilename(username, "story", mediaId, isVideo)
        ModuleLog.line(
            "(NA|Story|DL) username=" + username + " mediaId=" + mediaId + " file=" + fn
        )
        Toast.makeText(
            ctx,
            if (isVideo) I18n.t(ctx, R.string.ig_toast_downloading_story_video)
            else I18n.t(ctx, R.string.ig_toast_downloading_story_photo),
            Toast.LENGTH_SHORT
        ).show()
        mainHandler.post {
            Thread {
                try {
                    val delegated =
                        FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVideo, username)
                    if (!delegated) {
                        mainHandler.post {
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_toast_story_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Throwable) {
                    mainHandler.post {
                        Toast.makeText(
                            ctx, I18n.t(ctx, R.string.ig_toast_download_failed, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
        }
    }

    @Suppress("unused")
    @Throws(Exception::class)
    private fun downloadToStream(url: String, out: OutputStream) {
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
}
