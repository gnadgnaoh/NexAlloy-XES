package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookBannerBoolean
import io.github.nexalloy.revanced.facebook.hookBlockNull

val AggressiveAdBlocking = patch(
    name = "Aggressive banner & ad-break blocking",
    description = "Broad upstream hooks: ad-break state machine and banner-ad boolean sweep. May break some screens; off by default.",
    use = false,
) {
    ::sponsoredPoolAddMethodFingerprint.method

    val adBreak = ::adBreakStateMachineMethodsFingerprint.dexMethodList
    val banner = ::bannerBooleanMethodsFingerprint.dexMethodList
    check(adBreak.isNotEmpty() || banner.isNotEmpty()) { "no ad-break or banner method found" }
    adBreak.forEach { dm -> hookBlockNull(dm.toMethod()) }
    banner.forEach { dm -> hookBannerBoolean(dm.toMethod()) }
}
