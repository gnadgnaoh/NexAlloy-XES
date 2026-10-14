package io.github.nexalloy.revanced.zalo.adtima

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodFunc
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.patch
import kotlin.reflect.KProperty0

internal fun PatchExecutor.blockAdtimaEntryPoints(
    context: String,
    entryPoints: List<Pair<String, KProperty0<FindMethodFunc>>>,
) {
    val blocked = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    entryPoints.forEach { (label, fingerprint) ->
        runCatching {
            fingerprint.hookMethod {
                before { param -> param.result = null }
            }
        }.onSuccess { blocked += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName})" }
    }

    if (skipped.isNotEmpty()) {
        Logger.printInfo { "[Zalo] $context, not blocked: ${skipped.joinToString()}" }
    }

    check(blocked.isNotEmpty()) {
        "$context: no entry point could be blocked (${skipped.joinToString()})"
    }
}

val DisableAdtimaAdRequests = patch(
    name = "Block Adtima ad requests",
    description = "Stops the bundled Adtima SDK from fetching ad creatives " +
            "(api.adtimaserver.vn/mobad/*) for native, bundle, banner, interstitial and " +
            "rewarded placements.",
) {
    blockAdtimaEntryPoints(
        "Adtima display ads",
        listOf(
            "native" to ::zAdsNativeLoadAdsFingerprint,
            "bundle" to ::zAdsBundlePreloadFingerprint,
            "banner" to ::zAdsBannerLoadAdsFingerprint,
            "interstitial" to ::zAdsInterstitialLoadAdsFingerprint,
            "incentivized" to ::zAdsIncentivizedLoadAdsFingerprint,
        ),
    )
}

val DisableAdtimaVideoAdRequests = patch(
    name = "Block Adtima video ad requests",
    description = "Also blocks pre-roll, mid-roll and audio ad requests. May delay or stall " +
            "playback if Zalo waits for an ad result — turn this off first if video stops starting.",
    use = true,
) {
    blockAdtimaEntryPoints(
        "Adtima video ads",
        listOf(
            "video" to ::zAdsVideoLoadAdsFingerprint,
            "audio" to ::zAdsAudioLoadAdsFingerprint,
            "videoSuite" to ::zAdsVideoSuiteLoadAdsFingerprint,
            "videoRoll" to ::zAdsVideoRollLoadAdsFingerprint,
            "videoRollOne" to ::zAdsVideoRollOneLoadAdsFingerprint,
        ),
    )
}
