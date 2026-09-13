package io.github.nexalloy.revanced.shared.tigon

import io.github.nexalloy.morphe.Fingerprint

/** Network layer shared by Instagram and Threads; every API request goes through it. */
internal object TigonServiceLayerStartRequestFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/api/tigon/TigonServiceLayer;",
    name = "startRequest",
)
