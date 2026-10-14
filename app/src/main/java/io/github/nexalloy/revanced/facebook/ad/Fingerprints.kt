package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.revanced.facebook.GRAPHQL_FEED_UNIT_EDGE_CLASS
import io.github.nexalloy.revanced.facebook.isLoaderInfra
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private fun MethodData.isConcreteHookTarget(): Boolean {
    if (isConstructor || Modifier.isAbstract(modifiers)) return false
    val ownerModifiers = declaredClass?.modifiers ?: return true
    return !Modifier.isInterface(ownerModifiers) && !Modifier.isAbstract(ownerModifiers)
}

private fun MethodData.isHookableMethod(): Boolean =
    isMethod && !Modifier.isAbstract(modifiers)

val adKindEnumFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("AD", "UGC", "PARADE", "MIDCARD") }
    }.first()
}

val listBuilderClassFingerprint = findClassDirect {
    val structural = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "void"
                    paramCount(5, 7)
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramCount(5, 7)
                }
                add {
                    returnType = "java.util.ArrayList"
                    paramCount(4, 6)
                }
                add {
                    returnType = "java.util.List"
                    paramCount(4, 6)
                }
            }
        }
    }

    structural.singleOrNull()
        ?: findClass {
            matcher { usingStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
        }.firstOrNull()
        ?: error("Unable to resolve the upstream Facebook reels list-builder class")
}

val pluginPackMethodsFingerprint = findMethodListDirect {
    listOf("FbShortsViewerPluginPack", "MarketplaceAdsPluginPack").flatMap { tag ->
        findClass {
            matcher {
                methods {
                    add { returnType = "java.lang.String"; paramCount = 0; usingStrings(tag) }
                    add { returnType = "java.util.List"; paramCount = 0 }
                }
            }
        }.flatMap { cls ->
            cls.findMethod { matcher { returnType = "java.util.List"; paramCount = 0 } }
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

val instreamBannerEligibilityClassFingerprint = findClassDirect {
    findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add { returnType = "java.lang.String"; paramCount = 0; usingStrings("InstreamAdIdleWithBannerState") }
            }
        }
    }.firstOrNull() ?: error("Unable to resolve the instream banner eligibility class")
}

val indicatorPillAdEligibilityFingerprint = findMethodDirect {
    val candidates = findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }
    candidates.firstNotNullOfOrNull { cls ->
        cls.findMethod {
            findFirst = true
            matcher { modifiers = Modifier.STATIC; returnType = "boolean"; paramCount = 3 }
        }.firstOrNull()
    } ?: error("Unable to resolve the Reels indicator pill ad eligibility method")
}

val reelsBannerRenderMethodsFingerprint = findMethodListDirect {
    val bannerRenders = runCatching {
        methodsUsingAnyOf(listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent"))
            .filter { m -> m.paramTypeNames.size == 1 && !m.isConstructor }
    }.getOrDefault(emptyList())

    val asyncAdsDispatch = runCatching {
        methodsUsingAnyOf(listOf("TRENDING_ADS_TRIGGERED_INTERSTITIAL"))
            .filter { m -> m.returnTypeName == "void" && !m.isConstructor && !Modifier.isAbstract(m.modifiers) }
    }.getOrDefault(emptyList())

    val sponsoredSlotQueueAdds = runCatching {
        classesUsingAnyOf(listOf("FbShortsCSRSponsoredSlotQueue")).flatMap { cls ->
            cls.findMethod { matcher { returnType = "void"; paramCount = 1 } }
        }.filter { m -> !m.isConstructor && !Modifier.isAbstract(m.modifiers) }
    }.getOrDefault(emptyList())

    (bannerRenders + asyncAdsDispatch + sponsoredSlotQueueAdds).distinctBy { it.descriptor }
}

val profileReelsAsyncAdsQueryFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(
                "com.facebook.auth.usersession.FbUserSession",
                "java.lang.Integer",
                "java.lang.Integer",
                "boolean"
            )
            usingStrings("ProfileReelsAsyncAdsQuery")
        }
    }.first { !it.isConstructor }
}

