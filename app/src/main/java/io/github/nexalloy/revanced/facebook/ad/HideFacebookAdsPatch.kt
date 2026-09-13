package io.github.nexalloy.revanced.facebook.ad

import android.os.Bundle
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.AdStoryInspector
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.GAME_AD_ACTIVITY_CLASS_NAMES
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.FeedCsrFilterHook
import io.github.nexalloy.revanced.facebook.FeedItemInspector
import io.github.nexalloy.revanced.facebook.FeedListSanitizerHook
import io.github.nexalloy.revanced.facebook.NEKO_PLAYABLE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.hookAudienceNetworkRewardFallbacks
import io.github.nexalloy.revanced.facebook.hookFeedCsrFilterInput
import io.github.nexalloy.revanced.facebook.hookGameAdActivityLaunchFallbacks
import io.github.nexalloy.revanced.facebook.hookGameAdBridge
import io.github.nexalloy.revanced.facebook.hookGameAdRequest
import io.github.nexalloy.revanced.facebook.hookGameAdResultMethods
import io.github.nexalloy.revanced.facebook.hookGameAdServiceDispatchMethods
import io.github.nexalloy.revanced.facebook.hookGlobalGameAdActivityLifecycleFallback
import io.github.nexalloy.revanced.facebook.hookGlobalGameAdSurfaceFallbacks
import io.github.nexalloy.revanced.facebook.hookIndicatorPillAdEligibility
import io.github.nexalloy.revanced.facebook.hookInstreamBannerEligibility
import io.github.nexalloy.revanced.facebook.hookLateFeedListSanitizer
import io.github.nexalloy.revanced.facebook.hookListBuilderAppend
import io.github.nexalloy.revanced.facebook.hookAdRequestNoOp
import io.github.nexalloy.revanced.facebook.hookForceBoolean
import io.github.nexalloy.revanced.facebook.hookInstantGamesAdsLoader
import io.github.nexalloy.revanced.facebook.hookListResultFilter
import io.github.nexalloy.revanced.facebook.hookNullAdResult
import io.github.nexalloy.revanced.facebook.hookPlayableAdActivity
import io.github.nexalloy.revanced.facebook.hookAdPluginListBuilder
import io.github.nexalloy.revanced.facebook.hookPluginDescriptorGate
import io.github.nexalloy.revanced.facebook.hookPluginPackFallback
import io.github.nexalloy.revanced.facebook.hookPluginPackList
import io.github.nexalloy.revanced.facebook.hookReelsBannerRender
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolAdd
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolListMethods
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolResultMethods
import io.github.nexalloy.revanced.facebook.hookSponsoredStoryListMethods
import io.github.nexalloy.revanced.facebook.hookSponsoredStoryNext
import io.github.nexalloy.revanced.facebook.hookStoryAdProvider
import io.github.nexalloy.revanced.facebook.hookStoryPoolAdd
import io.github.nexalloy.revanced.facebook.hookFeedCollectionAddEdge
import io.github.nexalloy.revanced.facebook.resolveListBuilderAppendMethod
import io.github.nexalloy.revanced.facebook.resolveListBuilderFactoryMethod
import io.github.nexalloy.revanced.facebook.resolveInstreamBannerEligibilityMethod
import io.github.nexalloy.revanced.facebook.resolveStoryAdProviderHooks
import java.lang.reflect.Method

/**
 * Master patch – ports all FacebookAppAdsRemover hooks into NexAlloy.
 *
 * Synced with upstream commit that:
 *  - Adds MarketplaceAdsPluginPack blocking
 *  - Adds hidebanneradasync message type
 *  - Splits fix strategy: banner → autofix, rewarded/interstitial → ADS_UNAVAILABLE
 *  - Adds hookGameAdResultMethods + hookGameAdServiceDispatchMethods (deeper bridge hooks)
 *  - Adds hookGlobalGameAdSurfaceFallbacks (native ad view / WebView / TextView)
 *  - Adds hookAudienceNetworkRewardFallbacks (reward completion callbacks)
 *  - Sets RESULT_OK (not RESULT_CANCELED) when finishing game ad activities
 *  - Changes storyAdsInDisc search string to "ads_deletion"
 */
