package io.github.nexalloy.morphe.tiktok.ads

import android.app.Activity
import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.morphe.tiktok.shared.realMatches

private const val TAG = "[TikTok splash]"

private const val SPLASH_PACKAGE = "com.bytedance.ies.ugc.aweme.commercialize.splash"

private val SPLASH_TASK_CLASSES = listOf(
    "$SPLASH_PACKAGE.SplashAdManagerPreloadTask",
    "$SPLASH_PACKAGE.SplashAdManagerPreloadTaskEntry",
    "$SPLASH_PACKAGE.topview.TopViewPreloadTask",
    "$SPLASH_PACKAGE.topview.TopViewPreloadJsonTask",
    "$SPLASH_PACKAGE.topview.RealTimeSplashTask",
)

private const val NORMAL_SPLASH_ACTIVITY = "$SPLASH_PACKAGE.show.NormalSplashAdActivity"

val SkipSplashAds = patch(
    name = "Skip splash ads",
    description = "Skips the full-screen splash and TopView ads shown when TikTok opens or returns " +
            "from the background, and stops TikTok downloading them.",
) {
    run { // showDecision
        val decisions = ::splashShowDecisionFingerprints.dexMethodList.realMatches()
        check(decisions.isNotEmpty()) { "splash show decision not found" }
        decisions.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }
    }

    // Tên class viết cứng, bản nào có thì hook → dò bằng findClassOrNull; cần ít nhất 1.
    val splashTasks = SPLASH_TASK_CLASSES.mapNotNull { className ->
        className.findClassOrNull(classLoader)?.declaredMethods
            ?.firstOrNull { it.name == "run" && it.parameterTypes.contentEquals(arrayOf<Class<*>>(Context::class.java)) }
    }
    check(splashTasks.isNotEmpty()) { "none of the ${SPLASH_TASK_CLASSES.size} splash tasks found" }
    splashTasks.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }

    run { // splashActivity
        val activity = NORMAL_SPLASH_ACTIVITY.findClassOrNull(classLoader) ?: error("class not found")
        activity.getDeclaredMethod("onCreate", android.os.Bundle::class.java).hookMethod {
            after { param -> (param.thisObject as? Activity)?.finish() }
        }
    }

}
