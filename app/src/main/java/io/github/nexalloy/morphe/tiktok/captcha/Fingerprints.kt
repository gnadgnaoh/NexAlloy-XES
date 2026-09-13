package io.github.nexalloy.morphe.tiktok.captcha

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect

internal object PopCaptchaV2Fingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("popCaptchaV2 - riskInfo = "),
    custom = { paramCount = 4 },
)

internal object PopCaptchaFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("popCaptcha - errorcode = "),
    custom = { paramCount = 3 },
)

val secCaptchaCloseCallbacksFingerprint = findMethodListDirect {
    val onFail = findMethod {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.sec.captcha.SecCaptcha"
            name = "onFail"
            paramTypes("int")
        }
    }.first()
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
    custom = { paramCount = 2 },
)
