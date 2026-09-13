package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.AccessFlags

internal object TelemetryWorkerEnqueueFingerprint : Fingerprint(
    definingClass = "Lme/proton/core/telemetry/data/worker/TelemetryWorkerManagerImpl;",
    name = "enqueueOrKeep-HG0u8IE",
    returnType = "V",
)

internal object SendObservabilityFingerprint : Fingerprint(
    definingClass = "Lme/proton/core/observability/data/usecase/SendObservabilityEventsImpl;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/util/List;",
        "Lkotlin/coroutines/Continuation;",
    ),
)

internal object VpnTelemetryAddEventFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/protonvpn/android/telemetry/TelemetryEvent;",
        "Z",
        "Lkotlin/coroutines/Continuation;",
    ),
)
