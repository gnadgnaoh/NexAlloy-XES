package io.github.nexalloy.v4n1x.morphe.soundcloud.analytics

import io.github.nexalloy.morphe.Fingerprint

object HandleMessageFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/analytics/base/TrackingHandler;",
    name = "handleMessage",
    returnType = "V",
    parameters = listOf("Landroid/os/Message;"),
)
