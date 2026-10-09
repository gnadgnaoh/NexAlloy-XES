package io.github.nexalloy.hoodles.morphe.protonvpn.delay

import io.github.nexalloy.morphe.Fingerprint

internal object GetLongDelayFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponse;",
    name = "getChangeServerLongDelayInSeconds",
    returnType = "I",
)

internal object GetLongDelayLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponseLegacyStorage;",
    name = "getChangeServerLongDelayInSeconds",
    returnType = "I",
)

internal object GetShortDelayFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponse;",
    name = "getChangeServerShortDelayInSeconds",
    returnType = "I",
)

internal object GetShortDelayLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponseLegacyStorage;",
    name = "getChangeServerShortDelayInSeconds",
    returnType = "I",
)
