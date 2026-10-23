package io.github.nexalloy.revanced.facebook.ad

import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookVoidMethodsNoOp

private val AD_PREFETCH_SCHEDULERS = listOf(
    "com.facebook.feed.push.adschannelbackgroundprefetch.FeedAdsChannelBackgroundPrefetchInitializerAppJob",
    "com.facebook.feed.push.adschannelemergingsurfaceprefetch.FeedAdsChannelEmergingSurfacePrefetchInitializerAppJob",
    "com.facebook.video.videohome.prefetching.ads.background.ReelsAdsBackgroundPrefetchAppJob",
    "com.facebook.stories.features.ads.prefetch.StoryViewerAdsPrefetchAppInitializationController",
    "com.facebook.stories.features.ads.prefetch.StoryViewerAdsPrefetchController",
    "com.facebook.addelivery.deliveryvalidation.cachedadsvalidator.NewsFeedAdCacheSyncInitializerAppJob",
    "com.facebook.feed.ads.mlranker.MlRankerAppJob",
)

val BlockAdPrefetch = patch(
    name = "Block background ad prefetch",
    description = "Stops the background jobs that download ads, and the model that ranks them, before you see them.",
) {
    ::sponsoredPoolAddMethodFingerprint.method

    val missing = AD_PREFETCH_SCHEDULERS.filter { it.findClassOrNull(classLoader) == null }
    check(missing.size < AD_PREFETCH_SCHEDULERS.size) { "no ad prefetch scheduler found" }

    AD_PREFETCH_SCHEDULERS.forEach { name -> name.findClassOrNull(classLoader)?.let { hookVoidMethodsNoOp(it) } }
    if (missing.isNotEmpty()) Logger.printInfo { "Facebook: ad prefetch schedulers not present: ${missing.joinToString()}" }
}
