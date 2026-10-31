package io.github.nexalloy.revanced.zalo.tracking

import io.github.nexalloy.morphe.fingerprint

val adsTrackingReceiverFingerprint = fingerprint {
    name("onReceive")
    strings(
        "com.zing.zalo.ads.receiver.AdsTrackingManager.HitUrls",
        "com.zing.zalo.ads.receiver.AdsTrackingManager.SubmitBatch",
    )
    parameters("Landroid/content/Context;", "Landroid/content/Intent;")
    returns("V")
}
