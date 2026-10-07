package io.github.nexalloy.morphe.tiktok.screencapture

import android.app.Activity
import android.content.Context
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch

private const val TAG = "[TikTok screen capture]"

private val SCREENSHOT_TASKS = listOf(
    "com.ss.android.ugc.aweme.legoImpl.task.ScreenShotTask",
    "com.ss.android.ugc.aweme.legoImp.task.ScreenShotTaskHolder\$BootFinish",
    "com.ss.android.ugc.aweme.legoImpl.task.ScreenShotFeedbackTask",
    "com.ss.android.ugc.aweme.legoImp.task.ScreenShotFeedbackTaskHolder\$BootFinish",
    "com.ss.android.ugc.aweme.legoImp.task.ScreenRecordingMonitorInitTask",
    "com.ss.android.ugc.aweme.im.sharepanel.impl.screenshotshare.InternalShareScreenshotTask",
    "com.ss.android.ugc.aweme.im.sharepanel.impl.screenshotshare.InternalShareScreenshotTaskHolder\$BootFinish",
)

private const val SCREENSHOT_FEEDBACK_SERVICE =
    "com.ss.android.ugc.aweme.feedback.screenshot.ScreenShotFeedbackService"
private val SCREENSHOT_FEEDBACK_TRIGGERS = setOf("onShot", "tryShowScreenShotFloatingView")

val DisableScreenCaptureDetection = patch(
    name = "Disable screen capture detection",
    description = "Prevents TikTok from reacting to screenshots and screen recordings.",
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    optional("screenCaptureCallback") {
        val methods = Activity::class.java.declaredMethods.filter {
            it.name == "registerScreenCaptureCallback" || it.name == "unregisterScreenCaptureCallback"
        }
        check(methods.isNotEmpty()) { "Activity has no screen capture callback API" }
        methods.forEach { method ->
            method.hookMethod {
                before { param -> param.result = null }
            }
        }
    }

    optional("clearModeDisplayListener") {
        val listeners = ::clearModeDisplayListenerFingerprints.dexMethodList.realMatches()
        check(listeners.isNotEmpty()) { "ClearModePanelComponent display listener not found" }
        listeners.forEach { it.toMethod().hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

    optional("screenshotTasks") {
        val hooked = SCREENSHOT_TASKS.count { name ->
            val task = name.findClassOrNull(classLoader) ?: return@count false
            val run = runCatching { task.getDeclaredMethod("run", Context::class.java) }.getOrNull()
                ?: return@count false
            run.hookMethod(XC_MethodReplacement.DO_NOTHING)
            true
        }
        check(hooked > 0) { "none of the ${SCREENSHOT_TASKS.size} screenshot tasks found" }
    }

    optional("screenshotFeedback") {
        val service = SCREENSHOT_FEEDBACK_SERVICE.findClassOrNull(classLoader)
            ?: error("$SCREENSHOT_FEEDBACK_SERVICE not found")
        val methods = service.declaredMethods.filter {
            it.name in SCREENSHOT_FEEDBACK_TRIGGERS && it.returnType == Boolean::class.javaPrimitiveType
        }
        check(methods.isNotEmpty()) { "no screenshot feedback trigger found" }
        methods.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }
    }

    check(installed.isNotEmpty()) { "no screen capture detection could be disabled: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
