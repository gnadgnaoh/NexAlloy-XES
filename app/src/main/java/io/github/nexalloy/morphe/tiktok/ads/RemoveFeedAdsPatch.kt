package io.github.nexalloy.morphe.tiktok.ads

import app.morphe.extension.shared.Logger
import io.github.nexalloy.IHookCallback
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.setObjectField

private const val TAG = "[TikTok ads]"

internal val TikTokFeedFilterHooks = patch(name = "<TikTokFeedFilterHooks>") {
    AwemeAdFilter.init(classLoader)

    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    val feedApis = ::feedApiFetchFeedListFingerprints.dexMethodList.realMatches()
    check(feedApis.isNotEmpty()) { "IFeedApi.fetchFeedList implementation not found" }
    feedApis.forEach {
        it.hookMethod {
            after { param -> filterFeedItemList(param.result, "fetchFeedList") }
        }
    }
    installed += "fetchFeedList x${feedApis.size}"

    optional("insertItemList") {
        val filterPayload: IHookCallback = { param ->
            param.args.forEach { filterSingleListField(it, "insertItemList") }
        }
        val inserts = ::feedInsertItemListFingerprints.dexMethodList.realMatches()
        check(inserts.isNotEmpty()) { "insertItemList not found" }
        inserts.forEach { it.hookMethod { before(filterPayload) } }
    }

    optional("coldStartCache") {
        val coldStart = ::coldStartFeedCacheFingerprints.dexMethodList.realMatches()
        check(coldStart.isNotEmpty()) { "cold-start FeedItemList getter not found" }
        coldStart.forEach {
            it.hookMethod {
                after { param -> filterFeedItemList(param.result, "coldStartCache") }
            }
        }
    }

    optional("videoGrids") {
        val filters = ::videoGridAdListFilterFingerprints.dexMethodList.realMatches()
        if (filters.isNotEmpty()) {
            filters.forEach {
                it.hookMethod {
                    after { param ->
                        val result = param.result as? List<*> ?: return@after
                        val kept = AwemeAdFilter.filteredCopyOrNull(result) ?: return@after
                        logRemoved("videoGrid", result.size - kept.size)
                        param.result = kept
                    }
                }
            }
        } else {
            val callbacks = ::profileResultCallbackFingerprints.dexMethodList.realMatches()
            check(callbacks.isNotEmpty()) { "no grid list filter and no result callback found" }
            callbacks.forEach {
                it.hookMethod {
                    before { param ->
                        val listArg = param.args.indexOfFirst { arg -> arg is List<*> }
                        if (listArg < 0) return@before
                        AwemeAdFilter.filteredCopyOrNull(param.args[listArg] as? List<*>)
                            ?.let { kept -> param.args[listArg] = kept }
                    }
                }
            }
            Logger.printInfo { "$TAG videoGrids: using ${callbacks.size} result-callback fallbacks" }
        }
    }

    optional("talentProfileAds") {
        val callbacks = ::talentProfileAdsCallbackFingerprints.dexMethodList.realMatches()
        check(callbacks.isNotEmpty()) { "ProfileTalentShareAdResult reader not found" }
        callbacks.forEach {
            it.hookMethod {
                before { param -> param.args.forEach { filterTalentAdResult(it) } }
            }
        }
    }

    optional("talentProfileAdEvent") {
        val filterEvent: IHookCallback = { param ->
            param.args.forEach { filterSingleListField(it, "talentProfileAdEvent") }
        }
        val byName = DETAIL_FRAGMENT_CLASS.findClassOrNull(classLoader)?.declaredMethods
            ?.filter { it.name == "onTalentProfileAdEvent" && it.parameterCount == 1 }
            .orEmpty()
        if (byName.isNotEmpty()) {
            byName.forEach { it.hookMethod { before(filterEvent) } }
        } else {
            val found = ::talentProfileAdEventSubscriberFingerprints.dexMethodList.realMatches()
            check(found.isNotEmpty()) { "onTalentProfileAdEvent not found" }
            found.forEach { it.hookMethod { before(filterEvent) } }
        }
    }

    optional("followingFeed") { hookFollowingFeed() }

    optional("midRollAds") {
        val splices = ::midRollAdSpliceFingerprints.dexMethodList.realMatches()
        check(splices.isNotEmpty()) { "MidAdComponent splice not found" }
        splices.forEach {
            it.hookMethod {
                before { param ->
                    if (!AwemeAdFilter.hideAds) return@before
                    param.result = null
                    logRemoved("midRollAd", 1)
                }
            }
        }
    }

    optional("profilePagerAds") {
        val gates = ::profileAdEligibilityFingerprints.dexMethodList.realMatches()
        check(gates.isNotEmpty()) { "profile_ad_experiment gate not found" }
        gates.forEach {
            it.hookMethod {
                after { param -> if (AwemeAdFilter.hideAds && param.result == true) param.result = false }
            }
        }
    }

    optional("searchAds") {
        val results = SEARCH_MIX_FEED_LIST_CLASS.findClassOrNull(classLoader) ?: error("$SEARCH_MIX_FEED_LIST_CLASS not found")
        results.getDeclaredMethod("setRequestId", String::class.java).hookMethod {
            before { param -> filterSearchResults(param.thisObject) }
        }
    }

    optional("friendsFeed") { hookFriendsFeed() }

    optional("dramaAdLock") {
        val gates = DRAMA_BLOCKING_AD_SERVICE_CLASS.findClassOrNull(classLoader)?.declaredMethods
            ?.filter {
                it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.singleOrNull()?.name == AWEME_CLASS
            }
            .orEmpty()
        check(gates.size == 1) { "expected one (Aweme): Boolean method, found ${gates.size}" }
        gates.single().hookMethod {
            after { param -> if (AwemeAdFilter.hideAds && param.result == true) param.result = false }
        }
    }
    
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}

