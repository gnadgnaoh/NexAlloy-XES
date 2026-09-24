package io.github.nexalloy.revanced.facebook

import io.github.nexalloy.revanced.facebook.ad.AggressiveAdBlocking
import io.github.nexalloy.revanced.facebook.ad.HideFacebookAds
import io.github.nexalloy.revanced.facebook.ad.HideInstreamAdBreaks
import io.github.nexalloy.revanced.facebook.ad.HideProfileTimelineAds
import io.github.nexalloy.revanced.facebook.ad.HideReelsShopping
import io.github.nexalloy.revanced.facebook.ad.HideSearchAds
import io.github.nexalloy.revanced.facebook.ad.SpoofAdFreeSession

val FacebookPatches = arrayOf(
    HideFacebookAds,
    HideInstreamAdBreaks,
    HideProfileTimelineAds,
    HideSearchAds,
    SpoofAdFreeSession,
    HideReelsShopping,
    AggressiveAdBlocking,
)
