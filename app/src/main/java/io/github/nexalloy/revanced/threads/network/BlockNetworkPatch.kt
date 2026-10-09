package io.github.nexalloy.revanced.threads.network

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.tigon.TigonRequestHook
import io.github.nexalloy.revanced.shared.tigon.addTigonRequestRule
import io.github.nexalloy.revanced.shared.tigon.tigonPreferredUriField

val BlockNetwork = patch(
    name = "Block ads and analytics",
    description = "Blocks ads and analytics network requests.",
) {
    tigonPreferredUriField = "A08"
    addTigonRequestRule("BlockNetwork", ::isAdOrAnalyticsRequest)
    dependsOn(TigonRequestHook)
}

private fun isAdOrAnalyticsRequest(host: String, path: String): Boolean =
    path.contains("/profile_ads/get_profile_ads/") ||
        path.contains("/async_ads/") ||
        path.contains("/feed/injected_reels_media/") ||
        path.contains("/api/v1/ads/") ||
        path.contains("/sponsored/") ||
        path.contains("/async_ads_event") ||
        path.contains("/activity_feed_sponsored_content_api") ||
        path.contains("/ads_event/") ||
        host.contains("graph.instagram.com") ||
        host.contains("graph.facebook.com") ||
        host.contains("an.facebook.com") ||
        (host.contains("fbcdn.net") && path.contains("/ads/")) ||
        path.contains("/logging_client_events")