val RemoveFeedAds = patch(
    name = "Remove feed ads",
    description = "Removes sponsored videos from the For You, Following, Friends and profile " +
            "feeds, mid-roll and profile pager ads, search result ads and the scroll lock of " +
            "short-drama ads, including ads inserted after the feed was loaded and the cold-start cache.",
) {
    AwemeAdFilter.hideAds = true
    dependsOn(TikTokFeedFilterHooks)
}

val HidePaidPartnerships = patch(
    name = "Hide paid partnerships",
    description = "Also hides creator posts labelled \"Paid partnership\" (branded content), " +
            "\"Creator earns commission\" and location affiliate posts. Your own posts are kept.",
) {
    AwemeAdFilter.hidePaidPartnerships = true
    dependsOn(TikTokFeedFilterHooks)
}

val HidePromotedMusicVideos = patch(
    name = "Hide promoted-music videos",
    description = "Also hides videos flagged by TikTok as using a paid promoted sound. " +
            "May hide some organic creator videos.",
    use = false,
) {
    AwemeAdFilter.hidePromotedMusic = true
    dependsOn(TikTokFeedFilterHooks)
}

private fun filterFeedItemList(feedItemList: Any?, source: String) {
    if (feedItemList == null || !AwemeAdFilter.enabled) return
    logRemoved(source, AwemeAdFilter.filterListField(feedItemList, "items"))
    if (AwemeAdFilter.hideAds) clearPreloadAds(feedItemList)
}

private fun clearPreloadAds(feedItemList: Any) {
    val ads = feedItemList.getObjectFieldOrNull("preloadAds") as? List<*>
    if (ads.isNullOrEmpty()) return
    runCatching { feedItemList.setObjectField("preloadAds", ArrayList<Any?>(0)) }
        .onSuccess { logRemoved("topViewPreload", ads.size) }
}

