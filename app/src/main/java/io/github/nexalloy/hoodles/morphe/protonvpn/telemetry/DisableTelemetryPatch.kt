package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val DisableTelemetry = patch(
    name = "Disable telemetry",
    description = "Blocks all telemetry, analytics, and observability data collection.",
) {
    // Enqueue methods are void; the other two are suspend functions returning Unit.
    ::telemetryWorkerEnqueueFingerprints.dexMethodList.forEach {
        it.hookMethod(XC_MethodReplacement.returnConstant(null))
    }
    ::sendObservabilityFingerprint.hookMethod(XC_MethodReplacement.returnConstant(Unit))
    ::vpnTelemetryAddEventFingerprint.hookMethod(XC_MethodReplacement.returnConstant(Unit))
}
