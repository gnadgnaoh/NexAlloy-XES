package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.string
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import io.github.nexalloy.morphe.tiktok.shared.cacheable

/*
 * Verified with DexKit against TikTok Asia 46.8.3 (base.apk) and TikTok Global 46.9.1 (55 dex).
 * Every obfuscated name differs between the two builds (LLIZ -> LLILZIL, LX/04Le -> LX/04JX,
 * QT -> xT, ...) while all fingerprints below still resolve.
 *
 * Anchors only use what TikTok's obfuscator keeps: Gson models (Aweme, FeedItemList,
 * FollowFeedList, ProfileTalentShareAdResult), interface methods called through ServiceManager,
 * EventBus subscriber names, log strings and *ServiceImpl class names.
 */

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"
internal const val FEED_ITEM_LIST_CLASS = "com.ss.android.ugc.aweme.feed.model.FeedItemList"
internal const val FOLLOW_FEED_LIST_CLASS = "com.ss.android.ugc.aweme.follow.presenter.FollowFeedList"
internal const val DETAIL_FRAGMENT_CLASS = "com.ss.android.ugc.aweme.detail.ui.DetailFragment"
internal const val TALENT_AD_RESULT_CLASS =
    "com.ss.android.ugc.aweme.commercialize.profile.talent.model.ProfileTalentShareAdResult"

private val MethodData.isConcrete get() = modifiers and AccessFlags.ABSTRACT.modifier == 0

// Every fingerprint in this file goes through `cacheable` (shared/CachedLookup.kt), so a hook point
// missing from a build is cached as "not found" instead of re-opening DexKit on every cold start.

// region For You

/**
 * `IFeedApi.fetchFeedList(request)` is looked up by name through ServiceManager. Every concrete
 * implementation is hooked, so future feed APIs implementing IFeedApi are covered as well.
 *
 * The return value is used on purpose instead of the protobuf converter: `fetchInitialFeedStream`
 * reads item[0]'s bitrate right after conversion to preload the first video from the same HTTP
 * stream, filtering earlier would attach an ad's video bytes to the next video.
 */
val feedApiFetchFeedListFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                name = "fetchFeedList"
                returnType = FEED_ITEM_LIST_CLASS
            }
        }.filter { it.isConcrete }
    }
}

/**
 * BaseListFragmentPanel.insertItemList(payload): single choke point for items inserted into a
 * displayed feed (server "ad_rerank", golden-house / play-lag cache, Live inserts...).
 */
internal object FeedInsertItemListFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("insertItemList fall to downgrade logic"),
)

/** Same method described by structure only, used when the log string disappears. */
internal object FeedInsertItemListByStructureFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("ad_rerank"),
        methodCall(
            definingClass = "Lcom/ss/android/ugc/aweme/feed/model/AwemeBizExtKt;",
            name = "setContentDiffType",
        ),
    ),
    custom = {
        declaredClass("BaseListFragmentPanel", StringMatchType.EndsWith)
    },
)

/**
 * [FeedInsertItemListFingerprint] with [FeedInsertItemListByStructureFingerprint] as its fallback,
 * resolved under one cache key. Hooking the two objects directly meant that on a build where the
 * log string is gone the first one missed - and a missed single fingerprint is never cached - so
 * both queries ran again on every launch.
 */
val feedInsertItemListFingerprints = findMethodListDirect {
    cacheable {
        runCatching { listOf(FeedInsertItemListFingerprint.run()) }
            .getOrElse { listOf(FeedInsertItemListByStructureFingerprint.run()) }
    }
}

internal object OfflineVideoHitCacheFingerprint : Fingerprint(
    strings = listOf("processOfflineVideoHitCache error"),
)

/** Cold-start cache of the previous session: the static FeedItemList getter of that class. */
internal object ColdStartFeedCacheFingerprint : Fingerprint(
    classFingerprint = OfflineVideoHitCacheFingerprint,
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    parameters = listOf(),
)