private fun filterSearchResults(results: Any?) {
    if (results == null || !AwemeAdFilter.hideAds) return
    val cards = results.getObjectFieldOrNull("mItems") as? List<*>
    if (cards.isNullOrEmpty()) return
    val kept = try {
        cards.filterNot(AwemeAdFilter::isSearchAdCard)
    } catch (_: ConcurrentModificationException) {
        return
    }
    if (kept.size == cards.size || kept.isEmpty()) return
    runCatching { results.setObjectField("mItems", ArrayList(kept)) }
        .onSuccess { logRemoved("searchAds", cards.size - kept.size) }
}

private fun PatchExecutor.hookFriendsFeed() {
    val response = FRIENDS_FEED_RESPONSE_CLASS.findClassOrNull(classLoader)
        ?: error("$FRIENDS_FEED_RESPONSE_CLASS not found")

    fun filter(owner: Any?, source: String) {
        if (owner == null || !AwemeAdFilter.enabled || !response.isInstance(owner)) return
        val list = owner.getObjectFieldOrNull("friendFeedData") as? List<*> ?: return
        val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return
        runCatching { owner.setObjectField("friendFeedData", kept) }
            .onSuccess { logRemoved(source, list.size - kept.size) }
    }

    response.getDeclaredMethod("getAwemeList").hookMethod {
        before { param -> filter(param.thisObject, "friendsFeed") }
    }
    response.declaredConstructors.forEach { constructor ->
        constructor.hookMethod { after { param -> filter(param.thisObject, "friendsFeed") } }
    }
    runCatching { ::friendsFeedSuccessFingerprints.dexMethodList.realMatches() }.getOrNull()
        ?.forEach {
            it.hookMethod { before { param -> param.args.forEach { arg -> filter(arg, "friendsFeedDelivery") } } }
        }
}

private fun filterSingleListField(owner: Any?, source: String) {
    if (owner == null || !AwemeAdFilter.enabled) return
    val field = AwemeAdFilter.singleListField(owner) ?: return
    val list = runCatching { field.get(owner) as? List<*> }.getOrNull() ?: return
    val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return
    runCatching { field.set(owner, kept) }.onSuccess { logRemoved(source, list.size - kept.size) }
}

private fun filterTalentAdResult(result: Any?) {
    if (result == null || !AwemeAdFilter.enabled || result.javaClass.name != TALENT_AD_RESULT_CLASS) return
    logRemoved("talentProfileAds", AwemeAdFilter.filterListField(result, "profileAds", AwemeAdFilter::awemeOf))
}

private fun logRemoved(source: String, removed: Int) {
    if (removed > 0) Logger.printDebug { "$TAG $source: removed $removed item(s)" }
}

private fun PatchExecutor.hookFollowingFeed() {
    val followFeedList = classLoader.loadClass(FOLLOW_FEED_LIST_CLASS)

    followFeedList.getMethod("getItems").hookMethod {
        after { param ->
            val owner = param.thisObject ?: return@after
            val list = param.result as? List<*> ?: return@after
            if (!AwemeAdFilter.markFirstSeen(owner)) return@after
            val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return@after
            val removed = list.size - kept.size
            @Suppress("UNCHECKED_CAST")
            val edited = runCatching { (list as MutableList<Any?>).run { clear(); addAll(kept) } }.isSuccess
            if (!edited) {
                owner.callMethodOrNull("setItems", kept)
                param.result = kept
            }
            logRemoved("followingFeed", removed)
        }
    }

    followFeedList.getMethod("setItems", List::class.java).hookMethod {
        before { param ->
            AwemeAdFilter.filteredCopyOrNull(param.args[0] as? List<*>, AwemeAdFilter::awemeOf)
                ?.let { param.args[0] = it }
        }
    }

    runCatching { followFeedList.getMethod("getAwemeList") }.getOrNull()?.hookMethod {
        after { param ->
            AwemeAdFilter.filteredCopyOrNull(param.result as? List<*>)?.let { param.result = it }
        }
    }
}

// endregion