private val FEED_CSR_FILTER_TAGS = listOf(
    "FeedCSRCacheFilter",
    "FeedCSRCacheFilter2025H1",
    "FeedCSRCacheFilter2026H1",
    "FeedCSRCacheFilter2026H2",
    "FeedCSRCacheFilter2027H1",
    "FeedCSRCacheFilter2027H2",
    "FriendlyFeedCacheFilter",
    "FbShortsCSRCacheFilter",
)

val feedCsrFilterMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(FEED_CSR_FILTER_TAGS).flatMap { cls ->
        run {
            val fourParam = cls.findMethod {
                matcher {
                    paramTypes(
                        "com.facebook.auth.usersession.FbUserSession",
                        null,
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }
            if (fourParam.isNotEmpty()) fourParam else cls.findMethod {
                matcher {
                    paramTypes(
                        "com.facebook.auth.usersession.FbUserSession",
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }
        }
    }.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

private val LATE_FEED_LIST_TAGS = listOf(
    "handleStorageStories",
    "cancelVendingTimerAndAddToPool_",
    "Empty Storage List",
    "CSRNoOpStorageLifecycleImpl",
    "FeedCSRStorageLifecycle",
    "FriendlyFeedCSRStorageLifecycle",
    "FbShortsCSRStorageLifecycle",
)

val lateFeedListMethodsFingerprint = findMethodListDirect {
    val fbUserSession = "com.facebook.auth.usersession.FbUserSession"
    val immutableList = "com.google.common.collect.ImmutableList"

    val shapes: List<List<String?>> = listOf(
        listOf(null, immutableList, "int"),
        listOf(immutableList, "java.lang.String"),
        listOf(fbUserSession, null, immutableList),
        listOf(fbUserSession, null, null, immutableList),
        listOf(immutableList),
    )

    val results = ArrayList<MethodData>()
    classesUsingAnyOf(LATE_FEED_LIST_TAGS).forEach { cls ->
        shapes.forEach { shape ->
            runCatching {
                cls.findMethod { matcher { returnType = "void"; paramTypes(shape) } }
            }.getOrDefault(emptyList()).forEach { results.add(it) }
        }
    }

    results.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

val STORY_POOL_TAGS = listOf(
    "CSRStoryPoolCoordinator",
    "FeedStoryPoolCoordinator",
    "FbShortsSponsoredPool",
    "FBShortsSponsoredPool",
    "FbShortsIFUSponsoredPool",
    "FriendlyFeedSponsoredPool",
    "FbShortsCSRSponsoredSlotQueue",
    "FbShortsCSRCacheFilter",
    "StoryPoolCoordinator",
    "FbShortsPoolContainerAdapter",
    "FBShortsStoryPool",
    "FriendlyFeedStoryPool",
    "StoriesStoryPool",
    "HoistStoryPool",
    "OfflineFeedStoryPool",
)

val storyPoolAddMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(STORY_POOL_TAGS).flatMap { cls ->
        cls.findMethod { matcher { returnType = "boolean"; paramCount = 1 } }
    }.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

val sponsoredPoolClassFingerprint = findClassDirect {
    val candidates = findClass {
        matcher { usingEqStrings("SponsoredPoolContainerAdapter", "Edge type mismatch; not added") }
    }
    candidates.firstOrNull { cls ->
        cls.findMethod {
            matcher { returnType = "boolean"; paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
        }.isNotEmpty()
    } ?: error("Unable to resolve the Facebook sponsored pool class")
}

val sponsoredPoolAddMethodFingerprint = findMethodDirect {
    sponsoredPoolClassFingerprint().findMethod {
        matcher { returnType = "boolean"; paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
    }.single()
}

val sponsoredStoryManagerClassFingerprint = findClassDirect {
    val candidates = findClass {
        matcher { usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder") }
    }
    candidates.firstOrNull { cls ->
        cls.findMethod {
            matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
        }.isNotEmpty()
    } ?: error("Unable to resolve the Facebook sponsored story manager class")
}

val sponsoredStoryNextMethodFingerprint = findMethodDirect {
    sponsoredStoryManagerClassFingerprint().findMethod {
        matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
    }.single()
}

private val STORY_AD_STORE_TAGS = listOf(
    "AdsPaginatingNetworkAdBucketFetcher",
    "FbStoryAdInDiscStoreImpl",
    "IN_DISC_METADATA_KEY",
    "AD_BUCKETS_KEY",
)

private fun ClassData.hasStoryAdProviderShape(): Boolean =
    findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
        }
    }.isNotEmpty() && findMethod {
        matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList") }
    }.isNotEmpty()

private fun DexKitBridge.storyAdProviderClasses(): List<ClassData> {
    val byStore = classesUsingAnyOf(STORY_AD_STORE_TAGS).filter { it.hasStoryAdProviderShape() }
    if (byStore.isNotEmpty()) return byStore.distinctBy { it.name }
    return findMethod { matcher { usingStrings("ads_deletion") } }
        .mapNotNull { it.declaredClass }
        .filter { it.hasStoryAdProviderShape() }
        .distinctBy { it.name }
}

val storyAdsInDiscClassFingerprint = findClassDirect {
    storyAdProviderClasses().firstOrNull() ?: error("Story ad provider class not found")
}

val storyAdsInsertionTriggerMethodFingerprint = findMethodDirect {
    storyAdsInDiscClassFingerprint().findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("ads_insertion")
        }
    }.first()
}

val gameAdRequestMethodsFingerprint = findMethodListDirect {
    listOf(
        "Invalid JSON content received by onGetInterstitialAdAsync: ",
        "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
        "Invalid JSON content received by onRewardedVideoAsync: ",
        "Invalid JSON content received by onLoadAdAsync: ",
        "Invalid JSON content received by onShowAdAsync: "
    ).let { tags ->
        methodsUsingAnyOf(tags).filter {
            it.returnTypeName == "void" && it.paramTypeNames == listOf("org.json.JSONObject")
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

val feedCollectionAddEdgeMethodFingerprint = findMethodDirect {
    val byShape = findMethod {
        matcher {
            name = "addNewEdgeToCollection"
            returnType = "boolean"
            paramTypes(null, "com.facebook.graphql.model.GraphQLFeedUnitEdge", null)
        }
    }.filter { it.isConcreteHookTarget() }

    byShape.firstOrNull()
        ?: findMethod {
            matcher { name = "addNewEdgeToCollection"; returnType = "boolean" }
        }.first {
            it.isConcreteHookTarget() &&
                it.paramTypeNames.any { p -> p == "com.facebook.graphql.model.GraphQLFeedUnitEdge" }
        }
}

val storyAdsInDiscMethodsFingerprint = findMethodListDirect {
    storyAdProviderClasses().mapNotNull { cls -> cls.methods.firstOrNull { it.isMethod } }
}

val allPluginPackListMethodsFingerprint = findMethodListDirect {
    val seed = listOf("FbShortsViewerPluginPack", "MarketplaceAdsPluginPack", "AdBreakPluginPack")
        .firstNotNullOfOrNull { tag ->
            runCatching {
                findClass {
                    matcher {
                        methods {
                            add { returnType = "java.lang.String"; paramCount = 0; usingStrings(tag) }
                            add { returnType = "java.util.List"; paramCount = 0 }
                        }
                    }
                }.firstOrNull()
            }.getOrNull()
        } ?: error("No plugin pack to seed the list-getter shape from")

    val getter = seed.methods.firstOrNull {
        it.paramTypeNames.isEmpty() && it.returnTypeName == "java.util.List"
    } ?: error("Plugin pack list getter shape not found")

    findMethod {
        matcher { name = getter.name; paramCount = 0; returnType = "java.util.List" }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

val pluginDescriptorGateMethodsFingerprint = findMethodListDirect {
    val seed = listOf("PlayableAdOverlayPluginDescriptor", "AdsSmartOverlayPluginDescriptor")
        .firstNotNullOfOrNull { tag ->
            runCatching { findClass { matcher { usingStrings(tag) } }.firstOrNull() }.getOrNull()
        } ?: error("No ad plugin descriptor to seed the gate shape from")

    val gate = seed.methods.firstOrNull {
        it.returnTypeName == "boolean" &&
            it.paramTypeNames.size == 4 &&
            it.paramTypeNames[1] == "com.facebook.video.common.playerorigin.PlayerOrigin"
    } ?: error("Plugin descriptor gate shape not found")

    findMethod {
        matcher {
            name = gate.name
            returnType = "boolean"
            paramTypes(null, "com.facebook.video.common.playerorigin.PlayerOrigin", null, null)
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

val directMonetizationAdsPluginListFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            usingStrings("REELS_DIRECT_MONETIZATION_ADS")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

private val NON_RENDER_RETURN_TYPES = setOf(
    "java.lang.String", "void", "boolean", "int", "long", "float", "double", "char", "byte", "short"
)

private fun MethodData.isRenderShaped(): Boolean =
    !isConstructor && returnTypeName !in NON_RENDER_RETURN_TYPES

private fun DexKitBridge.classesUsingAnyOf(tags: List<String>): List<ClassData> {
    if (tags.isEmpty()) return emptyList()
    runCatching {
        batchFindClassUsingStrings { groups(tags.associateWith { listOf(it) }) }
    }.getOrNull()?.let { batched ->
        return batched.values.flatten().distinctBy { it.descriptor }
    }
    return tags.flatMap { tag ->
        runCatching { findClass { matcher { usingStrings(tag) } }.toList() }.getOrDefault(emptyList())
    }.distinctBy { it.descriptor }
}

private fun DexKitBridge.methodsUsingAnyOf(tags: List<String>): List<MethodData> =
    methodsByTag(tags).values.flatten().distinctBy { it.descriptor }

private fun DexKitBridge.methodsByTag(tags: List<String>): Map<String, List<MethodData>> {
    if (tags.isEmpty()) return emptyMap()
    runCatching {
        batchFindMethodUsingStrings { groups(tags.associateWith { listOf(it) }) }
    }.getOrNull()?.let { batched ->
        return batched.mapValues { it.value.toList() }
    }
    return tags.associateWith { tag ->
        runCatching { findMethod { matcher { usingStrings(tag) } }.toList() }.getOrDefault(emptyList())
    }
}

private fun DexKitBridge.renderReturnTypeFrom(seedTags: List<String>): String? =
    seedTags.firstNotNullOfOrNull { tag ->
        runCatching {
            findClass { matcher { usingStrings(tag) } }
                .flatMap { cls -> cls.methods.filter { it.paramTypeNames.size == 1 } }
                .firstOrNull { it.isRenderShaped() }
                ?.returnTypeName
        }.getOrNull()
    }

val timelineStoryRenderMethodFingerprint = findMethodDirect {
    val renderType = renderReturnTypeFrom(
        listOf("ReelsBannerAdsComponent", "FbShortsAdsRootKComponent.render")
    ) ?: error("Litho render type not found")

    findClass {
        matcher { usingStrings("sponsored_timeline_stories_test_key") }
    }.flatMap { cls ->
        cls.findMethod { matcher { paramCount = 1; returnType = renderType } }
    }.first { it.isConcreteHookTarget() }
}

val sponsoredStoryVendorMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(
        listOf(
            "FeedSponsoredStoryHolder.getTopValidAd",
            "FeedSponsoredStoryHolder.rerankAdsForGetBestAdStory",
        )
    ).filter {
        it.returnTypeName == GRAPHQL_FEED_UNIT_EDGE_CLASS && it.isConcreteHookTarget()
    }.distinctBy { it.descriptor }
}

val quicksilverAdsVoltronGateFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            paramCount = 0
            usingStrings("QuicksilverAdsVoltronModule")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

val quicksilverBannerAdLoaderMethodsFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("com.facebook.quicksilver.adscommon.QuicksilverBannerAdsHandlerImpl")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

private const val IMMUTABLE_LIST_CLASS = "com.google.common.collect.ImmutableList"
private const val FB_USER_SESSION_CLASS = "com.facebook.auth.usersession.FbUserSession"
private const val SEARCH_RESULTS_CONTEXT_CLASS =
    "com.facebook.search.results.model.SearchResultsMutableContext"

private val SEARCH_AD_UNIT_TYPE_ANCHORS = listOf(
    "TOP_POSITION_SEARCH_ADS",
    "SEARCH_ADS",
    "DEPENDENT_SEARCH_ADS",
    "LATE_DEPENDENT_SEARCH_ADS",
)

private fun DexKitBridge.searchResultUnitTypeEnumClass(): ClassData =
    findClass { matcher { usingEqStrings(SEARCH_AD_UNIT_TYPE_ANCHORS) } }
        .firstOrNull() ?: error("Search result unit type enum not found")

val searchResultUnitTypeEnumFingerprint = findClassDirect { searchResultUnitTypeEnumClass() }

val searchResultUnitListMethodFingerprint = findMethodDirect {
    val enumName = searchResultUnitTypeEnumClass().name
    findClass {
        matcher {
            fields { addForType(enumName) }
            methods {
                add {
                    modifiers = Modifier.STATIC
                    paramTypes(IMMUTABLE_LIST_CLASS)
                    returnType = IMMUTABLE_LIST_CLASS
                }
            }
        }
    }.flatMap { cls ->
        runCatching {
            cls.findMethod {
                matcher {
                    modifiers = Modifier.STATIC
                    paramTypes(IMMUTABLE_LIST_CLASS)
                    returnType = IMMUTABLE_LIST_CLASS
                }
            }.toList()
        }.getOrDefault(emptyList())
    }.first { it.isConcreteHookTarget() }
}

private fun DexKitBridge.searchAdsControllerClass(): ClassData =
    findMethod {
        matcher {
            name = "<init>"
            paramTypes(FB_USER_SESSION_CLASS, SEARCH_RESULTS_CONTEXT_CLASS, "int")
        }
    }.firstNotNullOfOrNull { it.declaredClass } ?: error("Search ads controller not found")

val searchAdsLoadedStateConstructorFingerprint = findMethodDirect {
    val stateBaseName = searchAdsControllerClass().fields
        .map { it.typeName }
        .firstOrNull { name -> name.isObfuscatedAppType() }
        ?: error("Search ads state base class not found")

    findClass {
        matcher {
            superClass = stateBaseName
            methods {
                add {
                    name = "<init>"
                    paramTypes(IMMUTABLE_LIST_CLASS, "boolean")
                }
            }
        }
    }.flatMap { cls ->
        runCatching {
            cls.findMethod {
                matcher {
                    name = "<init>"
                    paramTypes(IMMUTABLE_LIST_CLASS, "boolean")
                }
            }.toList()
        }.getOrDefault(emptyList())
    }.first()
}

private fun String.isObfuscatedAppType(): Boolean =
    '.' in this && !endsWith("[]") &&
        listOf("java.", "javax.", "kotlin.", "android.", "androidx.", "com.", "org.")
            .none { startsWith(it) }

private val SEARCH_AD_COMPONENT_TAGS = listOf(
    "SearchResultsSponsoredStory",
    "SearchResultsSponsoredMultiStory",
    "SearchSerpAd",
    "SearchAdCard",
)

val searchAdComponentRenderMethodsFingerprint = findMethodListDirect {
    val renderType = renderReturnTypeFrom(
        listOf("ReelsBannerAdsComponent", "FbShortsAdsRootKComponent.render")
    ) ?: return@findMethodListDirect emptyList()

    classesUsingAnyOf(SEARCH_AD_COMPONENT_TAGS).flatMap { cls ->
        runCatching {
            cls.findMethod { matcher { paramCount = 1; returnType = renderType } }.toList()
        }.getOrDefault(emptyList())
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

val videoViewerExtensionGateMethodsFingerprint = findMethodListDirect {
    val seed = listOf("InstreamAdsViewerCoordinatorExtension", "InstreamAdsFooterExtension")
        .firstNotNullOfOrNull { tag ->
            runCatching {
                findClass {
                    matcher {
                        methods {
                            matchType = MatchType.Contains
                            add { returnType = "java.lang.String"; paramCount = 0; usingStrings(tag) }
                        }
                    }
                }.firstOrNull()
            }.getOrNull()
        } ?: error("No instream ads viewer extension to seed the extension shape from")

    val nameGetter = seed.methods.firstOrNull {
        it.paramTypeNames.isEmpty() && it.returnTypeName == "java.lang.String"
    } ?: error("Viewer extension name getter shape not found")

    val gate = seed.methods.firstOrNull {
        it.returnTypeName == "boolean" &&
            it.paramTypeNames.size == 3 &&
            it.paramTypeNames[0] == "com.facebook.auth.usersession.FbUserSession"
    } ?: error("Viewer extension gate shape not found")

    findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add { name = nameGetter.name; paramCount = 0; returnType = "java.lang.String" }
                add { name = gate.name; paramCount = 3; returnType = "boolean" }
            }
        }
    }.flatMap { cls ->
        runCatching {
            cls.findMethod {
                matcher {
                    name = gate.name
                    returnType = "boolean"
                    paramTypes("com.facebook.auth.usersession.FbUserSession", null, null)
                }
            }.toList()
        }.getOrDefault(emptyList())
    }.filter { it.isHookableMethod() }.distinctBy { it.descriptor }
}

val adBreakFetchKickoffMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(
        listOf(
            "Kicking off video ad fetch",
            "Kicking off banner ads fetch",
            "Kicking off extended breaks fetch",
        )
    ).filter { it.returnTypeName == "void" && it.isHookableMethod() }
        .distinctBy { it.descriptor }
}

val wasLiveAdBreakControlRenderMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(listOf("NonLiveWasLiveAdBreakControlComponent"))
        .filter {
            it.paramTypeNames.size == 1 &&
                it.returnTypeName !in NON_RENDER_RETURN_TYPES &&
                it.isHookableMethod()
        }
        .distinctBy { it.descriptor }
}


private const val FETCH_FEED_PARAMS_CLASS = "com.facebook.api.feed.model.FetchFeedParams"
private const val READABLE_MAP_CLASS = "com.facebook.react.bridge.ReadableMap"

private const val ACC_BRIDGE = 0x0040
private const val ACC_SYNTHETIC = 0x1000

private val PRIMITIVE_TYPE_NAMES = setOf("int", "long", "boolean", "float", "double", "short", "byte", "char", "void")

val adFreeSessionGettersFingerprint = findMethodListDirect {
    val cls = findClass { matcher { addUsingString("adprefs/", StringMatchType.Equals) } }.firstOrNull()
        ?: error("BasicAds opt-in class not found (anchor adprefs/)")
    cls.findMethod { matcher { returnType = "boolean"; paramCount = 0 } }
        .filter { it.isHookableMethod() && !Modifier.isStatic(it.modifiers) }
}

val adBreakStateMachineMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(listOf("AdBreakStateMachine")).filter { it.isHookableMethod() }
}

private val BANNER_ANCHORS = listOf(
    "banner_ad", "banner_ads", "banner_ads_overlay", "bannerAdsOverlay",
    "banner_ad_visible", "banner_ad_dismiss", "banner_ad_click",
    "banner_ads_impression", "affiliate_link_banner_impression",
    "try_it_surface_banner_impression",
    "click_on_instream_legacy_banner_ad",
    "click_on_instream_legacy_banner_ad_context_card",
    "click_on_reel_instream_unified_player_banner_ad",
    "click_on_reel_banner_call_ad",
    "reels_banner_click_iab_inline", "reels_banner_click_iab_chaining",
    "reels_banner_click_wnb_inline", "reels_banner_click_wnb_chaining",
    "banner_ad_above_metadata_transition_key", "bannerAdSurfaceDelayMs",
    "bannerAdBreak", "bannerPo",
    "Kicking off banner ads fetch",
    "error while trying to load banner ad",
    "Failed to fetch banner ad",
    "loadbanneradasync", "hidebanneradasync",
    "banner_ads_click", "banner_ads_report_ad", "banner_ads_hide_ad",
    "affiliate_link_banner_click", "affiliate_link_attachment_banner_click",
    "zero_banner_impression",
)

private const val MAX_BANNER_CLASSES = 100

val bannerBooleanMethodsFingerprint = findMethodListDirect {
    val byAnchor: Map<String, List<ClassData>> = runCatching {
        batchFindClassUsingStrings {
            groups(BANNER_ANCHORS.associateWith { listOf(it) }, StringMatchType.Equals)
        }.mapValues { it.value.toList() }
    }.getOrElse {
        BANNER_ANCHORS.associateWith { anchor ->
            runCatching {
                findClass { matcher { addUsingString(anchor, StringMatchType.Equals) } }.toList()
            }.getOrDefault(emptyList())
        }
    }
    val classes = LinkedHashMap<String, ClassData>()
    for (anchor in BANNER_ANCHORS) {
        byAnchor[anchor].orEmpty().forEach { cls -> if (!isLoaderInfra(cls.name)) classes.putIfAbsent(cls.name, cls) }
        if (classes.size >= MAX_BANNER_CLASSES) break
    }
    classes.values.flatMap { cls ->
        cls.methods.filter { m ->
            m.isHookableMethod() &&
                m.returnTypeName == "boolean" &&
                m.paramTypeNames.isNotEmpty() &&
                !(m.name == "equals" && m.paramTypeNames.size == 1) &&
                (m.modifiers and (ACC_SYNTHETIC or ACC_BRIDGE)) == 0 &&
                m.name != "loadLibrary" && m.name != "loadLibraryUnsafe"
        }
    }.distinctBy { it.descriptor }
}

private fun DexKitBridge.methodsForTags(filters: Map<String, (MethodData) -> Boolean>): List<MethodData> {
    val byTag = methodsByTag(filters.keys.toList())
    return filters.flatMap { (tag, shapeOk) ->
        byTag[tag].orEmpty().filter { it.isHookableMethod() && shapeOk(it) }
    }.distinctBy { it.descriptor }
}

private fun ClassData.representativeMethod(): MethodData? =
    methods.firstOrNull { it.isMethod } ?: methods.firstOrNull()

val newsfeedProcessNewStoriesRunFingerprint = findMethodDirect {
    findClass { matcher { addUsingString("Added stories to FUC", StringMatchType.Equals) } }
        .firstNotNullOfOrNull { cls ->
            cls.findMethod { matcher { name = "run"; paramCount = 0; returnType = "void" } }.firstOrNull()
        } ?: error("processNewStories Runnable not found")
}

val feedUnitComponentClassesFingerprint = findMethodListDirect {
    findClass { matcher { usingStrings(listOf("NewsFeedFeedUnitComponent"), StringMatchType.Equals) } }
        .filterNot { it.usesOnlyFromStringPool("NewsFeedFeedUnitComponent") }
        .mapNotNull { it.representativeMethod() }
}

private fun ClassData.usesOnlyFromStringPool(anchor: String): Boolean {
    val users = methods.filter { anchor in it.usingStrings }
    return users.isNotEmpty() && users.all {
        Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.String" &&
            it.paramTypeNames == listOf("int")
    }
}

val feedWrapperComponentClassesFingerprint = findMethodListDirect {
    findClass { matcher { usingStrings(listOf("LoggingComponent"), StringMatchType.Equals) } }
        .mapNotNull { it.representativeMethod() }
}

val feedAdChannelBlockMethodsFingerprint = findMethodListDirect {
    methodsForTags(
        mapOf(
            "FeedNetworkController.doAdChannelNetworkRequest" to { _: MethodData -> true },
            "ADS_CHANNEL_BACKGROUND_PREFETCH" to { m: MethodData ->
                m.returnTypeName == "void" &&
                    FB_USER_SESSION_CLASS in m.paramTypeNames &&
                    FETCH_FEED_PARAMS_CLASS !in m.paramTypeNames
            },
            "Cannot add null or non-sponsored story" to { _: MethodData -> true },
            "not expected to be called in ad selection" to { _: MethodData -> true },
            "FBMultiAdsFeedUnitKComponent" to { m: MethodData -> m.paramTypeNames.size == 1 },
        )
    )
}

val feedSponsoredRenderMethodsFingerprint = findMethodListDirect {
    methodsForTags(
        mapOf(
            "NPE of attachment story" to { _: MethodData -> true },
            "Nothing Rendered." to { _: MethodData -> true },
        )
    )
}

val videoAdBlockMethodsFingerprint = findMethodListDirect {
    methodsForTags(
        mapOf(
            "Fetched and altered AdBreakStory when there's already an adbreak playing" to { m: MethodData -> m.returnTypeName == "void" },
            "fb_in_content_ads" to { _: MethodData -> true },
            "-WVCDF-NO-AD" to { _: MethodData -> true },
        )
    )
}

val tapToFullscreenAdFetchFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            name = "maybeFetchTapToFullscreenAd"
            declaredClass { usingStrings("Host story doesn't have a media attachment") }
        }
    }.filter { it.isHookableMethod() }
}

val reelsAdBlockMethodsFingerprint = findMethodListDirect {
    methodsForTags(
        mapOf(
            "FBFetchReelsVideoAdsQuery" to { m: MethodData -> m.returnTypeName == "void" },
            "REELS_BLOKS_BANNER_ADS_RENDER_COMPONENT_TEST_KEY" to { m: MethodData -> m.returnTypeName !in NON_RENDER_RETURN_TYPES },
            "ReelsIsEligibleForInContentAds" to { m: MethodData -> m.returnTypeName == "void" },
            "REPLACE_POSTLOOP_WITH_BANNER" to { _: MethodData -> true },
            "AdBucketParser.parse validation" to { m: MethodData -> m.returnTypeName != "java.lang.String" },
        )
    )
}

val shortsMidCardTypeNodeFingerprint = findMethodListDirect {
    findMethod { matcher { usingNumbers(3386882, 1742214758) } }.filter { it.isHookableMethod() }
}

private val MARKETPLACE_RENDER_ANCHORS = listOf(
    "MarketplaceVideoAdQuery",
    "MarketplaceVideoAdsComponent",
    "MarketplaceVideoAdsGrootLayoutSpec",
)

val marketplaceAdRenderMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(MARKETPLACE_RENDER_ANCHORS).flatMap { cls ->
        cls.methods.filter { m ->
            m.isHookableMethod() && !Modifier.isStatic(m.modifiers) && (m.modifiers and ACC_SYNTHETIC) == 0 && (
                m.name == "render" || (
                    m.paramTypeNames.size == 1 && m.paramTypeNames[0] !in PRIMITIVE_TYPE_NAMES &&
                        m.returnTypeName !in PRIMITIVE_TYPE_NAMES
                    )
                )
        }
    }.distinctBy { it.descriptor }
}

val marketplaceSendRequestFingerprint = findMethodListDirect {
    classesUsingAnyOf(listOf("FBNetworkingModule_React_Native")).flatMap { cls ->
        cls.methods.filter {
            it.name == "sendRequest" && it.isHookableMethod() &&
                it.paramTypeNames.any { type -> type == READABLE_MAP_CLASS }
        }
    }.distinctBy { it.descriptor }
}
