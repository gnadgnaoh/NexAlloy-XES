package io.github.nexalloy.revanced.facebook

import io.github.nexalloy.revanced.facebook.ad.HideFacebookAds
import io.github.nexalloy.revanced.facebook.ad.HideInstreamAdBreaks
import io.github.nexalloy.revanced.facebook.ad.HideProfileTimelineAds
import io.github.nexalloy.revanced.facebook.ad.HideSearchAds

val FacebookPatches = arrayOf(
    HideFacebookAds,
    HideInstreamAdBreaks,
    HideProfileTimelineAds,
    HideSearchAds,
)
