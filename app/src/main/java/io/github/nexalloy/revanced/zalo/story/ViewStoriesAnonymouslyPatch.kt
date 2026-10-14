package io.github.nexalloy.revanced.zalo.story

import io.github.nexalloy.IHookCallback
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.privacy.Chokepoint
import io.github.nexalloy.revanced.zalo.privacy.installChokepoints

private val dropRequest: IHookCallback = { param -> param.result = null }

val ViewStoriesAnonymously = patch(
    name = "View stories anonymously",
    description = "Watches friends' stories without adding you to their viewer list. " +
        "The \"seen story\" packets are dropped before they reach the server; stories " +
        "are still marked as watched on this device. Reactions and replies are still sent.",
) {
    installChokepoints(
        "View stories anonymously",
        listOf(
            Chokepoint("story seen", ::storySeenRequestFingerprint, dropRequest),
            Chokepoint("suggested story seen", ::storySeenSuggestedRequestFingerprint, dropRequest),
        ),
    )
}
