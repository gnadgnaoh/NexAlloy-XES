package io.github.nexalloy.v4n1x.morphe.soundcloud.analytics

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val DisableAnalytics = patch(
    name = "Disable analytics",
    description = "Disables SoundCloud's analytics.",
) {
    HandleMessageFingerprint.memberOrNull?.hookMethod(XC_MethodReplacement.DO_NOTHING)
}
