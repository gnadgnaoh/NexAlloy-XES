package io.github.nexalloy.morphe.tiktok.ads

import android.app.Activity
import android.content.Context
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val TAG = "[TikTok splash]"

private const val SPLASH_PACKAGE = "com.bytedance.ies.ugc.aweme.commercialize.splash"

/**
 * Background tasks that fetch splash and TopView material, all registered by name (Lego tasks), so
 * the classes and their `run(Context)` keep their names. Some live in the df_a_dex split.
 */
private val SPLASH_TASK_CLASSES = listOf(
    "$SPLASH_PACKAGE.SplashAdManagerPreloadTask",
    "$SPLASH_PACKAGE.SplashAdManagerPreloadTaskEntry",
    "$SPLASH_PACKAGE.topview.TopViewPreloadTask",
    "$SPLASH_PACKAGE.topview.TopViewPreloadJsonTask",
    "$SPLASH_PACKAGE.topview.RealTimeSplashTask",
)

private const val NORMAL_SPLASH_ACTIVITY = "$SPLASH_PACKAGE.show.NormalSplashAdActivity"

/**
 * The full-screen ad TikTok shows when it starts or comes back from the background: a splash
 * image/video, or a TopView that turns into the first For You video. None of it goes through the
 * feed list, so [RemoveFeedAds] never sees it.
 *
 *  1. The show manager's decision answers "no splash" for cold and warm starts alike.
 *  2. The tasks that download splash / TopView material and request real-time splashes do
 *     nothing, so no ad is cached for later and no data is spent on it.
 *  3. Safety net: the stand-alone splash activity closes itself if it is still started.
 */
val SkipSplashAds = patch(
    name = "Skip splash ads",
    description = "Skips the full-screen splash and TopView ads shown when TikTok opens or returns " +
            "from the background, and stops TikTok downloading them.",
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    optional("showDecision") {
        val decisions = ::splashShowDecisionFingerprints.dexMethodList.realMatches()
        check(decisions.isNotEmpty()) { "splash show decision not found" }
        decisions.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }
    }

    SPLASH_TASK_CLASSES.forEach { className ->
        optional(className.substringAfterLast('.')) {
            val task = className.findClassOrNull(classLoader) ?: error("class not found")
            task.getDeclaredMethod("run", Context::class.java)
                .hookMethod(XC_MethodReplacement.DO_NOTHING)
        }
    }

    optional("splashActivity") {
        val activity = NORMAL_SPLASH_ACTIVITY.findClassOrNull(classLoader) ?: error("class not found")
        activity.getDeclaredMethod("onCreate", android.os.Bundle::class.java).hookMethod {
            after { param -> (param.thisObject as? Activity)?.finish() }
        }
    }

    check(installed.isNotEmpty()) { "no splash hook could be installed: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
