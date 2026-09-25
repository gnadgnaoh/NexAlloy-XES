package io.github.nexalloy.revanced.zalo.adtima

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.fingerprint
import java.lang.reflect.Modifier
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

private const val ADTIMA_CLASS = "com.adtima.Adtima"
private const val LAT_CHECK =
    "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;->isLimitAdTrackingEnabled()Z"

val adtimaLimitAdTrackingField = findFieldDirect {
    findField {
        matcher {
            declaredClass = ADTIMA_CLASS
            name = "mIsLat"
            type = "int"
        }
    }.single()
}

val adtimaLimitAdTrackingTaskFingerprint = findMethodDirect {
    val isLat = adtimaLimitAdTrackingField()
    findMethod {
        matcher {
            paramCount = 0
            addInvoke(LAT_CHECK)
            addUsingField(isLat.descriptor)
        }
    }.single()
}

val adtimaSupportNetworkField = findFieldDirect {
    findField {
        matcher {
            declaredClass = ADTIMA_CLASS
            name = "SDK_SUPPORT_NETWORK"
        }
    }.single()
}

val adtimaUpdateSupportNetworkFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = ADTIMA_CLASS
            name = "updateSupportNetwork"
            paramCount = 0
            returnType = "void"
        }
    }.single()
}

val adtimaOfflineWindowFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramCount = 0
            returnType = "boolean"
            usingEqStrings("n")
            addInvoke("Lcom/adtima/Adtima;->e(Ljava/lang/String;Ljava/lang/String;)V")
            addInvoke("Ljava/lang/System;->currentTimeMillis()J")
            addInvoke("Ljava/lang/Long;->longValue()J")
        }
    }.single()
}

val adtimaTrackingGateFingerprint = findMethodDirect {
    val window = adtimaOfflineWindowFingerprint()
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramCount = 0
            returnType = "boolean"
            addInvoke(window.descriptor)
        }
    }.single { it.descriptor != window.descriptor }
}
