package io.github.nexalloy.morphe.tiktok.screencapture

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch

private const val TAG = "[TikTok screenshots]"
private const val SECURE = WindowManager.LayoutParams.FLAG_SECURE
private const val PAID_LIVE_COURSE_SETTING =
    "com.bytedance.android.livesdk.comp.api.pcs.data.setting.LivePcsCourseVideoAntiScreenshotSetting"

/*
 * Ported from HushFeed "Allow screenshots and Circle to Search" and kveld "Bypass Screen Capture
 * Detection" (both GPL-3.0).
 *
 * Both rewrite TikTok's own Window.addFlags/setFlags/setAttributes call sites, and kveld also
 * flips the flag inside AntiScreenRecordController, makeScreenProtection and the paid LIVE course
 * setting. Every one of those ends in the same few Android calls, so here the Android methods
 * themselves are hooked instead: no TikTok class or method name is involved, nothing has to be
 * found again after an update, and a new protected screen is covered without a new fingerprint.
 *
 * WindowManagerImpl.addView/updateViewLayout is the last gate every window passes through
 * (activities, dialogs, popups, overlays), so a FLAG_SECURE that reached the window attributes
 * some other way is still taken off before the window is shown or updated.
 */
val AllowScreenshots = patch(
    name = "Allow screenshots everywhere",
    description = "Takes FLAG_SECURE off every TikTok window so screenshots and screen recordings " +
        "work on protected screens too (paid LIVE, payment and verification pages, ...), and " +
        "lifts the Circle to Search block on the feed.",
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    val int = Int::class.javaPrimitiveType!!

    // addFlags(f) is setFlags(f, f) in AOSP, but the boot image may have that call inlined, so both.
    optional("Window.addFlags") {
        Window::class.java.getDeclaredMethod("addFlags", int).hookMethod {
            before { param ->
                val flags = param.args[0] as Int
                if (flags and SECURE != 0) param.args[0] = flags and SECURE.inv()
            }
        }
    }

    // Also widens the mask, so any setFlags call clears a FLAG_SECURE set some other way.
    optional("Window.setFlags") {
        Window::class.java.getDeclaredMethod("setFlags", int, int).hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and SECURE.inv()
                param.args[1] = (param.args[1] as Int) or SECURE
            }
        }
    }

    optional("Window.setAttributes") {
        Window::class.java.getDeclaredMethod("setAttributes", WindowManager.LayoutParams::class.java).hookMethod {
            before { param -> (param.args[0] as? WindowManager.LayoutParams)?.clearSecure() }
        }
    }

    optional("WindowManagerImpl") {
        val impl = Class.forName("android.view.WindowManagerImpl")
        listOf("addView", "updateViewLayout").forEach { name ->
            impl.getDeclaredMethod(name, View::class.java, ViewGroup.LayoutParams::class.java).hookMethod {
                before { param -> (param.args[1] as? WindowManager.LayoutParams)?.clearSecure() }
            }
        }
    }

    check(installed.isNotEmpty()) { "FLAG_SECURE could not be hooked: ${skipped.joinToString("; ")}" }

    // kveld: the paid LIVE course setting that asks for the anti-screenshot treatment at all.
    optional("paidLiveCourseSetting") {
        val setting = PAID_LIVE_COURSE_SETTING.findClassOrNull(classLoader) ?: error("$PAID_LIVE_COURSE_SETTING not found")
        setting.getDeclaredMethod("getValue").hookMethod(XC_MethodReplacement.returnConstant(false))
    }

    optional("circleSearchBlock") {
        val getters = ::circleSearchBlockSettingFingerprints.dexMethodList.realMatches()
        check(getters.isNotEmpty()) { "circle_search_block setting not found" }
        getters.forEach { getter ->
            getter.toMethod().hookMethod {
                after { param ->
                    if (param.result is Int && param.result != 0) param.result = 0
                }
            }
        }
    }

    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}

private fun WindowManager.LayoutParams.clearSecure() {
    if (flags and SECURE != 0) flags = flags and SECURE.inv()
}
