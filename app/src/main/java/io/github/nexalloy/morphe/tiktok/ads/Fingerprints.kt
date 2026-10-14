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

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"
internal const val FEED_ITEM_LIST_CLASS = "com.ss.android.ugc.aweme.feed.model.FeedItemList"
internal const val FOLLOW_FEED_LIST_CLASS = "com.ss.android.ugc.aweme.follow.presenter.FollowFeedList"
internal const val DETAIL_FRAGMENT_CLASS = "com.ss.android.ugc.aweme.detail.ui.DetailFragment"
internal const val TALENT_AD_RESULT_CLASS =
    "com.ss.android.ugc.aweme.commercialize.profile.talent.model.ProfileTalentShareAdResult"

private val MethodData.isConcrete get() = modifiers and AccessFlags.ABSTRACT.modifier == 0

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

internal object FeedInsertItemListFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("insertItemList fall to downgrade logic"),
)

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

val feedInsertItemListFingerprints = findMethodListDirect {
    cacheable {
        runCatching { listOf(FeedInsertItemListFingerprint.run()) }
            .getOrElse { listOf(FeedInsertItemListByStructureFingerprint.run()) }
    }
}

internal object OfflineVideoHitCacheFingerprint : Fingerprint(
    strings = listOf("processOfflineVideoHitCache error"),
)

internal object ColdStartFeedCacheFingerprint : Fingerprint(
    classFingerprint = OfflineVideoHitCacheFingerprint,
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    parameters = listOf(),
)

private const val MAX_FEED_CACHE_GETTERS = 6

private fun DexKitBridge.staticFeedCacheGetters(): List<MethodData> =
    findMethod {
        matcher {
            returnType = FEED_ITEM_LIST_CLASS
            paramCount = 0
            modifiers(Modifier.STATIC)
        }
    }.filter { Modifier.isStatic(it.modifiers) && !it.isConstructor }
        .also { check(it.size <= MAX_FEED_CACHE_GETTERS) { "too many FeedItemList cache getters: ${it.size}" } }

val coldStartFeedCacheFingerprints = findMethodListDirect {
    cacheable {
        runCatching { listOf(ColdStartFeedCacheFingerprint.run()) }
            .getOrElse { staticFeedCacheGetters() }
    }
}

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

private const val FEED_EC_CARD_SERVICE =
    "com.ss.android.ugc.aweme.ecommerce.ug.feedeccard.service.IFeedEcCardService"
private const val EC_SEARCH_FEED_CARD_SERVICE = "com.ss.android.ugc.aweme.feedcard.IEcSearchFeedCardService"
private const val FEED_CARD_INSERT_DATA_CLASS = "com.ss.android.ugc.feed.platform.cardinsert.data.FeedCardInsertData"

internal const val NATIVE_EC_CARD_TYPE = 4

private val MethodData.isConcreteInstanceMethod
    get() = !isConstructor && modifiers and (Modifier.ABSTRACT or Modifier.STATIC) == 0

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

internal const val MID_AD_COMPONENT_CLASS = "com.ss.android.ugc.feed.platform.panel.midad.MidAdComponent"
internal const val FRIENDS_FEED_RESPONSE_CLASS = "com.ss.android.ugc.aweme.friendstab.api.FriendsFeedResponse"
internal const val SEARCH_MIX_FEED_LIST_CLASS =
    "com.ss.android.ugc.aweme.search.pages.result.topsearch.core.model.SearchMixFeedList"
internal const val DRAMA_BLOCKING_AD_SERVICE_CLASS = "com.ss.android.ugc.aweme.impl.DramaBlockingAdServiceImpl"

private val SPLASH_DECISION_LOGS = listOf(
    "coldShowSplash has splash",
    "coldHasSplash = ",
    "warmCanShowTopView has no splash",
    "warmCanShowTopView false, splashCheck=false",
)

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
