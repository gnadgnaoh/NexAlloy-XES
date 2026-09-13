package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.string
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.MethodData

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
    findMethod {
        matcher {
            name = "fetchFeedList"
            returnType = FEED_ITEM_LIST_CLASS
            paramCount = 1
        }
    }.filter { it.isConcrete }
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

// endregion

// region Profile

/**
 * Native "talent ad revenue share" list filters run before a video grid is displayed:
 * AwemeListFragmentImpl (profile tabs) and MentionPostedAndLikeVideoVM (Global 46.9.1).
 * A list on purpose: TikTok copies this filter into more view models over time.
 */
val videoGridAdListFilterFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "java.util.List"
            paramTypes("java.util.List")
            addInvoke { declaredClass("TalentAdRevenueShareService", StringMatchType.Contains) }
            addInvoke {
                declaredClass(AWEME_CLASS)
                name = "getAwemeRawAd"
            }
        }
    }.filter { it.isConcrete }
}

/** Fallback for [videoGridAdListFilterFingerprints]: result callbacks anchored on their logs. */
val profileResultCallbackFingerprints = findMethodListDirect {
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

/** Callback merging ProfileTalentShareAdResult.profileAds into a loaded profile grid. */
val talentProfileAdsCallbackFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            paramCount = 1
            addUsingField(
                "L${TALENT_AD_RESULT_CLASS.replace('.', '/')};->profileAds:Ljava/util/List;",
                UsingType.Read,
            )
        }
    }.filter { it.isConcrete && it.declaredClassName != TALENT_AD_RESULT_CLASS }
}

/**
 * DexKit fallback for DetailFragment.onTalentProfileAdEvent (EventBus subscriber, never
 * obfuscated). Empty on split installs whose base.apk does not contain DetailFragment; the patch
 * resolves it by name at runtime first.
 */
val talentProfileAdEventSubscriberFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            name = "onTalentProfileAdEvent"
            paramCount = 1
            returnType = "void"
        }
    }.filter { it.isConcrete }
}

// endregion
