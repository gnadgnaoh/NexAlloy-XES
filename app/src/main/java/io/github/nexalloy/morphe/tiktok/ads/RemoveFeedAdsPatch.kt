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

/**
 * Installs every feed hook once; [RemoveFeedAds] and [HidePromotedMusicVideos] only switch the
 * predicates on. Hook points are independent: one that no longer resolves after a TikTok update
 * is logged and skipped, the patch only fails when the For You response hook itself is gone.
 *
 * Split installs: TikTok is scanned with DexSource.APK_WITH_SPLITS (base.apk + installed feature
 * splits such as df_a_dex). Code living in feature splits (Following feed, detail pager) is still
 * resolved by its kept class name at runtime first, which also covers installs without the split.
 *
 * [AwemeAdFilter] decides what is removed: sponsored videos ([RemoveFeedAds]), promoted-music
 * videos ([HidePromotedMusicVideos]) and TikTok Shop cards ([HideShopAds]).
 */
internal val TikTokFeedFilterHooks = patch(name = "<TikTokFeedFilterHooks>") {
    AwemeAdFilter.init(classLoader)

    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    // region For You

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
            // The payload is found by shape, not by position: every argument is tried.
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

    // endregion

    // region Profile

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

    // endregion

    optional("followingFeed") { hookFollowingFeed() }

    // region Ads outside the For You list (routes from HushFeed / kveld)

    // Mid-roll: puts an ad in place of the video on screen after every list hook has run.
    // Everything it splices is an ad by construction, so the splice is skipped and the video stays.
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

    // A creator's video pager requests its own ads from /tiktok/v1/ad/profile_page/ and splices
    // them between the creator's videos; answering "not eligible" means the request is never sent.
    optional("profilePagerAds") {
        val gates = ::profileAdEligibilityFingerprints.dexMethodList.realMatches()
        check(gates.isNotEmpty()) { "profile_ad_experiment gate not found" }
        gates.forEach {
            it.hookMethod {
                after { param -> if (AwemeAdFilter.hideAds && param.result == true) param.result = false }
            }
        }
    }

    // Search grid: results are SearchMixFeed cards, stamped with the request id before anything
    // reads them; the card list is replaced by its ad-free copy right there.
    optional("searchAds") {
        val results = SEARCH_MIX_FEED_LIST_CLASS.findClassOrNull(classLoader) ?: error("$SEARCH_MIX_FEED_LIST_CLASS not found")
        results.getDeclaredMethod("setRequestId", String::class.java).hookMethod {
            before { param -> filterSearchResults(param.thisObject) }
        }
    }

    optional("friendsFeed") { hookFriendsFeed() }

    // Short-drama ads lock scrolling until they end; TikTok asks this service whether an item is
    // such an ad. Its only (Aweme): Boolean method.
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

    // endregion

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

// region helpers

private fun filterFeedItemList(feedItemList: Any?, source: String) {
    if (feedItemList == null || !AwemeAdFilter.enabled) return
    logRemoved(source, AwemeAdFilter.filterListField(feedItemList, "items"))
    if (AwemeAdFilter.hideAds) clearPreloadAds(feedItemList)
}

/**
 * FeedItemList.preloadAds: TopView ads TikTok preloads for the next start. The feed fetch hands
 * them to the splash service before fetchFeedList returns (Skip splash ads stops that task); the
 * later readers (the list's clone, the commerce preload) get an empty list from here on.
 */
private fun clearPreloadAds(feedItemList: Any) {
    val ads = feedItemList.getObjectFieldOrNull("preloadAds") as? List<*>
    if (ads.isNullOrEmpty()) return
    runCatching { feedItemList.setObjectField("preloadAds", ArrayList<Any?>(0)) }
        .onSuccess { logRemoved("topViewPreload", ads.size) }
}

/**
 * Search results page: SearchMixFeedList.mItems holds SearchMixFeed cards (not Awemes). The list
 * is replaced, never edited in place, and left alone if every card would go: a whole page of ads
 * is far less likely than a changed card shape.
 */
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

/**
 * The Friends tab is a feed of its own: FriendsFeedResponse.friendFeedData holds FriendsFeed
 * wrappers (`aweme` field; LIVE cards carry a room instead and are kept). Gson fills the field
 * after construction and every consumer reads it directly, so it is filtered where a populated
 * response is delivered (onSuccess), read (getAwemeList) or built by TikTok itself (constructor).
 * The field is replaced, never edited in place, so an adapter holding the old list is unaffected.
 */
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

/**
 * Payload / event objects with exactly one List field. That list can be a singletonList or still
 * owned by the sender, so the object gets its own filtered copy.
 */
private fun filterSingleListField(owner: Any?, source: String) {
    if (owner == null || !AwemeAdFilter.enabled) return
    val field = AwemeAdFilter.singleListField(owner) ?: return
    val list = runCatching { field.get(owner) as? List<*> }.getOrNull() ?: return
    val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return
    runCatching { field.set(owner, kept) }.onSuccess { logRemoved(source, list.size - kept.size) }
}

/** ProfileTalentShareAdResult.profileAds: List<ProfileAdData(previousItemId, aweme)>. */
private fun filterTalentAdResult(result: Any?) {
    if (result == null || !AwemeAdFilter.enabled || result.javaClass.name != TALENT_AD_RESULT_CLASS) return
    logRemoved("talentProfileAds", AwemeAdFilter.filterListField(result, "profileAds", AwemeAdFilter::awemeOf))
}

private fun logRemoved(source: String, removed: Int) {
    if (removed > 0) Logger.printDebug { "$TAG $source: removed $removed item(s)" }
}

/**
 * FollowFeedList is a Gson model whose accessors keep their names in every build; in 46.9.1 no
 * class outside FollowFeedList reads its list field directly. The field name itself is not used:
 * it was `mItems` in the builds Morphe targeted and is `items` in 46.9.1.
 *
 * The post-processor walks getItems() by index ~40 times before handing the list to the adapter,
 * so an instance is only cleaned on its first read (API / cache layer) and never afterwards.
 */
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

    // Lists assembled by TikTok itself (load-more merge, in-memory cache restore).
    followFeedList.getMethod("setItems", List::class.java).hookMethod {
        before { param ->
            AwemeAdFilter.filteredCopyOrNull(param.args[0] as? List<*>, AwemeAdFilter::awemeOf)
                ?.let { param.args[0] = it }
        }
    }

    // Reads the field directly and builds a new list on every call.
    runCatching { followFeedList.getMethod("getAwemeList") }.getOrNull()?.hookMethod {
        after { param ->
            AwemeAdFilter.filteredCopyOrNull(param.result as? List<*>)?.let { param.result = it }
        }
    }
}

// endregion
