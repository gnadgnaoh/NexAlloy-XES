package io.github.nexalloy.revanced.instagram.network

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.tigon.TigonRequestHook
import io.github.nexalloy.revanced.shared.tigon.addTigonRequestRule

val BlockNetwork = patch(
    name = "Block ads and analytics",
    description = "Blocks ads and analytics network requests for Feed, Reels, Stories, and Explore.",
) {
    addTigonRequestRule("BlockNetwork", ::isAdOrAnalyticsRequest)
    dependsOn(TigonRequestHook)
}

private fun isAdOrAnalyticsRequest(host: String, path: String): Boolean =
    // Feed ads
    path.startsWith("/api/v1/ads/") ||
        path.contains("/async_ads/") ||
        path.contains("/feed/injected_reels_media/") ||
        path.contains("/profile_ads/get_profile_ads/") ||
        // Reels / clips ads
        path.contains("/clips_viewer_feed_sa_multi_ads_watch_and_browse") ||
        // Ad event reporting
        path.contains("/async_ads_event") ||
        // Graph API hosts (ad targeting, data)
        host.contains("graph.instagram.com") ||
        host.contains("graph.facebook.com") ||
        // Analytics
        path.contains("/logging_client_events")
