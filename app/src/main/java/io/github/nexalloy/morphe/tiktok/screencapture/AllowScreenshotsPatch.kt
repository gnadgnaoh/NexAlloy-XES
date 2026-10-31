package io.github.nexalloy.morphe.tiktok.screencapture

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch

private const val TAG = "[TikTok screenshots]"
private const val SECURE = WindowManager.LayoutParams.FLAG_SECURE
private const val PAID_LIVE_COURSE_SETTING =
    "com.bytedance.android.livesdk.comp.api.pcs.data.setting.LivePcsCourseVideoAntiScreenshotSetting"

val AllowScreenshots = patch(
    name = "Allow screenshots everywhere",
    description = "Takes FLAG_SECURE off every TikTok window so screenshots and screen recordings " +
        "work on protected screens too (paid LIVE, payment and verification pages, ...), and " +
        "lifts the Circle to Search block on the feed.",
) {
    val int = Int::class.javaPrimitiveType!!

    run {
        Window::class.java.getDeclaredMethod("addFlags", int).hookMethod {
            before { param ->
                val flags = param.args[0] as Int
                if (flags and SECURE != 0) param.args[0] = flags and SECURE.inv()
            }
        }
    }

    run {
        Window::class.java.getDeclaredMethod("setFlags", int, int).hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and SECURE.inv()
                param.args[1] = (param.args[1] as Int) or SECURE
            }
        }
    }

    run {
        Window::class.java.getDeclaredMethod("setAttributes", WindowManager.LayoutParams::class.java).hookMethod {
            before { param -> (param.args[0] as? WindowManager.LayoutParams)?.clearSecure() }
        }
    }

    run {
        val impl = Class.forName("android.view.WindowManagerImpl")
        listOf("addView", "updateViewLayout").forEach { name ->
            impl.getDeclaredMethod(name, View::class.java, ViewGroup.LayoutParams::class.java).hookMethod {
                before { param -> (param.args[1] as? WindowManager.LayoutParams)?.clearSecure() }
            }
        }
    }


    run {
        val setting = PAID_LIVE_COURSE_SETTING.findClassOrNull(classLoader) ?: error("$PAID_LIVE_COURSE_SETTING not found")
        setting.getDeclaredMethod("getValue").hookMethod(XC_MethodReplacement.returnConstant(false))
    }

    run {
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

}

private fun WindowManager.LayoutParams.clearSecure() {
    if (flags and SECURE != 0) flags = flags and SECURE.inv()
}
