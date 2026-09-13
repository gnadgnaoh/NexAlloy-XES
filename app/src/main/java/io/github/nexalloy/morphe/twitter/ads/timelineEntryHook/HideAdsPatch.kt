package io.github.nexalloy.morphe.twitter.ads.timelineEntryHook

import io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch.FeatureFlagHook
import io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch.featureFlagOverrides
import io.github.nexalloy.patch

private val sspAdFlags = listOf(
    "ssp_ads_home_enabled",
    "ssp_ads_tweet_details",
    "ssp_ads_profile",
    "ssp_ads_immersive",
    "ssp_ads_spotlight",
    "ssp_ads_home_client_only_integration",
    "ssp_ads_tweet_details_client_only_integration",
    "ssp_ads_profile_client_only_integration_enabled",
    "ssp_ads_immersive_client_only_integration",
    "ssp_ads_spotlight_client_only_integration",
    "ssp_ads_dsp_client_context_enabled",
)

val HideAds = patch(
    name = "Remove ads",
    description = "Removes promoted posts, trends and Google ads.",
) {
    dependsOn(TimelineEntryHook, FeatureFlagHook)
    hideAdsEnabled = true
    for (flag in sspAdFlags) {
        featureFlagOverrides[flag] = false
    }
}
