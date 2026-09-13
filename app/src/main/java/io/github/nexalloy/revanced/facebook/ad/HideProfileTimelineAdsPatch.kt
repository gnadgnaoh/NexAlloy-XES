package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.FeedItemInspector
import io.github.nexalloy.revanced.facebook.hookTimelineStoryRender

/**
 * Sponsored posts injected into someone's profile timeline.
 *
 * A separate surface from the news feed: the profile loads its posts through its own
 * query, so the CSR filter, the sponsored pool and the collection filter never see these.
 *
 * The story arrives as a bare `com.facebook.graphql.model.GraphQLStory` with no category,
 * which is why the feed's usual tests are no help here. What does distinguish it is its
 * tracking data: a sponsored post carries an ad id, an organic one does not. Tracing a
 * real profile showed the ad's tracking accessor returning `{"adid":"1202471980…"}` while
 * the organic post next to it returned nothing matching at all.
 *
 * That positive signal is the whole basis of this patch, and it is what makes it safe. A
 * story with no ad id is never touched, so a missed ad is the worst outcome — as opposed
 * to the three earlier attempts, which asked "does this look ad-like", got "yes" for every
 * post, and left the profile showing an empty skeleton.
 */
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
