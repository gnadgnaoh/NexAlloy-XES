package io.github.nexalloy.revanced.threads.profile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.ProfilePictureViewer
import io.github.nexalloy.revanced.threads.download.ThreadsSaver
import io.github.nexalloy.revanced.threads.tracking.USER
import io.github.nexalloy.revanced.threads.tracking.userNameFingerprint
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.abs

val ProfilePictureMenu = patch(
    name = "Profile picture menu",
    description = "Long-press an opened profile picture to view it full screen, save it, or copy the username and bio.",
) {
    val userClass = classLoader.loadClass(USER)
    val imageUrl = classLoader.loadClass(IMAGE_URL)
    val getUrl = imageUrl.getMethod("getUrl")
    val username = runCatching { ::userNameFingerprint.method.apply { isAccessible = true } }.getOrNull()
    val biography = runCatching { ::userBiographyFingerprint.method.apply { isAccessible = true } }.getOrNull()
    val hdPicture = runCatching { ::userHdPictureFingerprint.method.apply { isAccessible = true } }.getOrNull()

    fun hdUrl(owner: Any?): String? {
        val info = if (hdPicture == null || owner == null) null else runCatching { hdPicture.invoke(owner) }.getOrNull()
        return info?.let { runCatching { it.javaClass.getMethod("getUrl").invoke(it) as? String }.getOrNull() }?.takeIf { it.startsWith("http") }
    }

    val inScreen = ThreadLocal<Boolean>()
    val shown = ThreadLocal<Boolean>()
    var active = false
    var url: String? = null
    var user = WeakReference<Any>(null)

    ::focusedPictureHostFingerprint.dexMethodList.filter { it.isMethod }.map { it.toMethod() }.forEach { host ->
        host.hookMethod {
            before { param ->
                param.setObjectExtra("na_shown", shown.get())
                shown.set(false)
            }
            after { param ->
                val opened = shown.get() == true
                shown.set(param.getObjectExtra("na_shown") as? Boolean)
                active = opened
                if (!opened) return@after
                val owner = param.thisObject ?: return@after
                owner.javaClass.declaredFields.firstOrNull { it.type == userClass }
                    ?.let { f -> runCatching { f.isAccessible = true; f.get(owner) }.getOrNull() }
                    ?.let { user = WeakReference(it) }
            }
        }
    }

    ::focusedPictureScreenFingerprint.hookMethod {
        before { param ->
            shown.set(true)
            active = true
            param.setObjectExtra("na_in", inScreen.get())
            inScreen.set(true)
        }
        after { param -> inScreen.set(param.getObjectExtra("na_in") as? Boolean) }
    }

    ::focusedPictureImageFingerprint.hookMethod {
        before { param ->
            if (inScreen.get() != true) return@before
            val image = param.args.lastOrNull() ?: return@before
            runCatching { getUrl.invoke(image) as? String }.getOrNull()?.takeIf { it.startsWith("http") }?.let { url = it }
        }
    }

    fun read(getter: Method?, owner: Any?): String? =
        if (getter == null || owner == null) null
        else runCatching { getter.invoke(owner) as? String }.getOrNull()?.takeIf { it.isNotBlank() }

    fun copy(ctx: Context, text: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Threads", text))
        ThreadsSaver.toast(ctx, ThreadsSaver.t(ctx, R.string.th_copied))
    }

    fun showMenu(activity: Activity, x: Float, y: Float) {
        val owner = user.get()
        val picture = hdUrl(owner) ?: url
        val name = read(username, owner)
        val bio = read(biography, owner)
        val items = listOfNotNull(
            picture?.let { ThreadsSaver.t(activity, R.string.th_pfp_view) to { ProfilePictureViewer.show(activity, it) } },
            picture?.let {
                ThreadsSaver.t(activity, R.string.th_pfp_save) to {
                    ThreadsSaver.saveImage(activity, it, "threads_${name ?: "user"}_pfp_${System.currentTimeMillis()}")
                }
            },
            name?.let { ThreadsSaver.t(activity, R.string.th_copy_username) to { copy(activity, it) } },
            bio?.let { ThreadsSaver.t(activity, R.string.th_copy_bio) to { copy(activity, it) } },
        )
        if (picture == null) ThreadsSaver.toast(activity, ThreadsSaver.t(activity, R.string.th_pfp_not_found))
        if (items.isEmpty()) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val anchor = View(activity)
        decor.addView(anchor, FrameLayout.LayoutParams(1, 1))
        anchor.translationX = x
        anchor.translationY = y
        val menu = PopupMenu(activity, anchor)
        items.forEachIndexed { i, (title, _) -> menu.menu.add(0, i, i, title) }
        menu.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        menu.setOnDismissListener { decor.removeView(anchor) }
        menu.show()
    }

    val main = Handler(Looper.getMainLooper())
    var pending: Runnable? = null
    var downX = 0f
    var downY = 0f
    Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java).hookMethod {
        before { param ->
            val e = param.args[0] as MotionEvent
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pending?.let(main::removeCallbacks)
                    if (!active) return@before
                    downX = e.rawX
                    downY = e.rawY
                    val activity = param.thisObject as Activity
                    pending = Runnable { if (active) runCatching { showMenu(activity, downX, downY) } }
                        .also { main.postDelayed(it, 550) }
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > 40) pending?.let(main::removeCallbacks)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                    pending?.let(main::removeCallbacks)
            }
        }
    }
}
