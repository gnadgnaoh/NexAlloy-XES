package io.github.nexalloy.revanced.zalo.ads

import io.github.nexalloy.patch

val SkipFeedAdsBinding = patch(
    name = "Skip feed ads binding",
    description = "Prevents sponsored feed items from loading their zinstant bundle and " +
            "reporting impressions. Disable this one first if the feed misbehaves.",
) {
    ::feedAdsBindFingerprint.hookMethod {
        before { param -> param.result = null }
    }
}
