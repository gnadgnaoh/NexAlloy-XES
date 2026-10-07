package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookBannerBoolean
import io.github.nexalloy.revanced.facebook.hookBlockNull

val AggressiveAdBlocking = patch(
    name = "Aggressive banner & ad-break blocking",
    description = "Broad upstream hooks: ad-break state machine and banner-ad boolean sweep. May break some screens; off by default.",
    use = false,
) {
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }
    val adBreak = runCatching { ::adBreakStateMachineMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
    val banner = runCatching { ::bannerBooleanMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
    adBreak.forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }
    banner.forEach { dm -> runCatching { hookBannerBoolean(dm.toMethod()) } }
}
