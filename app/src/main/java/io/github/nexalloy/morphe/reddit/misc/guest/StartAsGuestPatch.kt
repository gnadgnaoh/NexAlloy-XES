package io.github.nexalloy.morphe.reddit.misc.guest

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val startAsGuestPatch = patch(
    name = "Start as guest",
    description = "Skips the forced startup login screen using Reddit's native guest browsing mode."
) {
    // Force "onboarding complete"
    ::FrontPageApplicationHasFinishedOnboarding.hookMethod(XC_MethodReplacement.returnConstant(true))
}

