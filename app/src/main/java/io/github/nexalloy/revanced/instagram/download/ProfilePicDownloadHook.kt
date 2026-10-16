package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-press the enlarged profile picture (view id `expanded_profile_pic`) to save it.
 * The image view holds one or more ImageUrl fields; the largest one wins.
 *
 * Only `IgImageView.onAttachedToWindow` is hooked (CircularImageView and the other image views
 * extend it), not `View.onAttachedToWindow` for every view in the app. The check per call is a
 * single int comparison.
 */
internal object ProfilePicDownloadHook {

    private const val IG_IMAGE_VIEW = "com.instagram.common.ui.widget.imageview.IgImageView"

    @Volatile
    private var viewId = 0

    @Volatile
    private var viewIdResolved = false

    /** Views that already carry our listener; weak so recycled screens are released. */
    private val attached: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    private val onAttached = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!FeatureFlags.enableProfileDownload) return
            val v = param.thisObject as? View ?: return
            val id = v.id
            if (id == View.NO_ID) return
            val wanted = if (viewIdResolved) viewId else resolveViewId(v)
            if (wanted == 0 || id != wanted) return
            synchronized(attached) { if (!attached.add(v)) return }
            attach(v)
        }
    }

    fun install(cl: ClassLoader) {
        val imageView = runCatching { cl.loadClass(IG_IMAGE_VIEW) }.getOrNull()
        val target = imageView?.let {
            runCatching { it.getDeclaredMethod("onAttachedToWindow") }.getOrNull()
        }
        if (target != null) {
            XposedBridge.hookMethod(target, onAttached)
            ModuleLog.line("(NA|Profile) hooked IgImageView.onAttachedToWindow")
        } else {
            // Fallback for builds where IgImageView no longer overrides it.
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", onAttached)
            ModuleLog.line("(NA|Profile) IgImageView hook unavailable; using View.onAttachedToWindow")
        }
        FeatureStatusTracker.setHooked("ProfileDownload")
    }

    @SuppressLint("DiscouragedApi")
    @Synchronized
    private fun resolveViewId(view: View): Int {
        if (viewIdResolved) return viewId
        viewId = runCatching {
            view.resources.getIdentifier("expanded_profile_pic", "id", view.context.packageName)
        }.getOrDefault(0)
        viewIdResolved = true
        if (viewId == 0) ModuleLog.line("(NA|Profile) expanded_profile_pic id not present in this build")
        return viewId
    }

    private fun attach(view: View) {
        view.setOnLongClickListener { v ->
            val ctx = v.context
            val url = extractUrl(v)
            if (url == null) {
                MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found))
                return@setOnLongClickListener true
            }
            val username = CarouselIndex.activityOf(ctx)?.let { usernameFromScreen(it) }
            DownloadUi.saveOne(ctx, MediaItem(url, false), MediaKind.PROFILE, username, null)
            true
        }
    }

    private val imageFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /** Fields typed as an image url (ImageUrl / ExtendedImageUrl / …) on the view's classes. */
    private fun imageFields(cls: Class<*>): List<Field> = imageFieldCache.getOrPut(cls) {
        val imageUrl = MediaResolver.imageUrlType
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != View::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val t = f.type
                val isImageUrl = (imageUrl != null && imageUrl.isAssignableFrom(t)) || t.name.endsWith("ImageUrl")
                if (!isImageUrl) continue
                f.isAccessible = true
                out.add(f)
            }
            c = c.superclass
        }
        out
    }

    private fun extractUrl(view: View): String? {
        var bestUrl: String? = null
        var bestArea = -1L
        for (f in imageFields(view.javaClass)) {
            val img = runCatching { f.get(view) }.getOrNull() ?: continue
            val url = MediaResolver.imageUrlOf(img)?.takeIf { it.startsWith("http") } ?: continue
            val area = MediaResolver.imageAreaOf(img)
            if (area > bestArea) {
                bestArea = area
                bestUrl = url
            }
        }
        if (bestUrl != null) return bestUrl
        return when (val tag = view.tag) {
            is Uri -> tag.toString()
            is String -> tag.takeIf { it.startsWith("http") }
            else -> null
        }.also { if (it == null) ModuleLog.line("(NA|Profile) no image url on ${view.javaClass.name}") }
    }

    @SuppressLint("DiscouragedApi")
    private fun usernameFromScreen(activity: Activity): String? {
        val candidates = ArrayList<CharSequence?>()
        runCatching { candidates.add(activity.actionBar?.title) }
        runCatching {
            val id = activity.resources.getIdentifier("action_bar_title", "id", activity.packageName)
            if (id != 0) candidates.add(activity.findViewById<TextView>(id)?.text)
        }
        runCatching { candidates.add(activity.title) }
        return candidates.firstNotNullOfOrNull { cs ->
            cs?.toString()?.trim()?.takeIf {
                it.length in 1..30 && it.matches(Regex("[a-zA-Z0-9._]+")) && !it.matches(Regex("\\d+"))
            }
        }
    }
}
