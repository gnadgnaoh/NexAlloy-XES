package io.github.nexalloy.revanced.zalo.adtima

import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.revanced.zalo.AdtimaClasses

val zAdsNativeLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_NATIVE)
    name("loadAds")
    parameters("Ljava/lang/String;")
}

val zAdsBundlePreloadFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_BUNDLE)
    name("preloadAds")
    parameters("Ljava/lang/String;")
}

val zAdsBannerLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_BANNER)
    name("loadAds")
    parameters("Ljava/lang/String;", "Ljava/lang/String;")
}

val zAdsInterstitialLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_INTERSTITIAL)
    name("loadAds")
    parameters("Ljava/lang/String;", "Ljava/lang/String;")
}

val zAdsIncentivizedLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_INCENTIVIZED)
    name("loadAds")
    parameters("Ljava/lang/String;")
}

val zAdsVideoLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_VIDEO)
    name("loadAds")
    parameters("Ljava/lang/String;")
}

val zAdsAudioLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_AUDIO)
    name("loadAds")
    parameters("Ljava/lang/String;")
}

val zAdsVideoRollOneLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_VIDEO_ROLL_ONE)
    name("loadAds")
    parameters("Ljava/lang/String;")
}

val zAdsVideoRollLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_VIDEO_ROLL)
    name("loadAds")
    parameters()
}

val zAdsVideoSuiteLoadAdsFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_VIDEO_SUITE)
    name("loadAds")
    parameters()
}

val zAdsTrackingCheckInventoryFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_TRACKING)
    name("checkIfHaveInventory")
    parameters("Ljava/lang/String;")
}

val zAdsTrackingCheckInventoryListFingerprint = fingerprint {
    definingClass(AdtimaClasses.ZADS_TRACKING)
    name("checkIfHaveInventory")
    parameters("Ljava/util/ArrayList;")
}
