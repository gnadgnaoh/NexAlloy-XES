package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexMethod

val DisableTelemetry = patch(
    name = "Disable telemetry",
    description = "Blocks all telemetry, analytics, and observability data collection.",
) {
    val hooked = mutableSetOf<String>()

    fun hookOnce(method: DexMethod, replacement: XC_MethodReplacement) {
        if (hooked.add(method.toString())) method.hookMethod(replacement)
    }

    fun optional(what: String, block: () -> Unit) = runCatching(block).onFailure { e ->
        Logger.printInfo { "Proton VPN telemetry: $what not hooked: $e" }
    }

    val doNothing = XC_MethodReplacement.returnConstant(null)
    val returnUnit = XC_MethodReplacement.returnConstant(Unit)

    // TelemetryManager.enqueue (suspend, marked by "core.telemetry.enqueue") is the single entry point.
    hookOnce(::coreTelemetryEnqueueFingerprint.dexMethod, returnUnit)
    hookOnce(::vpnTelemetryEventFingerprint.dexMethod, returnUnit)
    hookOnce(::observabilityUploadFingerprint.dexMethod, returnUnit)
}