/** Upper bound for the structural fallback; a handful of cache readers exist (2 on 47.1.4). */
private const val MAX_FEED_CACHE_GETTERS = 6

/**
 * Every static, no-argument `FeedItemList` getter: the feed caches read at cold start.
 *
 * 47.1.4 dropped the "processOfflineVideoHitCache" log the legacy fingerprint was anchored on and
 * reads the previous session from two places instead ("getColdCacheDB", "tryUseCache"). Anchoring
 * on the Gson model type rather than on log text keeps this working across such rewrites. Every
 * match only feeds [filterFeedItemList], which removes ads and leaves everything else untouched,
 * so hooking one cache reader too many is harmless.
 */
private fun DexKitBridge.staticFeedCacheGetters(): List<MethodData> =
    findMethod {
        matcher {
            returnType = FEED_ITEM_LIST_CLASS
            paramCount = 0
            modifiers(Modifier.STATIC)
        }
    }.filter { Modifier.isStatic(it.modifiers) && !it.isConstructor }
        .also { check(it.size <= MAX_FEED_CACHE_GETTERS) { "too many FeedItemList cache getters: ${it.size}" } }

/** [ColdStartFeedCacheFingerprint], so that a build without that cache is remembered as such. */
val coldStartFeedCacheFingerprints = findMethodListDirect {
    cacheable {
        runCatching { listOf(ColdStartFeedCacheFingerprint.run()) }
            .getOrElse { staticFeedCacheGetters() }
    }
}

// endregion

// region Profile

/**
 * Native "talent ad revenue share" list filters run before a video grid is displayed:
 * AwemeListFragmentImpl (profile tabs) and MentionPostedAndLikeVideoVM (Global 46.9.1).
 * A list on purpose: TikTok copies this filter into more view models over time.
 *
 * Resolved from `Aweme.getAwemeRawAd`'s callers instead of describing the filter with
 * `addInvoke { declaredClass(..., Contains) }`. That matcher had no anchor DexKit could use, so it
 * decoded every method body in all 55 dex files and substring-compared the owner of every invoke
 * it found - by far the most expensive query these patches ran. Resolving one exact method is an
 * index lookup, `callers` is a single cross-reference pass, and the remaining conditions are then
 * checked on the handful of methods that survive.
 */
val videoGridAdListFilterFingerprints = findMethodListDirect {
    cacheable {
        val getAwemeRawAd = findMethod {
            matcher {
                declaredClass = AWEME_CLASS
                name = "getAwemeRawAd"
            }
        }.firstOrNull() ?: return@cacheable emptyList()

        getAwemeRawAd.callers
            .filter {
                it.isConcrete &&
                        it.returnTypeName == "java.util.List" &&
                        "java.util.List" in it.paramTypeNames
            }
            .filter { caller ->
                caller.invokes.any { it.declaredClassName.contains("TalentAdRevenueShareService") }
            }
            .distinctBy { it.descriptor }
    }
}

/** Fallback for [videoGridAdListFilterFingerprints]: result callbacks anchored on their logs. */
val profileResultCallbackFingerprints = findMethodListDirect {
    cacheable {
        listOf("onRefreshResult: type=", "onLoadMoreResult: type=", "onLoadLatestResult: type=")
            .flatMap { log ->
                findMethod {
                    matcher {
                        usingStrings(listOf(log), StringMatchType.Equals)
                        returnType = "void"
                    }
                }
            }
            .distinctBy { it.descriptor }
    }
}

/**
 * Callback merging ProfileTalentShareAdResult.profileAds into a loaded profile grid.
 *
 * Found through the field's own reader cross-reference. The previous
 * `findMethod { paramCount = 1; addUsingField(...) }` had no cheap anchor either, so DexKit
 * decoded the body of every one-argument method in the apk.
 */
