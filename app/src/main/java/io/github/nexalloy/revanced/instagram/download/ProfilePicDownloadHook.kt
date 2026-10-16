package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-press the enlarged profile picture (view id `expanded_profile_pic`) to save it.
 * The image view holds one or more ImageUrl fields; the largest one wins.
 */
internal object ProfilePicDownloadHook {

    private const val HOOKED_TAG = "ie_profile_dl"

    @Volatile
    private var viewId = 0

    @Volatile
    private var viewIdResolved = false

    fun install() {
        XposedHelpers.findAndHookMethod(
            View::class.java, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureFlags.enableProfileDownload) return
                    val v = param.thisObject as View
                    val id = v.id
                    if (id == View.NO_ID) return
                    val wanted = if (viewIdResolved) viewId else resolveViewId(v)
                    if (wanted == 0 || id != wanted || v.tag == HOOKED_TAG) return
                    attach(v)
                }
            }
        )
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
        view.tag = HOOKED_TAG
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
