package io.github.nexalloy.morphe.tiktok.captcha

import android.app.Activity
import java.lang.reflect.Method
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val TAG = "[TikTok captcha]"
private const val BD_TURING_CALLBACK = "com.tts.oecverify.BdTuringCallback"

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

    run { // closeCallbacks
        CaptchaSuppressor.init(::secCaptchaCloseCallbacksFingerprint.dexMethodList.map { it.toMethod() })
        check(CaptchaSuppressor.canCloseSecCaptcha()) { "SecCaptcha close callbacks not found" }
    }

    val listenerClass: Class<*>? = ::secCaptchaCloseCallbacksFingerprint.dexMethodList.first().toMethod().declaringClass
    fun Method.listenerArg() = argIndex("listener") { type ->
        type != Any::class.java && listenerClass != null && type.isAssignableFrom(listenerClass)
    }
    fun Method.activityArg() = argIndex("activity") { Activity::class.java.isAssignableFrom(it) }

    if (CaptchaSuppressor.canCloseSecCaptcha()) run { // popCaptchaV2
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

    if (CaptchaSuppressor.canCloseSecCaptcha()) run { // popCaptcha
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

    run { // oecVerification
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

}