val talentProfileAdsCallbackFingerprints = findMethodListDirect {
    cacheable {
        val profileAds = findField {
            matcher {
                declaredClass = TALENT_AD_RESULT_CLASS
                name = "profileAds"
                type = "java.util.List"
            }
        }.firstOrNull() ?: return@cacheable emptyList()

        profileAds.readers
            .filter {
                it.isConcrete && it.declaredClassName != TALENT_AD_RESULT_CLASS
            }
            .distinctBy { it.descriptor }
    }
}

/**
 * DexKit fallback for DetailFragment.onTalentProfileAdEvent (EventBus subscriber, never
 * obfuscated). Empty on split installs whose base.apk does not contain DetailFragment; the patch
 * resolves it by name at runtime first.
 */
val talentProfileAdEventSubscriberFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                name = "onTalentProfileAdEvent"
                returnType = "void"
            }
        }.filter { it.isConcrete }
    }
}

// endregion

// region TikTok Shop

/*
 * TikTok Shop cards in the For You feed ("EC card": product, price, voucher, "Not interested" /
 * "Claim" buttons), hidden by [HideShopAds]. Verified with DexKit against TikTok Asia 47.0.3
 * (base + df_a_dex) and TikTok Global 47.1.4.
 *
 * How such a card gets into the feed:
 *  1. Before each For You request, the card-insert platform asks every provider which card types
 *     it wants. The Shop provider is IFeedEcCardService: it returns CardTypeRequest(4) - the
 *     native EC card - plus the Lynx EC card types listed in the `feed_ec_lynx_card_config`
 *     setting.
 *  2. The server answers with the card inside FeedItemList.items: an Aweme whose
 *     `cardInsertInfo.cardType` is the requested type.
 *
 * Anchors: the two service interfaces are looked up by class through ServiceManager / SPI, so
 * their names are kept; FeedCardInsertData, CardTypeRequest, Aweme.getCardInsertInfo() and
 * CardInsertInfo.getCardType() are Gson / proto models. Method names (LIZ, LIZJ, ...) and the
 * implementation classes are not relied on: implementations are found through the interface.
 */

private const val FEED_EC_CARD_SERVICE =
    "com.ss.android.ugc.aweme.ecommerce.ug.feedeccard.service.IFeedEcCardService"
private const val EC_SEARCH_FEED_CARD_SERVICE = "com.ss.android.ugc.aweme.feedcard.IEcSearchFeedCardService"
private const val FEED_CARD_INSERT_DATA_CLASS = "com.ss.android.ugc.feed.platform.cardinsert.data.FeedCardInsertData"

/** `CardTypeRequest(4, ...)`, added by IFeedEcCardService on every request it lets through. */
internal const val NATIVE_EC_CARD_TYPE = 4

/** Instance method with a body: implementations of the service interfaces, not the interfaces. */
private val MethodData.isConcreteInstanceMethod
    get() = !isConstructor && modifiers and (Modifier.ABSTRACT or Modifier.STATIC) == 0

/**
 * The Shop provider's request builder, on every IFeedEcCardService implementation: the method
 * that takes the card-insert state (FeedCardInsertData) and returns the CardTypeRequests to send
 * with the next For You request. Recognised by that input type and the List result, so the
 * argument order does not matter.
 */
val ecCardRequestFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                declaredClass { addInterface(FEED_EC_CARD_SERVICE) }
                returnType = "java.util.List"
            }
        }.filter { it.isConcreteInstanceMethod && FEED_CARD_INSERT_DATA_CLASS in it.paramTypeNames }
    }
}

/**
 * The card types of Shop *search* cards ("keyword + magnifier" cards), read from the
 * `ec_feed_preload_image_card_list` setting: the only no-argument List getter of
 * IEcSearchFeedCardService (the other no-argument method returns a Map).
 */
val ecSearchCardTypeGetterFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                declaredClass { addInterface(EC_SEARCH_FEED_CARD_SERVICE) }
                returnType = "java.util.List"
                paramCount = 0
            }
        }.filter { it.isConcreteInstanceMethod }
    }
}

