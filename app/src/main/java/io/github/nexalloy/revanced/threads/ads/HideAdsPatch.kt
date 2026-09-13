package io.github.nexalloy.revanced.threads.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Hides injected ads, sponsored content, and paid partnership posts in Threads feed."
) {
    ::adFetchSponsoredContentFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::adContentDeliveredFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::paidPartnershipLabelFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::adMetadataFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::sponsoredLabelInHeaderFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::spoolAdInjectorFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
}
