package io.github.nexalloy.morphe.twitter

import io.github.nexalloy.Patch
import io.github.nexalloy.morphe.twitter.ads.timelineEntryHook.HideAds
import io.github.nexalloy.morphe.twitter.ads.timelineEntryHook.HideRecommendationItems
import io.github.nexalloy.morphe.twitter.link.unshorten.NoShortenedUrl
import io.github.nexalloy.morphe.twitter.misc.blur.DisableBlur
import io.github.nexalloy.morphe.twitter.timeline.forceTranslate.ForceTranslate
import io.github.nexalloy.morphe.twitter.timeline.removePremiumUpsell.RemovePremiumUpsell
import io.github.nexalloy.morphe.twitter.timeline.showpollresults.ShowPollResults
import io.github.nexalloy.morphe.twitter.timeline.banner.HideBanner

val TwitterPatches: Array<Patch> = arrayOf(
    // Ads / recommendations
    HideAds,
    HideRecommendationItems,

    // Links
    NoShortenedUrl,

    // Premium
    RemovePremiumUpsell,

    // Timeline / video
    ForceTranslate,
    ShowPollResults,
    HideBanner,

    // Appearance
    DisableBlur,
)
