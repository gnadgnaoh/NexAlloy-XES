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

    (::telemetryWorkerEnqueueFingerprints.dexMethodList +
        runCatching { ::coreTelemetryUploadSchedulerFingerprints.dexMethodList }.getOrDefault(emptyList()))
        .ifEmpty { throw Exception("No core telemetry scheduler found") }
        .forEach { hookOnce(it, doNothing) }
    ::vpnTelemetryAddEventFingerprint.dexMethod.let { hookOnce(it, returnUnit) }
    optional("VpnTelemetry event (Morphe fingerprint)") { hookOnce(::vpnTelemetryEventFingerprint.dexMethod, returnUnit) }
    ::sendObservabilityFingerprint.dexMethod.let { hookOnce(it, returnUnit) }
    optional("observability upload (Morphe fingerprint)") { hookOnce(::observabilityUploadFingerprint.dexMethod, returnUnit) }
    optional("IsObservabilityEnabled") {
        hookOnce(::observabilityEnabledFingerprint.dexMethod, XC_MethodReplacement.returnConstant(false))
    }
    optional("payments observability worker") { hookOnce(::paymentsObservabilityWorkerFingerprint.dexMethod, doNothing) }
}
