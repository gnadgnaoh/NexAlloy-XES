package io.github.nexalloy.revanced.facebook.ad

import android.os.Bundle
import io.github.nexalloy.findClassOrNull
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
    // Chưa thấy dex feed → ném lỗi → KatanaDexGate thử lại; lần cuối vẫn lỗi thì có toast.
    ::sponsoredPoolAddMethodFingerprint.method

    val storyInspector = AdStoryInspector(::adKindEnumFingerprint.clazz)
    val listBuilderClass = ::listBuilderClassFingerprint.clazz

    hookListBuilderAppend(resolveListBuilderAppendMethod(listBuilderClass), storyInspector)
    resolveListBuilderFactoryMethod(listBuilderClass)?.let { factoryMethod ->
        hookListResultFilter(factoryMethod, "list factory", storyInspector)
    }

    ::pluginPackMethodsFingerprint.dexMethodList.forEach { dm ->
        hookPluginPackFallback(dm.toMethod(), storyInspector)
    }

    ::allPluginPackListMethodsFingerprint.dexMethodList.forEach { dm -> hookPluginPackList(dm.toMethod()) }
    ::pluginDescriptorGateMethodsFingerprint.dexMethodList.forEach { dm -> hookPluginDescriptorGate(dm.toMethod()) }
    ::directMonetizationAdsPluginListFingerprint.dexMethodList.forEach { dm -> hookAdPluginListBuilder(dm.toMethod()) }

    resolveInstreamBannerEligibilityMethod(::instreamBannerEligibilityClassFingerprint.clazz)
        ?.let { hookInstreamBannerEligibility(it) }

    hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method)

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dm -> hookReelsBannerRender(dm.toMethod()) }

    val storyPoolAddMethods = ::storyPoolAddMethodsFingerprint.dexMethodList.map { it.toMethod() }

    val feedItemInspector = FeedItemInspector(
        storyPoolAddMethods
            .mapNotNull { it.parameterTypes.firstOrNull() }
            .distinct()
            .filter { type -> type.methods.any { it.parameterCount == 0 && it.returnType != Void.TYPE } }
    )

    ::feedCsrFilterMethodsFingerprint.dexMethodList.forEach { dm ->
        val method = dm.toMethod()
        val listArgIndex = method.parameterTypes.indexOfFirst {
            it.name == "com.google.common.collect.ImmutableList"
        }.coerceAtLeast(0)
        hookFeedCsrFilterInput(FeedCsrFilterHook(method, listArgIndex), feedItemInspector)
    }

    hookFeedCollectionAddEdge(::feedCollectionAddEdgeMethodFingerprint.method, feedItemInspector)

    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dm ->
        val method = dm.toMethod()
        val listArgIndex = method.parameterTypes.indexOfFirst {
            it.name == "com.google.common.collect.ImmutableList"
        }.coerceAtLeast(0)
        hookLateFeedListSanitizer(FeedListSanitizerHook(method, listArgIndex), feedItemInspector)
    }

    storyPoolAddMethods.forEach { method -> hookStoryPoolAdd(method, feedItemInspector) }

    hookSponsoredPoolAdd(::sponsoredPoolAddMethodFingerprint.method)
    hookSponsoredStoryNext(::sponsoredStoryNextMethodFingerprint.method)
    hookSponsoredStoryListMethods(::sponsoredStoryManagerClassFingerprint.clazz)

    val poolClass = ::sponsoredPoolClassFingerprint.clazz
    hookSponsoredPoolListMethods(poolClass)
    hookSponsoredPoolResultMethods(poolClass)

    ::sponsoredStoryVendorMethodsFingerprint.dexMethodList.forEach { dm -> hookNullAdResult(dm.toMethod()) }

    val insertionTrigger = ::storyAdsInsertionTriggerMethodFingerprint.method
    // Dự phòng có chủ đích: không có method nào thì dùng class fingerprint (cái này mà thiếu thì ném lỗi).
    val providerClasses = ::storyAdsInDiscMethodsFingerprint.dexMethodList
        .map { classLoader.loadClass(it.className) }
        .distinct()
        .ifEmpty { listOf(::storyAdsInDiscClassFingerprint.clazz) }
    providerClasses.forEachIndexed { index, providerClass ->
        hookStoryAdProvider(resolveStoryAdProviderHooks(providerClass, index == 0, insertionTrigger))
    }

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.map { it.toMethod() }
    gameAdMethods.forEach { m -> hookGameAdRequest(m) }

    gameAdMethods.firstOrNull()?.let { firstMethod ->
        firstMethod.declaringClass.declaredMethods
            .firstOrNull { m -> m.name == "postMessage" && m.parameterCount == 2 && m.parameterTypes.all { it == String::class.java } }
            ?.apply { isAccessible = true }
            ?.let { hookGameAdBridge(it) }
    }

    gameAdMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
        hookGameAdResultMethods(bridgeClass)
        hookGameAdServiceDispatchMethods(bridgeClass)
    }

    hookGameAdJavascriptInterfaceWatcher()
    hookGameAdScriptDeliveries()
    hookAudienceNetworkRewardFallbacks(classLoader)
    hookInstantGamesAdsLoader(classLoader)

    ::quicksilverAdsVoltronGateFingerprint.dexMethodList.forEach { dm -> hookForceBoolean(dm.toMethod(), false) }
    ::quicksilverBannerAdLoaderMethodsFingerprint.dexMethodList.forEach { dm -> hookAdRequestNoOp(dm.toMethod()) }

    // Class có thể không tồn tại ở một số bản → dò bằng findClassOrNull, không cần runCatching.
    NEKO_PLAYABLE_ACTIVITY_CLASS.findClassOrNull(classLoader)?.declaredMethods
        ?.firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
        ?.apply { isAccessible = true }
        ?.let { hookPlayableAdActivity(it) }

    fun Class<*>.lifecycleMethod() = (declaredMethods + methods).firstOrNull { m ->
        (m.name == "onResume" && m.parameterCount == 0) ||
            (m.name == "onStart" && m.parameterCount == 0) ||
            (m.name == "onCreate" && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
    }?.apply { isAccessible = true }

    val gameAdUiHooked = LinkedHashMap<String, Method>()
    listOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS).forEach { cn ->
        cn.findClassOrNull(classLoader)?.lifecycleMethod()
            ?.let { gameAdUiHooked.putIfAbsent("${it.declaringClass.name}.${it.name}", it) }
    }
    if (gameAdUiHooked.isEmpty()) {
        val activityClass = android.app.Activity::class.java
        GAME_AD_ACTIVITY_CLASS_NAMES.forEach { className ->
            val clazz = className.findClassOrNull(classLoader)
            if (clazz != null && activityClass.isAssignableFrom(clazz)) {
                clazz.lifecycleMethod()
                    ?.let { gameAdUiHooked.putIfAbsent("${it.declaringClass.name}.${it.name}", it) }
            }
        }
    }
    gameAdUiHooked.values.forEach { method -> hookPlayableAdActivity(method) }

    // hookGlobalGameAdActivityLifecycleFallback()

    hookGameAdActivityLaunchFallbacks()

    // hookGlobalGameAdSurfaceFallbacks()

    ::profileReelsAsyncAdsQueryFingerprint.hookMethod {
        before { param -> param.result = null }
    }

    hookNewsfeedSponsoredFilter(::newsfeedProcessNewStoriesRunFingerprint.method, classLoader)

    fun classesOf(list: List<org.luckypray.dexkit.wrap.DexMethod>) =
        list.map { classLoader.loadClass(it.className) }.distinct()
    installFeedComponentGuard(
        classesOf(::feedUnitComponentClassesFingerprint.dexMethodList),
        classesOf(::feedWrapperComponentClassesFingerprint.dexMethodList),
        FeedItemInspector(emptyList()),
    )

    SponsoredDataCheck.init(classLoader)

    ::feedAdChannelBlockMethodsFingerprint.dexMethodList.forEach { dm -> hookBlockNull(dm.toMethod()) }
    ::feedSponsoredRenderMethodsFingerprint.dexMethodList.forEach { dm -> hookSponsoredNull(dm.toMethod()) }
    ::reelsAdBlockMethodsFingerprint.dexMethodList.forEach { dm -> hookBlockNull(dm.toMethod()) }
    ::shortsMidCardTypeNodeFingerprint.dexMethodList.forEach { dm -> hookReceiverSponsoredNull(dm.toMethod()) }
    ::marketplaceSendRequestFingerprint.dexMethodList.forEach { dm -> hookMarketplaceSendRequest(dm.toMethod()) }
    ::marketplaceAdRenderMethodsFingerprint.dexMethodList.forEach { dm -> hookNullAdResult(dm.toMethod()) }
}
