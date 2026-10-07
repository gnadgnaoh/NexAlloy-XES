package io.github.nexalloy.revanced.zalo.ads

import io.github.nexalloy.patch

val DisableSponsoredPlacements = patch(
    name = "Disable sponsored placements",
    description = "Turns off Zalo's Story and Community ad slots at their server config " +
        "flags, so sponsored items are never inserted (no blank gaps, no impressions). " +
        "Promotions sent as Official Account messages are not affected.",
) {
    val blockedKeys = setOf(STORY_ADS_ENABLE_KEY, COMMUNITY_ADS_ENABLE_KEY)

    ::remoteConfigIntGetterFingerprint.hookMethod {
        before { param ->
            val key = param.args.getOrNull(0) as? String ?: return@before
            if (key in blockedKeys) param.result = 0
        }
    }
}
