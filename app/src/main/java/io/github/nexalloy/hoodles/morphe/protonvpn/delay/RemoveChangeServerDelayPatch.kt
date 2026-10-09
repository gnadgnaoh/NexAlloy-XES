package io.github.nexalloy.hoodles.morphe.protonvpn.delay

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val RemoveChangeServerDelay = patch(
    name = "Remove delay",
    description = "Removes the imposed delay when changing VPN servers.",
) {
    listOf(
        GetLongDelayFingerprint,
        GetLongDelayLegacyFingerprint,
        GetShortDelayFingerprint,
        GetShortDelayLegacyFingerprint,
    ).forEach { it.hookMethod(XC_MethodReplacement.returnConstant(0)) }
}
