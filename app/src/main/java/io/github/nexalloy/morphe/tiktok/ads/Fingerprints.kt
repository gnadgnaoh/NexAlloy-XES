package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.string
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod

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

// region "not found" caching

/*
 * Why every fingerprint in this file goes through [cacheable].
 *
 * Several hook points here legitimately resolve to nothing on a given install: a feature split
 * whose base.apk does not contain DetailFragment, a profile filter TikTok has not copied into that
 * build yet, a log string that was renamed. DexKit does cache such a result, but it caches it as
 * an *empty* list, which SharedPrefCache stores as "" and reads back as "nothing cached"
 * (`takeIf(String::isNotBlank)`). Single-method fingerprints are worse: the default
 * CacheFailurePolicy.NONE never records a miss at all.
 *
 * So every unresolved hook point re-ran its query on every cold start - and because the DexKit
 * bridge is created lazily on the first cache miss, a single one of them re-opened and re-parsed
 * TikTok's 55 dex files. That is what kept startup at ~10s even right after a force close.
 *
 * Fixing this in SharedPrefCache would change caching for every app the module patches, so it is
 * handled here instead, for TikTok's ad hooks only: "found nothing" is recorded as a one-entry
 * list holding a marker method, which caches like any ordinary result. The next launch is then a
 * SharedPreferences read, the bridge is never created, and [realMatches] drops the marker again
 * before anything is hooked.
 */

/**
 * A no-argument method declared on the Aweme Gson model, preferring `isAd()` - the very method
 * [AwemeAdFilter] already relies on. No fingerprint in this file can resolve to a no-argument
 * method of Aweme (they all require parameters, or pin the declaring class elsewhere), so the
 * marker can always be told apart from a real match.
 */
private fun DexKitBridge.notFoundMarker(): List<MethodData> = runCatching {
    findMethod {
        matcher {
            declaredClass = AWEME_CLASS
            name = "isAd"
            paramCount = 0
        }
    }.ifEmpty {
        findMethod {
            matcher {
                declaredClass = AWEME_CLASS
                paramCount = 0
            }
        }
    }.take(1)
}.getOrDefault(emptyList())

/** Runs [find] and turns an empty or failed result into something the cache can store. */
private fun DexKitBridge.cacheable(find: DexKitBridge.() -> List<MethodData>): List<MethodData> =
    runCatching { find() }.getOrDefault(emptyList()).ifEmpty { notFoundMarker() }

/** Drops [notFoundMarker] from a resolved result, leaving only real matches. */
internal fun List<DexMethod>.realMatches(): List<DexMethod> =
    filterNot { it.className == AWEME_CLASS && it.paramTypeNames.isEmpty() }

// endregion

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
                paramCount = 1
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
    custom = { paramCount = 1 },
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
        paramCount = 1
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

/** [ColdStartFeedCacheFingerprint], so that a build without that cache is remembered as such. */
val coldStartFeedCacheFingerprints = findMethodListDirect {
    cacheable { listOf(ColdStartFeedCacheFingerprint.run()) }
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
                        it.paramTypeNames.singleOrNull() == "java.util.List"
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
                        paramTypes("java.util.List", "boolean")
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
                it.isConcrete && it.paramCount == 1 &&
                        it.declaredClassName != TALENT_AD_RESULT_CLASS
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
                paramCount = 1
                returnType = "void"
            }
        }.filter { it.isConcrete }
    }
}

// endregion