val HideFacebookAds = patch(
    name = "Hide Facebook ads",
    description = "Removes sponsored feed stories, Reels ads, game ads, and banner ads.",
) {
    // ── 0. Readiness gate ────────────────────────────────────────────────────
    // The former FB571 fast paths (Class.forName on pinned X.* names) are gone. Every
    // target below is resolved structurally by DexKit, so the patch survives Facebook
    // renaming its obfuscated classes between builds.
    //
    // Facebook loads its feed code from Superpack-compressed secondary dex, so at
    // Application.onCreate a DexKit scan can legitimately see nothing. Every hook below
    // is wrapped in runCatching, which would make this patch report success while having
    // hooked nothing at all. So probe one fingerprint that is known to exist on every
    // build and throw if it misses: KatanaDexGate treats the failure as "not ready yet"
    // and re-runs the whole patch once more dex is installed. Re-running is safe — the
    // hook helpers deduplicate by method.
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }

    // ── 1. Ad-kind enum & Reels list-builder ─────────────────────────────────

    // Both of these were previously unwrapped: a resolution failure threw out of the
    // patch body and silently cancelled every hook after it. They are optional now.
    val storyInspector = runCatching { AdStoryInspector(::adKindEnumFingerprint.clazz) }.getOrNull()

    // listBuilderClass itself is DexKit-cached; the specific append/factory method on
    // it is then picked via a plain-reflection scoring heuristic (no DexKit search),
    // matching upstream's more flexible (non-rigid-param-shape) resolution.
    val listBuilderClass = runCatching { ::listBuilderClassFingerprint.clazz }.getOrNull()

    if (storyInspector != null && listBuilderClass != null) {
        runCatching {
            hookListBuilderAppend(resolveListBuilderAppendMethod(listBuilderClass), storyInspector)
        }

        runCatching {
            resolveListBuilderFactoryMethod(listBuilderClass)?.let { factoryMethod ->
                hookListResultFilter(factoryMethod, "list factory", storyInspector)
            }
        }
    }

    // Both FbShortsViewerPluginPack and MarketplaceAdsPluginPack
    if (storyInspector != null) {
        ::pluginPackMethodsFingerprint.dexMethodList.forEach { dm ->
            runCatching { hookPluginPackFallback(dm.toMethod(), storyInspector) }
        }
    }

    // ── 2b. In-video ads: the video plugin system ─────────────────────────────
    //
    // Ads served inside a video rather than as their own feed story. Three layers,
    // because Facebook delivers them by three different routes:
    //
    //   packs       - a pack whose whole purpose is ads; its plugin list is emptied
    //   descriptors - individual ad plugins carried by a pack that also carries organic
    //                 ones, or by a pack whose name is built at runtime; each is refused
    //                 at its own eligibility gate
    //   builders    - ad plugins assembled by a static builder with no pack object at all
    //
    // All three filter per instance, so organic packs, descriptors and plugins are never
    // touched. Nothing here pins an obfuscated name.

    runCatching { ::allPluginPackListMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookPluginPackList(dm.toMethod()) } }

    runCatching { ::pluginDescriptorGateMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookPluginDescriptorGate(dm.toMethod()) } }

    runCatching { ::directMonetizationAdsPluginListFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdPluginListBuilder(dm.toMethod()) } }

    // ── 2. Instream banner & indicator pill ───────────────────────────────────

    runCatching {
        resolveInstreamBannerEligibilityMethod(::instreamBannerEligibilityClassFingerprint.clazz)
            ?.let { hookInstreamBannerEligibility(it) }
    }

    runCatching { hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method) }

    // ── 3. Reels banner Litho render ──────────────────────────────────────────

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookReelsBannerRender(dm.toMethod()) }
    }

    // ── 4. Feed CSR cache filter ──────────────────────────────────────────────

    val storyPoolAddMethods = runCatching {
        ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { dm ->
            runCatching { dm.toMethod() }.getOrNull()
        }
    }.getOrNull().orEmpty()

    // The item-contract interfaces come from the story-pool add parameter (verified on
    // FB 573: X.3Ws — a 0-arg boolean, a 0-arg Object edge getter and a model getter).
    // Types that expose no 0-arg accessor at all are dropped so they can't shadow the
    // real contract during shape-based accessor resolution.
    val feedItemInspector = FeedItemInspector(
        storyPoolAddMethods
            .mapNotNull { it.parameterTypes.firstOrNull() }
            .distinct()
            .filter { type -> type.methods.any { it.parameterCount == 0 && it.returnType != Void.TYPE } }
    )

    ::feedCsrFilterMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching {
            val method = dm.toMethod()
            val listArgIndex = method.parameterTypes.indexOfFirst {
                it.name == "com.google.common.collect.ImmutableList"
            }.coerceAtLeast(0)
            hookFeedCsrFilterInput(FeedCsrFilterHook(method, listArgIndex), feedItemInspector)
        }
    }

    // ── 4b. addNewEdgeToCollection filter ─────────────────────────────────────

    runCatching {
        hookFeedCollectionAddEdge(::feedCollectionAddEdgeMethodFingerprint.method, feedItemInspector)
    }

    // ── 5. Late feed list sanitisers ──────────────────────────────────────────

    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching {
            val method = dm.toMethod()
            val listArgIndex = method.parameterTypes.indexOfFirst {
                it.name == "com.google.common.collect.ImmutableList"
            }.coerceAtLeast(0)
            hookLateFeedListSanitizer(FeedListSanitizerHook(method, listArgIndex), feedItemInspector)
        }
    }

    // ── 6. Story pool add ─────────────────────────────────────────────────────

    storyPoolAddMethods.forEach { method ->
        runCatching { hookStoryPoolAdd(method, feedItemInspector) }
    }

    // ── 7. Sponsored pool ─────────────────────────────────────────────────────

    runCatching { hookSponsoredPoolAdd(::sponsoredPoolAddMethodFingerprint.method) }

    runCatching { hookSponsoredStoryNext(::sponsoredStoryNextMethodFingerprint.method) }

    runCatching { hookSponsoredStoryListMethods(::sponsoredStoryManagerClassFingerprint.clazz) }

    runCatching {
        val poolClass = ::sponsoredPoolClassFingerprint.clazz
        hookSponsoredPoolListMethods(poolClass)
        hookSponsoredPoolResultMethods(poolClass)
    }

    // The vendor sitting in front of the pool: the two methods the feed calls to pick
    // which ad goes in the next slot. Both already answer "nothing eligible" by returning
    // null — the pool logs empty_pool and the feed carries on with organic stories — so
    // this puts them permanently on a path the app handles on its own every session.
    runCatching { ::sponsoredStoryVendorMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookNullAdResult(dm.toMethod()) } }

    // ── 8. Story ad provider (in-disc) ────────────────────────────────────────

    // Every class that logs "ads_deletion" AND carries the provider shape — this replaces
    // both the single-class lookup and the six pinned FB571_STORY_AD_SOURCE_CLASSES.
    val insertionTrigger = runCatching { ::storyAdsInsertionTriggerMethodFingerprint.method }.getOrNull()
    val providerClasses = runCatching {
        ::storyAdsInDiscMethodsFingerprint.dexMethodList.mapNotNull { dm ->
            runCatching { dm.toMethod().declaringClass }.getOrNull()
        }.distinct()
    }.getOrNull().orEmpty().ifEmpty {
        listOfNotNull(runCatching { ::storyAdsInDiscClassFingerprint.clazz }.getOrNull())
    }
    providerClasses.forEachIndexed { index, providerClass ->
        runCatching {
            // Only the first provider gets the insertion trigger, matching upstream.
            hookStoryAdProvider(
                resolveStoryAdProviderHooks(providerClass, index == 0, insertionTrigger)
            )
        }
    }

    // ── 9. Game ad requests + bridge ─────────────────────────────────────────

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }

    gameAdMethods.forEach { m ->
        runCatching { hookGameAdRequest(m) }
    }

    // postMessage bridge
    gameAdMethods.firstOrNull()?.let { firstMethod ->
        runCatching {
            firstMethod.declaringClass.declaredMethods
                .firstOrNull { m -> m.name == "postMessage" && m.parameterCount == 2 && m.parameterTypes.all { it == String::class.java } }
                ?.apply { isAccessible = true }
                ?.let { hookGameAdBridge(it) }
        }
    }

    // ── 10. Deeper bridge hooks (resolve / reject / service dispatch) ─────────

    gameAdMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
        runCatching { hookGameAdResultMethods(bridgeClass) }
        runCatching { hookGameAdServiceDispatchMethods(bridgeClass) }
    }

    // ── 11. Audience Network reward fallbacks ─────────────────────────────────

    runCatching { hookAudienceNetworkRewardFallbacks(classLoader) }

    // ── 11b. Instant Games ads (Quicksilver) ─────────────────────────────────
    //
    // A layer below everything in sections 9–11, which all sit on the JavaScript bridge
    // and answer a game that has already loaded the ad code. Instant Games advertising
    // ships as its own dynamic module, so refusing that load leaves nothing in the
    // process to serve an ad in the first place. The banner runnable is hooked as well:
    // it is what draws a banner over a running game, and it is reached without the
    // bridge on a build where the module was already cached.

    runCatching { hookInstantGamesAdsLoader(classLoader) }

    runCatching { ::quicksilverAdsVoltronGateFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookForceBoolean(dm.toMethod(), false) } }

    runCatching { ::quicksilverBannerAdLoaderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdRequestNoOp(dm.toMethod()) } }

    // ── 12. Activity lifecycle hooks ──────────────────────────────────────────

    // NekoPlayableAdActivity
    runCatching {
        classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS).declaredMethods
            .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.let { hookPlayableAdActivity(it) }
    }

    // AudienceNetwork activities — one entry-point method per class (first match
    // among onResume / onStart / onCreate(Bundle)), matching upstream's
    // resolveGameAdUiActivityMethods. Falls back to a broader Activity-subclass
    // scan across GAME_AD_ACTIVITY_CLASS_NAMES if neither AN class yields a hook.
    val gameAdUiHooked = LinkedHashMap<String, Method>()
    listOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS).forEach { cn ->
        runCatching {
            val actClass = classLoader.loadClass(cn)
            (actClass.declaredMethods + actClass.methods).firstOrNull { m ->
                (m.name == "onResume" && m.parameterCount == 0) ||
                (m.name == "onStart" && m.parameterCount == 0) ||
                (m.name == "onCreate" && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
            }?.apply { isAccessible = true }
                ?.let { gameAdUiHooked.putIfAbsent("${it.declaringClass.name}.${it.name}", it) }
        }
    }
    if (gameAdUiHooked.isEmpty()) {
        runCatching {
            val activityClass = classLoader.loadClass("android.app.Activity")
            GAME_AD_ACTIVITY_CLASS_NAMES.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                if (clazz != null && activityClass.isAssignableFrom(clazz)) {
                    (clazz.declaredMethods + clazz.methods).firstOrNull { m ->
                        (m.name == "onResume" && m.parameterCount == 0) ||
                        (m.name == "onStart" && m.parameterCount == 0) ||
                        (m.name == "onCreate" && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
                    }?.apply { isAccessible = true }
                        ?.let { gameAdUiHooked.putIfAbsent("${it.declaringClass.name}.${it.name}", it) }
                }
            }
        }
    }
    gameAdUiHooked.values.forEach { method -> runCatching { hookPlayableAdActivity(method) } }

    // runCatching { hookGlobalGameAdActivityLifecycleFallback() }

    runCatching { hookGameAdActivityLaunchFallbacks() }

    // ── 13. Native ad view / WebView surface fallbacks ────────────────────────

    // runCatching { hookGlobalGameAdSurfaceFallbacks() }

    // ── 14. ProfileReelsAsyncAdsQuery dispatch block ────────────────────

    runCatching {
        ::profileReelsAsyncAdsQueryFingerprint.hookMethod {
            before { param -> param.result = null }
        }
    }
}
