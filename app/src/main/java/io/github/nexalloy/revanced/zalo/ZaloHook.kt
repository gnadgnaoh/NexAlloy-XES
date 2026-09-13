package io.github.nexalloy.revanced.zalo

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.zalo.ads.FilterFeedAds
import io.github.nexalloy.revanced.zalo.ads.HideFeedZInstantAds
import io.github.nexalloy.revanced.zalo.ads.HideShortVideoAds
import io.github.nexalloy.revanced.zalo.ads.HideStoryAds
import io.github.nexalloy.revanced.zalo.ads.SkipFeedAdsBinding
import io.github.nexalloy.revanced.zalo.adtima.DisableAdtimaAdRequests
import io.github.nexalloy.revanced.zalo.adtima.DisableAdtimaVideoAdRequests
import io.github.nexalloy.revanced.zalo.tracking.DisableAdsTracking

val ZaloPatches = arrayOf<Patch>(
    HideFeedZInstantAds,
    SkipFeedAdsBinding,
    FilterFeedAds,
    HideStoryAds,
    HideShortVideoAds,
    DisableAdtimaAdRequests,
    DisableAdtimaVideoAdRequests,
    DisableAdsTracking,
)
