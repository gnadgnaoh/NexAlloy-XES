package io.github.nexalloy.morphe.tiktok.captcha

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect

/**
 * SecApi.popCaptchaV2(activity, riskInfo, listener, ...): anchored on its log line only.
 * The parameter list keeps growing (4 params up to 46.x, 5 since 47.1.4), so neither the count
 * nor positions are pinned; the patch resolves the arguments it needs by type.
 */
internal object PopCaptchaV2Fingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("popCaptchaV2 - riskInfo = "),
)

/** SecApi.popCaptcha(activity, errorCode, listener): same, arguments resolved by type. */
internal object PopCaptchaFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("popCaptcha - errorcode = "),
)

val secCaptchaCloseCallbacksFingerprint = findMethodListDirect {
    val onFail = findMethod {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.sec.captcha.SecCaptcha"
            name = "onFail"
        }
    }.single()
    val invokes = onFail.invokes.distinctBy { it.descriptor }
    val result = invokes.first { it.returnTypeName == "void" && it.paramTypeNames == listOf("int", "boolean") }
    val dismiss = invokes.filter {
        it.className == result.className && it.returnTypeName == "void" && it.paramTypeNames.isEmpty()
    }.take(1)
    listOf(result) + dismiss
}

internal object OecRiskControlExecuteFingerprint : Fingerprint(
    definingClass = "Lcom/tts/oecverify/verify/RiskControlService;",
    name = "execute",
    returnType = "Z",
)
