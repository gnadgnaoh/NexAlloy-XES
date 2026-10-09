package io.github.nexalloy.revanced.instagram.download

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
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
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.max
import kotlin.math.roundToInt

class InstantUploadHook {

    fun install(cl: ClassLoader) {
        hookSwap(cl)
        hookCameraOpen(cl)
        hookPickerResult()
    }

    private fun hookSwap(cl: ClassLoader) {
        try {
            val vm = XposedHelpers.findClass(VM_CLASS, cl)
            var a02: Method? = null
            for (m in vm.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                var bmp = 0
                for (t in m.parameterTypes) if (t == Bitmap::class.java) bmp++
                if (bmp >= 2) {
                    a02 = m
                    break
                }
            }
            if (a02 == null) {
                ModuleLog.line("(NA|InstantUpload) ⚠️ A02 not found")
                return
            }
            XposedBridge.hookMethod(a02, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        if (p.args[1] !is Bitmap) return
                        if (pendingBitmap != null &&
                            System.currentTimeMillis() - pendingSetAt > PENDING_TTL_MS
                        ) {
                            pendingBitmap = null
                        }
                        val pending = pendingBitmap
                        if (FeatureFlags.uploadInstants && pending != null) {
                            p.args[1] = pending
                            if (p.args[2] != null) {
                                val config = pending.config ?: Bitmap.Config.ARGB_8888
                                p.args[2] = pending.copy(config, false)
                            }
                            pendingBitmap = null
                            FeedVideoDownloadHook.mainHandler.post { removeChip() }
                            ModuleLog.line("(NA|InstantUpload) ✅ swapped in gallery bitmap")
                        }
                    } catch (t: Throwable) {
                        ModuleLog.line("(NA|InstantUpload) ❌ swap: $t")
                    }
                }
            })
            FeatureStatusTracker.setHooked("UploadInstants")
            ModuleLog.line("(NA|InstantUpload) ✅ A02 hooked")
        } catch (t: Throwable) {
            ModuleLog.line("(NA|InstantUpload) ⚠️ swap install: " + t.message)
        }
    }

    private fun hookCameraOpen(cl: ClassLoader) {
        registerLifecycle(cl)
        try {
            val vm = XposedHelpers.findClass(VM_CLASS, cl)
            val onActive: XC_MethodHook = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!FeatureFlags.uploadInstants) return
                    val act = currentActivity()
                    if (act != null) {
                        FeedVideoDownloadHook.mainHandler.post { injectChip(act) }
                    }
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
                        if (a === chipHost) removeChip()
                    }

                    override fun onActivityStopped(a: Activity) {
                        if (a === chipHost) removeChip()
                    }

                    override fun onActivityDestroyed(a: Activity) {
                        if (a === chipHost) removeChip()
                    }

                    override fun onActivityResumed(a: Activity) {
                        if (chip != null && a !== chipHost) removeChip()
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
                            markChipReady(act, bmp)
                            Toast.makeText(
                                act, I18n.t(act, R.string.ig_instant_upload_ready),
                                Toast.LENGTH_LONG
                            ).show()
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

        private const val VM_CLASS =
            "com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel"

        private const val PICK_REQUEST = 0x1E5A

        @Volatile
        @JvmField
        internal var pendingBitmap: Bitmap? = null

        @Volatile
        private var pendingSetAt = 0L

        private const val PENDING_TTL_MS = 5L * 60 * 1000

        private var chip: TextView? = null

        private var preview: ImageView? = null

        private var chipHost: Activity? = null

        private var lifecycleRegistered = false

        private const val MAX_EDGE = 1440

        private fun markChipReady(act: Activity, bmp: Bitmap) {
            try {
                val decor = act.window.decorView
                if (decor is ViewGroup && preview == null) {
                    val iv = ImageView(act)
                    iv.setImageBitmap(bmp)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    val side = dp(act, 84)
                    val lp = FrameLayout.LayoutParams(side, side)
                    lp.gravity = Gravity.BOTTOM or Gravity.START
                    lp.leftMargin = dp(act, 20)
                    lp.bottomMargin = dp(act, 172)
                    iv.layoutParams = lp
                    val frame = GradientDrawable()
                    frame.setColor(Color.BLACK)
                    frame.cornerRadius = dp(act, 12).toFloat()
                    frame.setStroke(dp(act, 2), Color.parseColor("#30D158"))
                    iv.background = frame
                    iv.clipToOutline = true
                    decor.addView(iv)
                    preview = iv
                }
                chip?.let { c ->
                    c.text = I18n.t(act, R.string.ig_instant_upload_chip_ready)
                    val bg = GradientDrawable()
                    bg.setColor(Color.parseColor("#CC30D158"))
                    bg.cornerRadius = dp(act, 22).toFloat()
                    c.background = bg
                    c.bringToFront()
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun removeChip() {
            try {
                val c = chip
                if (c != null && c.parent is ViewGroup) (c.parent as ViewGroup).removeView(c)
            } catch (ignored: Throwable) {
            }
            try {
                val p = preview
                if (p != null && p.parent is ViewGroup) (p.parent as ViewGroup).removeView(p)
            } catch (ignored: Throwable) {
            }
            chip = null
            preview = null
            chipHost = null
        }

        private fun injectChip(act: Activity) {
            try {
                if (!act.javaClass.name.contains("ModalActivity")) return
                if (chip != null && chipHost === act) return
                removeChip()

                val decor = act.window.decorView
                if (decor !is ViewGroup) return

                val c = TextView(act)
                c.text = I18n.t(act, R.string.ig_instant_upload_chip)
                c.setTextColor(Color.WHITE)
                c.textSize = 14f
                val padH = dp(act, 16)
                val padV = dp(act, 10)
                c.setPadding(padH, padV, padH, padV)
                val bg = GradientDrawable()
                bg.setColor(Color.parseColor("#CC0A84FF"))
                bg.cornerRadius = dp(act, 22).toFloat()
                c.background = bg

                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.leftMargin = dp(act, 20)
                lp.bottomMargin = dp(act, 120)
                c.layoutParams = lp
                c.setOnClickListener { launchPicker(act) }

                decor.addView(c)
                chip = c
                chipHost = act
                ModuleLog.line("(NA|InstantUpload) ✅ gallery chip injected")
            } catch (t: Throwable) {
                ModuleLog.line("(NA|InstantUpload) ❌ chip: $t")
            }
        }

        private fun launchPicker(act: Activity) {
            try {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                i.addCategory(Intent.CATEGORY_OPENABLE)
                act.startActivityForResult(
                    Intent.createChooser(i, I18n.t(act, R.string.ig_instant_upload_chip)),
                    PICK_REQUEST
                )
            } catch (t: Throwable) {
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

        @Suppress("UNCHECKED_CAST")
        internal fun currentActivity(): Activity? {
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
                        return af.get(record) as Activity?
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }
}
