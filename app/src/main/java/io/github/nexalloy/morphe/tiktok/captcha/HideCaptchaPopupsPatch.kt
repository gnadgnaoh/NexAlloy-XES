package io.github.nexalloy.morphe.tiktok.captcha

import app.morphe.extension.shared.Logger
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.patch

private const val TAG = "[TikTok captcha]"

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

    // Covers LIVE as well: its host implementation wraps the listener and calls the same API.
    if (CaptchaSuppressor.canCloseSecCaptcha()) optional("popCaptchaV2") {
        PopCaptchaV2Fingerprint.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleRiskInfoCaptcha(param.args[0], param.args[1], param.args[2])) {
                    param.result = null
                }
            }
        }
    }

    if (CaptchaSuppressor.canCloseSecCaptcha()) optional("popCaptcha") {
        PopCaptchaFingerprint.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleLegacyCaptcha(param.args[0], param.args[2])) {
                    param.result = null
                }
            }
        }
    }

    // TikTok Shop / oec verification: a second SDK with its own dialog and its own callback.
    // Independent of the Sec listener above; it answers through BdTuringCallback instead.
    optional("oecVerification") {
        OecRiskControlExecuteFingerprint.hookMethod {
            before { param ->
                if (CaptchaSuppressor.handleVerifyRequest(param.args[0], param.args[1])) {
                    param.result = true
                }
            }
        }
    }

    check(installed.isNotEmpty()) { "no captcha entry point could be hooked: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
