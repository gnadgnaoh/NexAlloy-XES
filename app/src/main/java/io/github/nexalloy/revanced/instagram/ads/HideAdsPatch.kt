package io.github.nexalloy.revanced.instagram.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Hides injected ads, sponsored content, paid partnership, and Reels/Stories ads.",
) {
    ::feedAcpContentInjectorFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::adInsertGateFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
}
