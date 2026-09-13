package io.github.nexalloy.morphe.twitter.timeline.removePremiumUpsell

import io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch.FeatureFlagHook
import io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch.featureFlagOverrides
import io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook.TweetInfoHook
import io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook.removePremiumUpsellStateEnabled
import io.github.nexalloy.patch

private val upsellFlags = listOf(
    "subscriptions_enabled",
    "subscriptions_upsells_api_enabled",
    "subscriptions_upsells_premium_home_nav",
    "subscriptions_upsells_get_verified_profile",
    "subscriptions_upsells_get_verified_profile_card",
    "subscriptions_upsells_get_verified_drawer_card_enabled",
    "subscriptions_upsells_profile_card_enabled",
    "subscriptions_upsells_analytics_profile_enabled",
    "subscriptions_upsells_bookmark_folders_enabled",
    "subscriptions_upsells_quick_display_settings",
    "subscriptions_upsells_track_interactions_enabled",
    "subscriptions_upsells_home_nav_migration_enabled",
)

val RemovePremiumUpsell = patch(
    name = "Remove premium upsell",
    description = "Removes premium upsells.",
) {
    dependsOn(FeatureFlagHook, TweetInfoHook)

    for (flag in upsellFlags) {
        featureFlagOverrides[flag] = false
    }
    removePremiumUpsellStateEnabled = true
}
