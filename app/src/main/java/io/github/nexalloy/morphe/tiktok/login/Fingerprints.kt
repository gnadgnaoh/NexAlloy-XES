package io.github.nexalloy.morphe.tiktok.login

import io.github.nexalloy.morphe.findMethodListDirect

internal const val GOOGLE_AUTH_CLASS = "com.bytedance.lobby.google.GoogleAuth"
internal const val MANDATORY_LOGIN_SERVICE_CLASS = "com.ss.android.ugc.aweme.services.MandatoryLoginService"
internal val MANDATORY_LOGIN_GATES = setOf("enableForcedLogin", "shouldShowForcedLogin")

/** `enableForcedLogin(boolean)` / `shouldShowForcedLogin(boolean)` of every IMandatoryLoginService. */
val mandatoryLoginGatesFingerprint = findMethodListDirect {
    findClass {
        matcher { addInterface("com.ss.android.ugc.aweme.IMandatoryLoginService") }
    }.findMethod {
        matcher {
            returnType = "boolean"
            paramTypes("boolean")
        }
    }.filter { it.name in MANDATORY_LOGIN_GATES }
}
