package io.github.nexalloy.revanced.threads.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Hides sponsored content in the Threads feed, " +
        "and the ads shown among the replies when you open a post.",
) {
    ::adFetchSponsoredContentFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::permalinkAdResultFingerprint.hookMethod {
        before { param ->
            param.args[0] = emptyList<Any>()
            param.args[1] = 0
        }
    }
}
