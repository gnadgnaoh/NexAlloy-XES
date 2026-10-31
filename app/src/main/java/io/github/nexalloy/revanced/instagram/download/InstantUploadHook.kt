package io.github.nexalloy.revanced.instagram.download

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.R
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.max
import kotlin.math.roundToInt

internal class InstantUploadHook {

    fun install(cl: ClassLoader, captureMethods: List<Method>) {
        hookSwap(captureMethods)
        hookCameraOpen(cl)
        hookPickerResult()
    }

    private fun hookSwap(captureMethods: List<Method>) {
        if (captureMethods.isEmpty()) {
            ModuleLog.line("(NA|InstantUpload) capture methods not found")
            return
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    if (!FeatureFlags.uploadInstants || p.args.size < 2 || p.args[1] !is Bitmap) return
                    if (pendingBitmap != null && System.currentTimeMillis() - pendingSetAt > PENDING_TTL_MS) {
                        pendingBitmap = null
                    }
                    val pending = pendingBitmap ?: return
                    pendingBitmap = null
                    p.args[1] = pending
                    MediaSaver.mainHandler.post { removeChip() }
                    ModuleLog.line("(NA|InstantUpload) swapped in gallery bitmap (${p.method.name})")
                } catch (t: Throwable) {
                    ModuleLog.line("(NA|InstantUpload) swap: $t")
                }
            }
        }
        for (m in captureMethods) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, hook)
        }
        FeatureStatusTracker.setHooked("UploadInstants")
        ModuleLog.line("(NA|InstantUpload) capture hooked: ${captureMethods.joinToString { it.name }}")
    }

    private fun hookCameraOpen(cl: ClassLoader) {
        registerLifecycle(cl)
        try {
            val vm = XposedHelpers.findClass(QUICKSNAP_VM_CLASS, cl)
            val onActive: XC_MethodHook = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    quickSnapActiveAt = SystemClock.uptimeMillis()
                    if (!FeatureFlags.uploadInstants) return
                    if (!lifecycleRegistered) registerLifecycle(cl)
                    val act = currentActivity() ?: return
                    if (watchHost !== act && act.javaClass.name.contains("ModalActivity")) MediaSaver.mainHandler.post { startWatch(act) }
                }
            }
            var n = 0
            for (m in vm.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 0) continue
                if (m.returnType != Void.TYPE) continue
                if (m.isSynthetic || m.isBridge) continue
                try {
                    XposedBridge.hookMethod(m, onActive)
                    n++
                } catch (ignored: Throwable) {
                }
            }
            ModuleLog.line("(NA|InstantUpload) ✅ camera-open hooked ($n VM methods)")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|InstantUpload) ⚠️ camera-open: " + t.message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun registerLifecycle(cl: ClassLoader) {
        if (lifecycleRegistered) return
        try {
            val app = AndroidAppHelper.currentApplication() ?: return
            app.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityPaused(a: Activity) {
                        if (resumed?.get() === a) resumed = null
                        if (a === watchHost && !pickerOpen) stopWatch()
                        if (a === chipHost && !pickerOpen) removeChip()
                    }

                    override fun onActivityStopped(a: Activity) {
                        if (a === chipHost && !pickerOpen) removeChip()
                    }

                    override fun onActivityDestroyed(a: Activity) {
                        if (a === watchHost) stopWatch()
                        if (a === chipHost) {
                            pickerOpen = false
                            removeChip()
                        }
                    }

                    override fun onActivityResumed(a: Activity) {
                        resumed = WeakReference(a)
                        if (a === chipHost) pickerOpen = false
                        if (chip != null && a !== chipHost) removeChip()
                        if (FeatureFlags.uploadInstants && a.javaClass.name.contains("ModalActivity")) startWatch(a)
                    }

                    override fun onActivityCreated(a: Activity, b: Bundle?) {}

                    override fun onActivityStarted(a: Activity) {}

                    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                }
            )
            lifecycleRegistered = true
        } catch (ignored: Throwable) {
        }
    }

    private fun hookPickerResult() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onActivityResult",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val req = p.args[0] as Int
                            val res = p.args[1] as Int
                            if (req != PICK_REQUEST) return
                            pickerOpen = false
                            if (res != Activity.RESULT_OK || p.args[2] == null) return
                            val uri = (p.args[2] as Intent).data ?: return
                            val act = p.thisObject as Activity
                            val bmp = decode(act, uri)
                            if (bmp == null) {
                                Toast.makeText(
                                    act, I18n.t(act, R.string.ig_instant_upload_fail),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return
                            }
                            pendingBitmap = bmp
                            pendingSetAt = System.currentTimeMillis()
                            pickerOpen = false
                            if (chip == null || chipHost !== act) injectChip(act)
                            markChipReady(act, bmp)
                        } catch (t: Throwable) {
                            ModuleLog.line("(NA|InstantUpload) ❌ result: $t")
                        }
                    }
                }
            )
            ModuleLog.line("(NA|InstantUpload) ✅ picker-result hooked")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|InstantUpload) ⚠️ picker-result: " + t.message)
        }
    }

    companion object {

        private const val PICK_REQUEST = 0x1E5A

        @Volatile
        @JvmField
        internal var pendingBitmap: Bitmap? = null

        @Volatile
        private var pendingSetAt = 0L

        private const val PENDING_TTL_MS = 5L * 60 * 1000

        private var chip: FrameLayout? = null

        private var chipButton: FrameLayout? = null

        private var chipIcon: View? = null

        private var chipThumb: ImageView? = null

        private var chipBadge: ImageView? = null

        private var hint: TextView? = null

        private var chipHost: Activity? = null

        @Volatile
        private var pickerOpen = false

        private var lifecycleRegistered = false

        private const val MAX_EDGE = 1440

        private val GREEN = Color.parseColor("#30D158")

        private const val WATCH_MS = 300L

        private const val IDLE_WATCH_MS = 1000L

        private const val QUICKSNAP_VIEW_PREFIX = "com.instagram.quicksnap.camera.ui."

        private var cameraViewId = 0

        private var watchHost: Activity? = null

        @Volatile
        private var quickSnapActiveAt = 0L

        @Volatile
        private var watchStartedAt = 0L

        private val quickSnapHosts = java.util.WeakHashMap<Activity, Boolean>()

        private val watch = object : Runnable {
            override fun run() {
                val host = watchHost ?: return
                if (!FeatureFlags.uploadInstants) return
                val hasCam = hasCameraView(host)
                val on = hasCam && (quickSnapActiveAt >= watchStartedAt - 2000 || isQuickSnapCamera(host)) && cameraOnScreen(host)
                if (on && (chip == null || chipHost !== host)) injectChip(host)
                if (chip != null && chipHost === host) {
                    val vis = if (on || pickerOpen) View.VISIBLE else View.GONE
                    if (chip?.visibility != vis) chip?.visibility = vis
                    if (hint?.visibility != vis) hint?.visibility = vis
                }
                MediaSaver.mainHandler.postDelayed(this, if (hasCam) WATCH_MS else IDLE_WATCH_MS)
            }
        }

        private fun startWatch(act: Activity) {
            watchHost = act
            watchStartedAt = SystemClock.uptimeMillis()
            MediaSaver.mainHandler.removeCallbacks(watch)
            MediaSaver.mainHandler.post(watch)
        }

        private fun stopWatch() {
            watchHost = null
            MediaSaver.mainHandler.removeCallbacks(watch)
        }

        private fun cameraView(act: Activity): View? {
            if (cameraViewId == 0) cameraViewId = act.resources.getIdentifier("camera_view", "id", act.packageName)
            return if (cameraViewId == 0) null else act.window.decorView.findViewById(cameraViewId)
        }

        private fun hasCameraView(act: Activity): Boolean = runCatching { cameraView(act) != null }.getOrDefault(false)

        private fun cameraOnScreen(act: Activity): Boolean = runCatching {
            val cam = cameraView(act) ?: return@runCatching true
            if (!cam.isShown || cam.width == 0) return@runCatching false
            val at = IntArray(2)
            cam.getLocationOnScreen(at)
            kotlin.math.abs(at[0]) < cam.width / 2
        }.getOrDefault(true)

        private fun isQuickSnapCamera(act: Activity): Boolean {
            if (quickSnapHosts[act] == true) return true
            val found = hasQuickSnapView(act.window.decorView, 0)
            if (found) quickSnapHosts[act] = true
            return found
        }

        private fun hasQuickSnapView(v: View, depth: Int): Boolean {
            if (v.javaClass.name.startsWith(QUICKSNAP_VIEW_PREFIX)) return true
            if (depth > 40 || v !is ViewGroup) return false
            for (i in 0 until v.childCount) if (hasQuickSnapView(v.getChildAt(i), depth + 1)) return true
            return false
        }

        private fun cleanLabel(s: String): String {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (Character.isLetterOrDigit(cp)) break
                i += Character.charCount(cp)
            }
            return if (i < s.length) s.substring(i).trim() else s.trim()
        }

        private fun navInset(act: Activity, decor: View): Int {
            runCatching {
                val insets = decor.rootWindowInsets
                if (insets != null) {
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                    } else {
                        @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                    }
                }
            }
            return runCatching {
                val id = act.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (id != 0) act.resources.getDimensionPixelSize(id) else dp(act, 48)
            }.getOrDefault(dp(act, 48))
        }

        private fun markChipReady(act: Activity, bmp: Bitmap) {
            try {
                val decor = act.window.decorView as? ViewGroup ?: return
                removeHint()
                val pill = TextView(act).apply {
                    text = cleanLabel(I18n.t(act, R.string.ig_instant_upload_chip_ready))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    isSingleLine = true
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(act, 8), dp(act, 6), dp(act, 12), dp(act, 6))
                    I18n.drawable(act, R.drawable.ic_na_check_circle)?.mutate()?.let { check ->
                        check.setTint(GREEN)
                        val side = dp(act, 16)
                        check.setBounds(0, 0, side, side)
                        setCompoundDrawablesRelative(check, null, null, null)
                        compoundDrawablePadding = dp(act, 6)
                    }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#A6000000"))
                        setStroke(dp(act, 1), Color.parseColor("#26FFFFFF"))
                        cornerRadius = dp(act, 16).toFloat()
                    }
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        bottomMargin = navInset(act, decor) + dp(act, 198)
                    }
                    alpha = 0f
                }
                decor.addView(pill)
                pill.animate().alpha(1f).setDuration(180).start()
                hint = pill
                applyChipState(act, bmp)
                chip?.bringToFront()
            } catch (ignored: Throwable) {
            }
        }

        private fun applyChipState(act: Activity, bmp: Bitmap?) {
            val button = chipButton ?: return
            val ready = bmp != null
            val disc = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(dp(act, if (ready) 2 else 1), if (ready) Color.WHITE else Color.parseColor("#26FFFFFF"))
            }
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            button.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#40FFFFFF")), disc, mask)
            chipThumb?.apply {
                setImageBitmap(if (ready) bmp else null)
                visibility = if (ready) View.VISIBLE else View.GONE
            }
            chipIcon?.visibility = if (ready) View.GONE else View.VISIBLE
            chipBadge?.apply {
                visibility = if (ready) View.VISIBLE else View.GONE
                if (ready) {
                    scaleX = 0.4f
                    scaleY = 0.4f
                    animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator()).start()
                }
            }
            button.contentDescription = cleanLabel(I18n.t(act, if (ready) R.string.ig_instant_upload_chip_ready else R.string.ig_instant_upload_chip))
        }

        private fun removeHint() {
            runCatching { hint?.let { h -> (h.parent as? ViewGroup)?.removeView(h) } }
            hint = null
        }

        private fun removeChip() {
            runCatching { chip?.let { c -> (c.parent as? ViewGroup)?.removeView(c) } }
            removeHint()
            chip = null
            chipButton = null
            chipIcon = null
            chipThumb = null
            chipBadge = null
            chipHost = null
        }

        private fun injectChip(act: Activity) {
            try {
                if (!act.javaClass.name.contains("ModalActivity")) return
                if (chip != null && chipHost === act) return
                removeChip()
                val decor = act.window.decorView as? ViewGroup ?: return

                val size = dp(act, 48)
                val badgeSize = dp(act, 18)
                val box = size + dp(act, 6)
                val holder = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }

                val button = FrameLayout(act).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { launchPicker(act) }
                    setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }
                        false
                    }
                }

                val ring = dp(act, 2)
                val thumb = ImageView(act).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(ring, ring, ring, ring)
                    cropToPadding = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(ring, ring, view.width - ring, view.height - ring)
                        }
                    }
                    clipToOutline = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = View.GONE
                }
                button.addView(thumb)

                val glyph = I18n.drawable(act, R.drawable.ic_na_gallery)
                val icon: View = if (glyph != null) {
                    ImageView(act).apply {
                        setImageDrawable(glyph)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        val side = dp(act, 24)
                        layoutParams = FrameLayout.LayoutParams(side, side, Gravity.CENTER)
                    }
                } else {
                    TextView(act).apply {
                        text = "🖼"
                        textSize = 18f
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    }
                }
                icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                button.addView(icon)
                holder.addView(button)

                val badge = ImageView(act).apply {
                    I18n.drawable(act, R.drawable.ic_na_check_circle)?.let { setImageDrawable(it) }
                    imageTintList = ColorStateList.valueOf(GREEN)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke(dp(act, 1), Color.BLACK)
                    }
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = View.GONE
                }
                holder.addView(badge)

                val width = if (decor.width > 0) decor.width else act.resources.displayMetrics.widthPixels
                holder.layoutParams = FrameLayout.LayoutParams(box, box).apply {
                    gravity = Gravity.BOTTOM or Gravity.LEFT
                    leftMargin = max(dp(act, 8), width / 2 - dp(act, 106) - box / 2)
                    bottomMargin = navInset(act, decor) + dp(act, 40) - box / 2
                }
                holder.alpha = 0f
                decor.addView(holder)
                holder.animate().alpha(1f).setDuration(160).start()
                chip = holder
                chipButton = button
                chipIcon = icon
                chipThumb = thumb
                chipBadge = badge
                chipHost = act
                applyChipState(act, pendingBitmap)
                ModuleLog.line("(NA|InstantUpload) ✅ gallery button injected")
            } catch (t: Throwable) {
                ModuleLog.line("(NA|InstantUpload) ❌ chip: $t")
            }
        }

        private fun launchPicker(act: Activity) {
            try {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                i.addCategory(Intent.CATEGORY_OPENABLE)
                pickerOpen = true
                act.startActivityForResult(
                    Intent.createChooser(i, I18n.t(act, R.string.ig_instant_upload_chip)),
                    PICK_REQUEST
                )
            } catch (t: Throwable) {
                pickerOpen = false
                Toast.makeText(
                    act, I18n.t(act, R.string.ig_instant_upload_fail), Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun decode(act: Activity, uri: Uri): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(act.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                        decoder.setMutableRequired(false)
                        val w = info.size.width
                        val h = info.size.height
                        val longEdge = max(w, h)
                        if (longEdge > MAX_EDGE) {
                            val scale = MAX_EDGE.toFloat() / longEdge
                            decoder.setTargetSize(
                                (w * scale).roundToInt(), (h * scale).roundToInt()
                            )
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    downscale(MediaStore.Images.Media.getBitmap(act.contentResolver, uri))
                }
            } catch (t: Throwable) {
                ModuleLog.line("(NA|InstantUpload) ❌ decode: $t")
                null
            }
        }

        private fun downscale(input: Bitmap?): Bitmap? {
            if (input == null) return null
            val longEdge = max(input.width, input.height)
            if (longEdge <= MAX_EDGE) return input
            val scale = MAX_EDGE.toFloat() / longEdge
            return Bitmap.createScaledBitmap(
                input,
                (input.width * scale).roundToInt(),
                (input.height * scale).roundToInt(),
                true
            )
        }

        private fun dp(a: Activity, v: Int): Int =
            (v * a.resources.displayMetrics.density).roundToInt()

        @Volatile
        private var resumed: WeakReference<Activity>? = null

        @Suppress("UNCHECKED_CAST")
        internal fun currentActivity(): Activity? {
            resumed?.get()?.let { return it }
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val at = atClass.getMethod("currentActivityThread").invoke(null)
                val f = atClass.getDeclaredField("mActivities")
                f.isAccessible = true
                val acts = f.get(at) as? Map<Any, Any> ?: return null
                for (record in acts.values) {
                    val rc = record.javaClass
                    val paused = rc.getDeclaredField("paused")
                    paused.isAccessible = true
                    if (paused.get(record) == false) {
                        val af = rc.getDeclaredField("activity")
                        af.isAccessible = true
                        return (af.get(record) as Activity?)?.also { resumed = WeakReference(it) }
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }
}
