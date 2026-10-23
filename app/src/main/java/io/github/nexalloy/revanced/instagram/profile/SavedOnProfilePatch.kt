package io.github.nexalloy.revanced.instagram.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.LinearLayout
import io.github.nexalloy.R
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import java.lang.reflect.Method
import java.util.WeakHashMap

val SavedOnProfile = patch(
    name = "Saved on your profile",
    description = "Adds a bookmark tab to your own profile, beside posts, reels and tagged, that opens Saved.",
) {
    (appContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = SavedTab.watch(activity)
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })
}

private object SavedTab {
    private const val TAB_LAYOUT = "profile_tab_layout"
    private const val CREATE_BUTTON = "profile_header_create_button"
    private const val ACTION_BUTTONS = "right_action_bar_buttons"
    private const val LOOK_EVERY_MS = 600L
    private const val WAIT_FOR_MENU_MS = 2000L
    private const val HOLD_MAX_MS = 2500L
    private const val POLL_MS = 50L
    private const val NODE_LIMIT = 600

    private val watched = WeakHashMap<Activity, Boolean>()
    private val rows = WeakHashMap<ViewGroup, Row>()

    fun watch(activity: Activity) {
        synchronized(watched) { if (watched.put(activity, true) != null) return }
        val decor = activity.window?.decorView ?: return
        var last = 0L
        var pending = false
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val now = SystemClock.uptimeMillis()
            if (now - last < LOOK_EVERY_MS) {
                if (!pending) {
                    pending = true
                    decor.postDelayed({ pending = false; last = SystemClock.uptimeMillis(); look(activity) }, LOOK_EVERY_MS)
                }
                return@addOnGlobalLayoutListener
            }
            last = now
            look(activity)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun find(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    private fun look(activity: Activity) = runCatching {
        val decor = activity.window.decorView
        val tabs = find(decor, TAB_LAYOUT) as? ViewGroup ?: return@runCatching
        if (!tabs.isShown || tabs.width <= 0) return@runCatching
        if (find(decor, CREATE_BUTTON)?.isShown != true) return@runCatching
        val row = synchronized(rows) { rows.getOrPut(tabs) { Row(activity, tabs) } }
        row.keep()
    }

    private class Row(private val activity: Activity, private val tabs: ViewGroup) : View.OnTouchListener {
        private val icon = Icon(activity)
        private var down = false

        init {
            tabs.overlay.add(icon)
            tabs.setOnTouchListener(this)
        }

        private fun slot(): Int {
            val own = (tabs.getChildAt(0) as? ViewGroup)?.childCount ?: 3
            return tabs.width / (own.coerceAtLeast(1) + 1)
        }

        fun keep() {
            val slot = slot()
            (tabs.getChildAt(0) as? LinearLayout)?.let { group ->
                var changed = false
                for (i in 0 until group.childCount) {
                    val tab = group.getChildAt(i)
                    val params = tab.layoutParams ?: continue
                    if (params.width == slot) continue
                    (params as? LinearLayout.LayoutParams)?.weight = 0f
                    params.width = slot
                    tab.layoutParams = params
                    changed = true
                }
                if (changed) group.requestLayout()
                val gravity = (group.gravity and Gravity.VERTICAL_GRAVITY_MASK) or Gravity.START
                if (group.gravity != gravity) group.gravity = gravity
            }
            icon.setBounds(tabs.width - slot, 0, tabs.width, tabs.height)
        }

        private fun inBookmark(e: MotionEvent) = e.x >= tabs.width - slot() && e.x <= tabs.width

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, e: MotionEvent): Boolean = runCatching {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> inBookmark(e).also { down = it }
                MotionEvent.ACTION_UP -> {
                    val tapped = down && inBookmark(e)
                    down = false
                    if (tapped) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        open(activity)
                    }
                    tapped
                }
                MotionEvent.ACTION_CANCEL -> down.also { down = false }
                else -> down
            }
        }.getOrDefault(false)
    }

    private fun open(activity: Activity) {
        val decor = activity.window.decorView
        runCatching {
            val menu = (find(decor, ACTION_BUTTONS) as? ViewGroup)?.let { g ->
                (g.childCount - 1 downTo 0).map { g.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE && it.isClickable }
            } ?: return
            val handler = Handler(Looper.getMainLooper())
            val started = SystemClock.uptimeMillis()
            decor.alpha = 0f
            handler.postDelayed({ decor.alpha = 1f }, HOLD_MAX_MS)
            menu.performClick()
            handler.postDelayed(object : Runnable {
                override fun run() {
                    val done = runCatching { tapSaved(decor) }.getOrDefault(false)
                    if (done) return run { handler.postDelayed({ decor.alpha = 1f }, 400) }
                    if (childId != null && SystemClock.uptimeMillis() - started < WAIT_FOR_MENU_MS) {
                        handler.postDelayed(this, POLL_MS)
                        return
                    }
                    decor.alpha = 1f
                }
            }, POLL_MS)
        }.onFailure { decor.alpha = 1f }
    }

    private fun tapSaved(root: View): Boolean {
        if (root.visibility != View.VISIBLE) return false
        if (root.javaClass.name.startsWith("androidx.compose.") && tapNode(root)) return true
        if (root is ViewGroup) for (i in 0 until root.childCount) if (tapSaved(root.getChildAt(i))) return true
        return false
    }

    private fun tapNode(compose: View): Boolean {
        val provider: AccessibilityNodeProvider = compose.accessibilityNodeProvider ?: return false
        val childOf = childId ?: return false
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(AccessibilityNodeProvider.HOST_VIEW_ID to AccessibilityNodeProvider.HOST_VIEW_ID) }
        val parents = HashMap<Int, Int>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < NODE_LIMIT) {
            val (id, parent) = queue.removeFirst()
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            parents[id] = parent
            val label = (node.text ?: node.contentDescription)?.toString()
            if (label != null && isSavedLabel(compose.context, label)) {
                var at = id
                while (true) {
                    if (provider.performAction(at, AccessibilityNodeInfo.ACTION_CLICK, null)) return true
                    val up = parents[at] ?: break
                    if (up == at || up == AccessibilityNodeProvider.HOST_VIEW_ID) break
                    at = up
                }
            }
            for (i in 0 until node.childCount) {
                val child = childOf.invoke(node, i) as Long
                queue.add((child ushr 32).toInt() to id)
            }
        }
        return false
    }

    private val childId: Method? by lazy {
        runCatching { AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType).apply { isAccessible = true } }.getOrNull()
    }

    private fun isSavedLabel(ctx: Context, label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.equals("Saved", true) || trimmed.equals(I18n.t(ctx, R.string.ig_saved_label), true)
    }

    private class Icon(ctx: Context) : Drawable() {
        private val density = ctx.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            color = if (night == Configuration.UI_MODE_NIGHT_YES) 0xb3ffffff.toInt() else 0x99000000.toInt()
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val w = 18 * density
            val h = 22 * density
            val left = bounds.left + (bounds.width() - w) / 2f
            val top = bounds.top + (bounds.height() - h) / 2f
            paint.strokeWidth = 2f * density
            path.reset()
            path.moveTo(left, top)
            path.lineTo(left + w, top)
            path.lineTo(left + w, top + h)
            path.lineTo(left + w / 2f, top + h - 6 * density)
            path.lineTo(left, top + h)
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
