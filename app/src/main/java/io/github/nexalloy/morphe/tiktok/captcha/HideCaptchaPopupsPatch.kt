package io.github.nexalloy.morphe.tiktok.captcha

import android.app.Activity
import app.morphe.extension.shared.Logger
import java.lang.reflect.Method
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val TAG = "[TikTok captcha]"
private const val BD_TURING_CALLBACK = "com.tts.oecverify.BdTuringCallback"

/** Index of the first parameter accepted by [accepts]; arguments are never taken by position. */
private fun Method.argIndex(role: String, accepts: (Class<*>) -> Boolean): Int =
    parameterTypes.indexOfFirst(accepts).takeIf { it >= 0 }
        ?: throw IllegalStateException("$name: no parameter for $role in ${parameterTypes.joinToString { it.name }}")

val HideCaptchaPopups = patch(
    name = "Hide CAPTCHA popups",
    description = "Skips browsing puzzle dialogs and reports them as closed. Account verification " +
            "(SMS, e-mail, password, identity) and login flows are left alone. The action that " +
            "triggered the puzzle will usually fail instead.",
    use = false,
) {
    TikTokServices.init(classLoader)

    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    // Without the listener callbacks a suppressed dialog could not answer the caller, and the
    // caller would wait for minutes; the Sec hooks below are only installed once they resolve.
    optional("closeCallbacks") {
        CaptchaSuppressor.init(::secCaptchaCloseCallbacksFingerprint.dexMethodList.map { it.toMethod() })
        check(CaptchaSuppressor.canCloseSecCaptcha()) { "SecCaptcha close callbacks not found" }
    }

    // The Sec listener type is the class declaring the close callbacks resolved above.
    val listenerClass: Class<*>? = runCatching {
        ::secCaptchaCloseCallbacksFingerprint.dexMethodList.first().toMethod().declaringClass
    }.getOrNull()
    fun Method.listenerArg() = argIndex("listener") { type ->
        type != Any::class.java && listenerClass != null && type.isAssignableFrom(listenerClass)
    }
    fun Method.activityArg() = argIndex("activity") { Activity::class.java.isAssignableFrom(it) }

    // Covers LIVE as well: its host implementation wraps the listener and calls the same API.
    if (CaptchaSuppressor.canCloseSecCaptcha()) optional("popCaptchaV2") {
        val method = PopCaptchaV2Fingerprint.method
        val activity = method.activityArg()
        val riskInfo = method.argIndex("riskInfo") { it == String::class.java }
        val listener = method.listenerArg()
        method.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleRiskInfoCaptcha(param.args[activity], param.args[riskInfo], param.args[listener])) {
                    param.result = null
                }
            }
        }
    }

    if (CaptchaSuppressor.canCloseSecCaptcha()) optional("popCaptcha") {
        val method = PopCaptchaFingerprint.method
        val activity = method.activityArg()
        val listener = method.listenerArg()
        method.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleLegacyCaptcha(param.args[activity], param.args[listener])) {
                    param.result = null
                }
            }
        }
    }

    // TikTok Shop / oec verification: a second SDK with its own dialog and its own callback.
    // Independent of the Sec listener above; it answers through BdTuringCallback instead.
    optional("oecVerification") {
        val method = OecRiskControlExecuteFingerprint.method
        val callback = method.argIndex("callback") { it.name == BD_TURING_CALLBACK }
        val request = method.argIndex("request") { it.name != BD_TURING_CALLBACK }
        method.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleVerifyRequest(param.args[request], param.args[callback])) {
                    param.result = true
                }
            }
        }
    }

    check(installed.isNotEmpty()) { "no captcha entry point could be hooked: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
