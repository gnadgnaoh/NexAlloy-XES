package io.github.nexalloy.morphe.tiktok.login

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClass
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val FixGoogleLogin = patch(
    name = "Fix Google login",
    description = "Forces TikTok's web-based Google sign-in. Only needed when the APK was re-signed " +
            "(LSPatch or a repackaged build), where Play Services rejects the native flow.",
    use = false,
) {
    GOOGLE_AUTH_CLASS.findClass(classLoader)
        .getDeclaredMethod("isAvailable")
        .hookMethod(XC_MethodReplacement.returnConstant(false))
}
