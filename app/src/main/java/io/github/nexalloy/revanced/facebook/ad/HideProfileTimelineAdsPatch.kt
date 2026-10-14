package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.FeedItemInspector
import io.github.nexalloy.revanced.facebook.hookTimelineStoryRender

val HideProfileTimelineAds = patch(
    name = "Hide profile timeline ads",
    description = "Removes sponsored posts from profile pages by checking for an advertisement's tracking id. Posts without one are left alone.",
) {
    val storyPoolAddMethods = runCatching {
        ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { runCatching { it.toMethod() }.getOrNull() }
    }.getOrNull().orEmpty()

    val inspector = FeedItemInspector(
        storyPoolAddMethods.mapNotNull { it.parameterTypes.firstOrNull() }.distinct()
            .filter { type -> type.methods.any { it.parameterCount == 0 && it.returnType != Void.TYPE } }
    )

    runCatching {
        hookTimelineStoryRender(::timelineStoryRenderMethodFingerprint.method, inspector)
    }
}
