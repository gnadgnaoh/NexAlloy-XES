package io.github.nexalloy.hoodles.morphe.protonvpn

import io.github.nexalloy.hoodles.morphe.protonvpn.delay.RemoveChangeServerDelay
import io.github.nexalloy.hoodles.morphe.protonvpn.freeservers.ShowFreeServerLocations
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.UnlockVpnPlus
import io.github.nexalloy.hoodles.morphe.protonvpn.telemetry.DisableTelemetry
import io.github.nexalloy.hoodles.morphe.protonvpn.upselling.HideUpgradePromotions

val ProtonVpnPatches = arrayOf(
    UnlockVpnPlus,
    ShowFreeServerLocations,
    HideUpgradePromotions,
    RemoveChangeServerDelay,
    DisableTelemetry,
)
