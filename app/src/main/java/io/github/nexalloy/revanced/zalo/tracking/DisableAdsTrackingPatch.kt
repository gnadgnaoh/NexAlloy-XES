package io.github.nexalloy.revanced.zalo.tracking

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.adtima.blockAdtimaEntryPoints
import io.github.nexalloy.revanced.zalo.adtima.zAdsTrackingCheckInventoryFingerprint
import io.github.nexalloy.revanced.zalo.adtima.zAdsTrackingCheckInventoryListFingerprint

val DisableAdsTracking = patch(
    name = "Disable ads tracking",
    description = "Blocks Adtima impression/click reporting and the Zalo ads tracking receiver.",
) {
    blockAdtimaEntryPoints(
        "Zalo ads tracking",
        listOf(
            "inventoryCheck" to ::zAdsTrackingCheckInventoryFingerprint,
            "inventoryCheckBatch" to ::zAdsTrackingCheckInventoryListFingerprint,
            "trackingReceiver" to ::adsTrackingReceiverFingerprint,
        ),
    )
}