// endregion

// region Splash and other ad routes

/*
 * Ads that never travel through the For You list, ported from HushFeed ("Skip the splash ad",
 * Feed filter routes) and kveld ("Instant Launch & Splash Blocker"), re-anchored for DexKit.
 * Verified against TikTok Asia 47.0.3 and TikTok Global 47.1.4, each with its df_a_dex split.
 * Classes with kept names (splash tasks, MidAdComponent, SearchMixFeedList, FriendsFeedResponse,
 * DramaBlockingAdServiceImpl) are hooked by name in the patches; only methods whose names R8
 * changes on every build are fingerprinted here, by their log strings or kept-name fields.
 */

internal const val MID_AD_COMPONENT_CLASS = "com.ss.android.ugc.feed.platform.panel.midad.MidAdComponent"
internal const val FRIENDS_FEED_RESPONSE_CLASS = "com.ss.android.ugc.aweme.friendstab.api.FriendsFeedResponse"
internal const val SEARCH_MIX_FEED_LIST_CLASS =
    "com.ss.android.ugc.aweme.search.pages.result.topsearch.core.model.SearchMixFeedList"
internal const val DRAMA_BLOCKING_AD_SERVICE_CLASS = "com.ss.android.ugc.aweme.impl.DramaBlockingAdServiceImpl"

/**
 * Log lines of the splash ad show manager's decision `(launchType: Int, Context): Boolean`, where
 * launch type 1 is a cold start and 2 a return from the background (LX/05Sz.LJI on Asia 47.0.3,
 * LX/05W5.LJI on Global 47.1.4). Each of them is only used by that method; any one is enough.
 */
private val SPLASH_DECISION_LOGS = listOf(
    "coldShowSplash has splash",
    "coldHasSplash = ",
    "warmCanShowTopView has no splash",
    "warmCanShowTopView false, splashCheck=false",
)

/** Whether a splash (or TopView) ad is shown now, for both cold and warm starts. */
val splashShowDecisionFingerprints = findMethodListDirect {
    cacheable {
        SPLASH_DECISION_LOGS
            .flatMap { log ->
                findMethod {
                    matcher {
                        usingStrings(listOf(log), StringMatchType.Equals)
                        returnType = "boolean"
                    }
                }
            }
            .distinctBy { it.descriptor }
    }
}

/**
 * MidAdComponent's splice: finds the video on screen in the pager adapter and puts a mid-roll ad
 * in its place, after every list hook has already run. Its only `midroll_ads_show` user; the
 * method name changes per build (`Dq` / `uq`).
 */
val midRollAdSpliceFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                declaredClass = MID_AD_COMPONENT_CLASS
                usingStrings(listOf("midroll_ads_show"), StringMatchType.Equals)
                returnType = "void"
            }
        }
    }
}

/**
 * TikTok's answer to "should this creator's video pager ask /ad/profile_page/ for ads"
 * (`static (User): Boolean`, reads the `profile_ad_experiment` setting). The other user of the
 * string is the void method registering the setting.
 */
val profileAdEligibilityFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("profile_ad_experiment"), StringMatchType.Equals)
                returnType = "boolean"
            }
        }
    }
}

/**
 * Where a fetched Friends tab page is delivered: the `onSuccess(Object)` callback that reads
 * `FriendsFeedResponse.friendFeedData` (field and callback keep their names; the class is R8's).
 */
val friendsFeedSuccessFingerprints = findMethodListDirect {
    cacheable {
        findField {
            matcher {
                declaredClass = FRIENDS_FEED_RESPONSE_CLASS
                name = "friendFeedData"
            }
        }.firstOrNull()
            ?.readers
            ?.filter { it.name == "onSuccess" && it.isConcrete }
            ?.distinctBy { it.descriptor }
            .orEmpty()
    }
}

// endregion
