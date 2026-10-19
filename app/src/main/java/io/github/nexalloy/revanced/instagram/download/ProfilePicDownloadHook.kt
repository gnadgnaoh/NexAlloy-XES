package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.revanced.instagram.profile.ProfileHeader
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal object ProfilePicDownloadHook {

    private const val IG_IMAGE_VIEW = "com.instagram.common.ui.widget.imageview.IgImageView"

    @Volatile
    private var viewId = 0

    @Volatile
    private var viewIdResolved = false

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
        val target = IG_IMAGE_VIEW.findClassOrNull(cl)?.declaredMethods
            ?.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
        if (target != null) {
            XposedBridge.hookMethod(target, onAttached)
            ModuleLog.line("(NA|Profile) hooked IgImageView.onAttachedToWindow")
        } else {
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
            runCatching { showMenu(v) }.onFailure { ModuleLog.line("(NA|Profile) menu failed: $it") }
            true
        }
    }

    private fun showMenu(v: View) {
        val ctx = v.context
        val user = ProfileHeader.lastUser
        val username = user?.let { UserUtils.callUsernameGetter(it) }
            ?: CarouselIndex.activityOf(ctx)?.let { usernameFromScreen(it) }
        val bio = user?.let { u -> runCatching { ProfileHeader.biographyGetter?.invoke(u) as? String }.getOrNull() }
        val url = fullSizeUrl(user) ?: extractUrl(v)
        val menu = PopupMenu(ctx, v)
        val items = listOfNotNull(
            url?.let { I18n.t(ctx, R.string.ig_profile_pic_view) to { ProfilePictureViewer.show(ctx, it) } },
            url?.let { I18n.t(ctx, R.string.ig_profile_pic_download) to { DownloadUi.saveOne(ctx, MediaItem(it, false), MediaKind.PROFILE, username, null) } },
            username?.takeIf { it.isNotEmpty() }?.let { I18n.t(ctx, R.string.ig_copy_username) to { copy(ctx, it) } },
            bio?.takeIf { it.isNotBlank() }?.let { I18n.t(ctx, R.string.ig_copy_bio) to { copy(ctx, it) } },
        )
        if (items.isEmpty()) {
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found))
            return
        }
        items.forEachIndexed { i, (title, _) -> menu.menu.add(0, i, i, title) }
        menu.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        menu.show()
    }

    private fun fullSizeUrl(user: Any?): String? {
        val info = user?.let { runCatching { ProfileHeader.hdPictureGetter?.invoke(it) }.getOrNull() } ?: return null
        return runCatching { info.javaClass.getMethod("getUrl").invoke(info) as? String }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun copy(ctx: Context, text: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Instagram", text))
        MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_copied))
    }

    private val imageFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

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
