package io.github.nexalloy.hoodles.morphe.protonvpn

import io.github.nexalloy.hoodles.morphe.protonvpn.delay.RemoveChangeServerDelay
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.UnlockVpnPlus
import io.github.nexalloy.hoodles.morphe.protonvpn.telemetry.DisableTelemetry

val ProtonVpnPatches = arrayOf(
    UnlockVpnPlus,
    RemoveChangeServerDelay,
    DisableTelemetry,
)
