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
import io.github.nexalloy.revanced.facebook.hookGameAdJavascriptInterfaceWatcher
import io.github.nexalloy.revanced.facebook.hookGameAdScriptDeliveries
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
import io.github.nexalloy.revanced.facebook.SponsoredDataCheck
import io.github.nexalloy.revanced.facebook.hookBlockNull
import io.github.nexalloy.revanced.facebook.hookSponsoredNull
import io.github.nexalloy.revanced.facebook.hookReceiverSponsoredNull
import io.github.nexalloy.revanced.facebook.hookNewsfeedSponsoredFilter
import io.github.nexalloy.revanced.facebook.installFeedComponentGuard
import io.github.nexalloy.revanced.facebook.hookMarketplaceSendRequest
import java.lang.reflect.Method

val HideFacebookAds = patch(
    name = "Hide Facebook ads",
    description = "Removes sponsored feed stories, Reels ads, game ads, and banner ads.",
) {
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }

    val storyInspector = runCatching { AdStoryInspector(::adKindEnumFingerprint.clazz) }.getOrNull()

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

    if (storyInspector != null) {
        ::pluginPackMethodsFingerprint.dexMethodList.forEach { dm ->
            runCatching { hookPluginPackFallback(dm.toMethod(), storyInspector) }
        }
    }

    runCatching { ::allPluginPackListMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookPluginPackList(dm.toMethod()) } }

    runCatching { ::pluginDescriptorGateMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookPluginDescriptorGate(dm.toMethod()) } }

    runCatching { ::directMonetizationAdsPluginListFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdPluginListBuilder(dm.toMethod()) } }

    runCatching {
        resolveInstreamBannerEligibilityMethod(::instreamBannerEligibilityClassFingerprint.clazz)
            ?.let { hookInstreamBannerEligibility(it) }
    }

    runCatching { hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method) }

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookReelsBannerRender(dm.toMethod()) }
    }

    val storyPoolAddMethods = runCatching {
        ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { dm ->
            runCatching { dm.toMethod() }.getOrNull()
        }
    }.getOrNull().orEmpty()

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

    runCatching {
        hookFeedCollectionAddEdge(::feedCollectionAddEdgeMethodFingerprint.method, feedItemInspector)
    }

    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching {
            val method = dm.toMethod()
            val listArgIndex = method.parameterTypes.indexOfFirst {
                it.name == "com.google.common.collect.ImmutableList"
            }.coerceAtLeast(0)
            hookLateFeedListSanitizer(FeedListSanitizerHook(method, listArgIndex), feedItemInspector)
        }
    }

    storyPoolAddMethods.forEach { method ->
        runCatching { hookStoryPoolAdd(method, feedItemInspector) }
    }

    runCatching { hookSponsoredPoolAdd(::sponsoredPoolAddMethodFingerprint.method) }

    runCatching { hookSponsoredStoryNext(::sponsoredStoryNextMethodFingerprint.method) }

    runCatching { hookSponsoredStoryListMethods(::sponsoredStoryManagerClassFingerprint.clazz) }

    runCatching {
        val poolClass = ::sponsoredPoolClassFingerprint.clazz
        hookSponsoredPoolListMethods(poolClass)
        hookSponsoredPoolResultMethods(poolClass)
    }

    runCatching { ::sponsoredStoryVendorMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookNullAdResult(dm.toMethod()) } }

    val insertionTrigger = runCatching { ::storyAdsInsertionTriggerMethodFingerprint.method }.getOrNull()
    val providerClasses = runCatching {
        ::storyAdsInDiscMethodsFingerprint.dexMethodList.mapNotNull { dm ->
            runCatching { classLoader.loadClass(dm.className) }.getOrNull()
        }.distinct()
    }.getOrNull().orEmpty().ifEmpty {
        listOfNotNull(runCatching { ::storyAdsInDiscClassFingerprint.clazz }.getOrNull())
    }
    providerClasses.forEachIndexed { index, providerClass ->
        runCatching {
            hookStoryAdProvider(
                resolveStoryAdProviderHooks(providerClass, index == 0, insertionTrigger)
            )
        }
    }

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }

    gameAdMethods.forEach { m ->
        runCatching { hookGameAdRequest(m) }
    }

    gameAdMethods.firstOrNull()?.let { firstMethod ->
        runCatching {
            firstMethod.declaringClass.declaredMethods
                .firstOrNull { m -> m.name == "postMessage" && m.parameterCount == 2 && m.parameterTypes.all { it == String::class.java } }
                ?.apply { isAccessible = true }
                ?.let { hookGameAdBridge(it) }
        }
    }

    gameAdMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
        runCatching { hookGameAdResultMethods(bridgeClass) }
        runCatching { hookGameAdServiceDispatchMethods(bridgeClass) }
    }

    runCatching { hookGameAdJavascriptInterfaceWatcher() }
    runCatching { hookGameAdScriptDeliveries() }

    runCatching { hookAudienceNetworkRewardFallbacks(classLoader) }

    runCatching { hookInstantGamesAdsLoader(classLoader) }

    runCatching { ::quicksilverAdsVoltronGateFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookForceBoolean(dm.toMethod(), false) } }

    runCatching { ::quicksilverBannerAdLoaderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdRequestNoOp(dm.toMethod()) } }

    runCatching {
        classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS).declaredMethods
            .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.let { hookPlayableAdActivity(it) }
    }

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

    // runCatching { hookGlobalGameAdSurfaceFallbacks() }

    runCatching {
        ::profileReelsAsyncAdsQueryFingerprint.hookMethod {
            before { param -> param.result = null }
        }
    }

    runCatching {
        hookNewsfeedSponsoredFilter(::newsfeedProcessNewStoriesRunFingerprint.method, classLoader)
    }

    runCatching {
        fun classesOf(list: List<org.luckypray.dexkit.wrap.DexMethod>) =
            list.mapNotNull { runCatching { classLoader.loadClass(it.className) }.getOrNull() }.distinct()
        val components = classesOf(::feedUnitComponentClassesFingerprint.dexMethodList)
        val wrappers = classesOf(::feedWrapperComponentClassesFingerprint.dexMethodList)
        installFeedComponentGuard(components, wrappers, FeedItemInspector(emptyList()))
    }

    SponsoredDataCheck.init(classLoader)

    runCatching { ::feedAdChannelBlockMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }

    runCatching { ::feedSponsoredRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookSponsoredNull(dm.toMethod()) } }

    runCatching { ::reelsAdBlockMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }

    runCatching { ::shortsMidCardTypeNodeFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookReceiverSponsoredNull(dm.toMethod()) } }

    runCatching { ::marketplaceSendRequestFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookMarketplaceSendRequest(dm.toMethod()) } }

    runCatching { ::marketplaceAdRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookNullAdResult(dm.toMethod()) } }
}
