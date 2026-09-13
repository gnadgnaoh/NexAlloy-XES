package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.revanced.facebook.GRAPHQL_FEED_UNIT_EDGE_CLASS
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Mirrors upstream's post-resolution filter used in resolveFeedCsrFilterMethods,
 * resolveLateFeedListHooks and resolveStoryPoolAddMethods: excludes constructors and
 * any method that is abstract, or declared on an interface/abstract class, since
 * those can't be hooked directly — Xposed needs the concrete implementing method.
 * DexKit's MethodData/ClassData expose `modifiers` directly from the dex, so this
 * can run entirely at fingerprint-resolution time (no classLoader needed).
 */
private fun MethodData.isConcreteHookTarget(): Boolean {
    if (isConstructor || Modifier.isAbstract(modifiers)) return false
    val ownerModifiers = declaredClass?.modifiers ?: return true
    return !Modifier.isInterface(ownerModifiers) && !Modifier.isAbstract(ownerModifiers)
}

/**
 * A looser sibling of [isConcreteHookTarget], for targets whose *declaring* class may be
 * abstract while the method itself has a body.
 *
 * [isConcreteHookTarget] rejects those, and for the feed fingerprints it is right to:
 * there the abstract owner means the match is a declaration and the real work lives in a
 * subclass. The instream ad-break senders are the opposite case — the fetch is written
 * once on an abstract base and inherited by every concrete surface, so rejecting it by
 * owner would silently drop the banner/video fetch path (verified: it is declared on an
 * abstract class on the audited build). Hooking a non-abstract method on an abstract
 * class is valid: the body exists and runs for every instance of every subclass.
 */
private fun MethodData.isHookableMethod(): Boolean =
    !isConstructor && !Modifier.isAbstract(modifiers)

// ─── Ad-kind enum ─────────────────────────────────────────────────────────────

val adKindEnumFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("AD", "UGC", "PARADE", "MIDCARD") }
    }.first()
}

// ─── Reels list-builder ───────────────────────────────────────────────────────
// Primary: class that logs "Non ads story fall into ads rendering logic"
// Fallback: structural signature (static 6-param void + static 5-param ArrayList)

val listBuilderClassFingerprint = findClassDirect {
    // Primary: structural — the class must contain methods matching ALL of the shapes
    // below. Only trusted when it resolves to a SINGLE unambiguous class.
    //
    // VERIFIED against the shipped FB 575.0.0.45.73 dex (2026-08): three of the six
    // original shapes no longer matched anything. Facebook prepended an `FbUserSession`
    // first parameter to most methods on this class between FB573 and FB575, so every
    // pinned parameter shape shifted right by exactly one:
    //
    //   FB573 (was pinned)                       FB575 (X.564, observed)
    //   static ArrayList(?,?,?,?, boolean)   ->  static ArrayList(?,?,?,?,?, boolean)   A06
    //   ArrayList(?,?,?, Iterable)           ->  ArrayList(?,?,?,?, Iterable)           A0D
    //   List(?,?,?, boolean)                 ->  List(?,?,?,?, boolean)                 A0E/A0F
    //
    // The structural search therefore returned ZERO classes on FB575 and the entire Reels
    // list-builder layer was being carried by the string fallback alone — one log-message
    // rename away from silent death, with nothing to report the loss.
    //
    // Fixed by dropping the pinned parameter positions and keeping return type plus an
    // arity RANGE, which spans both generations. Checked against the shipped dex: this
    // set still resolves to exactly one class (X.564), so the `singleOrNull()` guard
    // below is not weakened in practice. If a future build makes it ambiguous the guard
    // sends resolution to the string fallback, exactly as before.
    val structural = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                // The builder append: void, six-ish parameters, one of them a List.
                add {
                    returnType = "void"
                    paramCount(5, 7)
                }
                // The list factory: static, returns ArrayList, trailing boolean flag.
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramCount(5, 7)
                }
                // The bulk copy: returns ArrayList, takes an Iterable of stories.
                add {
                    returnType = "java.util.ArrayList"
                    paramCount(4, 6)
                }
                // The public entry point: returns List, trailing boolean flag.
                add {
                    returnType = "java.util.List"
                    paramCount(4, 6)
                }
            }
        }
    }

    // Fallback: string-based — only consulted when the structural search above is
    // ambiguous (0 or 2+ matches), exactly mirroring upstream's
    // `structuralCandidates.singleOrNull() ?: batchCandidates.firstOrNull() ?: error(...)`.
    structural.singleOrNull()
        ?: findClass {
            matcher { usingStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
        }.firstOrNull()
        ?: error("Unable to resolve the upstream Facebook reels list-builder class")
}

// NOTE: listBuilderAppendFingerprint / listBuilderFactoryFingerprint were removed.
// Upstream now resolves these two methods via plain reflection + a scoring heuristic
// over every method on the already-resolved listBuilderClass (no rigid param-shape
// match), because Facebook occasionally ships variants with a different parameter
// count/order. That scoring logic needs a real java.lang.reflect.Method (List
// subtype checks via Class.isAssignableFrom), which only exists once classLoader is
// available — see resolveListBuilderAppendMethod / resolveListBuilderFactoryMethod
// in FacebookAdHelpers.kt, called from the patch body with
// ::listBuilderClassFingerprint.clazz (still DexKit-cached) as input.

// ─── Plugin packs ─────────────────────────────────────────────────────────────
// Upstream now blocks BOTH FbShortsViewerPluginPack AND MarketplaceAdsPluginPack.

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

// ─── Instream banner eligibility ─────────────────────────────────────────────
// Upstream resolves the CLASS first via a structural "0-arg String-returning method
// that uses this tag" shape (findClassesByZeroArgStringTags), then picks the actual
// boolean()/0-param eligibility method via plain reflection — preferring a non-static
// method declared on/inherited by that class, falling back to walking the superclass
// chain if none is found directly. That second part needs a real Class<*>
// (classLoader), so it lives in resolveInstreamBannerEligibilityMethod in
// FacebookAdHelpers.kt, called from the patch body with this class as input.

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

// ─── Indicator pill eligibility ──────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings (the render-path string and the
// fully-qualified plugin class name), then finds the static boolean(3-param) method
// inside that class — it doesn't require the method itself to reference either string.

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

// ─── Reels banner render methods ─────────────────────────────────────────────

val reelsBannerRenderMethodsFingerprint = findMethodListDirect {
    val bannerRenders = runCatching {
        methodsUsingAnyOf(listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent"))
            .filter { m -> m.paramTypeNames.size == 1 && !m.isConstructor }
    }.getOrDefault(emptyList())

    // These two used to be separate findMethod{}/findClass{} queries. Each one walks the whole
    // string index (~110ms on the measured dex) even when it matches nothing, and the slot-queue
    // branch does match nothing on the audited FB build (the class exists but has no one-arg void
    // method left). The batch helpers keep the same semantics without paying for a dead tag.
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

// ─── Profile Reels async ad query ─────────────────────────────────────────────

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

// ─── Feed CSR cache filter ────────────────────────────────────────────────────
// Upstream now also matches a newer 4-param variant — (FbUserSession, ?, ImmutableList, int) —
// in addition to the original 3-param (FbUserSession, ImmutableList, int) shape.
// We search both shapes per candidate class; HideFacebookAdsPatch derives the correct
// listArgIndex afterwards from each resolved Method's real parameter types.

/**
 * Cache-filter tags, one per feed surface.
 *
 * The dated `FeedCSRCacheFilter…` names are Facebook's own half-yearly renames and are
 * all kept, because only one of them exists on any given build and keeping the others
 * costs a search that finds nothing.
 *
 * `FriendlyFeedCacheFilter` and `FbShortsCSRCacheFilter` were added after checking which
 * cache-filter tags the app actually ships against which ones were being searched for:
 * the professional-mode profile feed and the Shorts feed each run their own filter, and
 * neither was reached by the news-feed tags, so sponsored items survived the filter stage
 * on both surfaces.
 */
private val FEED_CSR_FILTER_TAGS = listOf(
    // Present on FB 575.0.0.45.73 (verified against the shipped dex):
    //   FeedCSRCacheFilter2026H1  -> X.28W  (BI3 returns the tag, A00 is the filter)
    //   FbShortsCSRCacheFilter    -> X.57o
    // The bare "FeedCSRCacheFilter" entry still earns its place: DexKit matches strings
    // by containment, so it reaches whichever dated variant the build actually ships,
    // including one named after a half-year nobody has added to this list yet.
    "FeedCSRCacheFilter",
    "FeedCSRCacheFilter2025H1",
    "FeedCSRCacheFilter2026H1",
    "FeedCSRCacheFilter2026H2",
    "FeedCSRCacheFilter2027H1",
    "FeedCSRCacheFilter2027H2",
    // Not present on FB575 — kept because it costs a search that finds nothing, and the
    // professional-mode profile feed has carried its own filter on past builds.
    "FriendlyFeedCacheFilter",
    "FbShortsCSRCacheFilter",
)

val feedCsrFilterMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(FEED_CSR_FILTER_TAGS).flatMap { cls ->
        run {
            // NOTE: older builds returned the filtered ImmutableList directly. Current
            // builds return a result WRAPPER instead — e.g.
            //   AnH(FbUserSession, <ctx>, ImmutableList, int) -> LX/2iE
            // where the filtered list sits in a field of that wrapper. Pinning
            // returnType to ImmutableList therefore matched NOTHING and the whole feed
            // CSR filter hook silently never installed (runCatching swallowed it),
            // which is why sponsored items still reached the profile feed.
            // We no longer constrain the return type at all; the hook only needs the
            // ImmutableList PARAMETER, which it rewrites in beforeHookedMethod. The
            // param shape plus the class-level tag string is specific enough.
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

// ─── Late feed list sanitisers ────────────────────────────────────────────────

/**
 * Tags of every "late feed list" layer — sanitisers that run after the feed has been built.
 *
 * The first three tags used to live in two separate findClass{} queries, and one of them
 * required a class to use BOTH "handleStorageStories" and "Empty Storage List".
 *
 * A dex scan shows why that AND was a trap: on the audited Facebook build
 * "handleStorageStories" and "cancelVendingTimerAndAddToPool_" are gone from the code while
 * "Empty Storage List" is still there — and the class using it is exactly the class the
 * fingerprint wants, complete with the `void(?, ImmutableList, int)` method and
 * `getStorageController` / `getCsrStoryCollectionWorker` next to it. The branch was not dead,
 * it had lost half of its condition, and an AND that loses half loses everything. The hook had
 * silently not been installed since.
 *
 * Merging them into ONE batchFindClassUsingStrings pass with the lifecycle tags brings the lost
 * hook back, drops two separate queries (~110ms each on every cold scan), and a tag that
 * disappears now costs zero queries instead of breaking the whole branch.
 */
private val LATE_FEED_LIST_TAGS = listOf(
    // No longer used by any code on the audited build; kept because they cost nothing:
    "handleStorageStories",
    "cancelVendingTimerAndAddToPool_",
    // Still alive, and on its own identifies the storage-stories class:
    "Empty Storage List",
    // Four lifecycle classes, three shapes each:
    "CSRNoOpStorageLifecycleImpl",
    "FeedCSRStorageLifecycle",
    "FriendlyFeedCSRStorageLifecycle",
    "FbShortsCSRStorageLifecycle",
)

val lateFeedListMethodsFingerprint = findMethodListDirect {
    val fbUserSession = "com.facebook.auth.usersession.FbUserSession"
    val immutableList = "com.google.common.collect.ImmutableList"

    // Every shape ever listed is tried on every matching class instead of pinning which shape
    // belongs to which tag. The wider net is safe because the hook consuming this list checks
    // each item: it only drops stories that identify themselves as ads, so a method that never
    // sees an ad is never affected. HideFacebookAdsPatch derives listArgIndex from the method's
    // real ImmutableList parameter.
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

// ─── Story pool add ───────────────────────────────────────────────────────────

/**
 * Pools and queues that admit a story into an ad slot.
 *
 * Safe to widen freely: the hook that consumes this is item-aware — it inspects the story
 * being offered and only refuses one it can positively identify as sponsored. A tag that
 * turns out to hold organic stories therefore costs nothing, which is why the Shorts and
 * Friendly-feed sponsored pools are included even though their exact semantics were never
 * confirmed at runtime.
 */
val STORY_POOL_TAGS = listOf(
    "CSRStoryPoolCoordinator",
    "FeedStoryPoolCoordinator",
    "FbShortsSponsoredPool",
    "FBShortsSponsoredPool",
    "FbShortsIFUSponsoredPool",
    "FriendlyFeedSponsoredPool",
    "FbShortsCSRSponsoredSlotQueue",
    "FbShortsCSRCacheFilter",

    // ── Added after listing every pool, queue and coordinator tag in the app ──────
    //
    // These are the story pools that were shipping without a corresponding tag here.
    // Several of them ("Stories", "Hoist", "Offline", the friendly-feed pool) hold
    // organic stories most of the time, which is exactly why they are safe to list:
    // the hook is item-aware, so a pool that never offers an ad is never affected,
    // while the one occasion it does is now covered.
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

// ─── Sponsored pool ───────────────────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings, then verifies the
// boolean(GraphQLFeedUnitEdge) method shape exists somewhere in that class.

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

// ─── Sponsored story manager ──────────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings, then verifies the
// GraphQLFeedUnitEdge()/0-param method shape exists somewhere in that class.

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

// ─── Story ads in-disc source ─────────────────────────────────────────────────
// Upstream changed search string to "ads_deletion" (from commit fixing profile timeline ads)

val storyAdsInDiscClassFingerprint = findClassDirect {
    findMethod {
        matcher { usingStrings("ads_deletion") }
    }.first { md ->
        val cls = md.declaredClass ?: return@first false
        cls.findMethod {
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && cls.findMethod {
            matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList") }
        }.isNotEmpty()
    }.declaredClass!!
}

/**
 * The specific 0-param void method inside storyAdsInDiscClass that triggers ad insertion.
 * Upstream finds this via usingStrings("ads_insertion") — we replicate that here.
 */
val storyAdsInsertionTriggerMethodFingerprint = findMethodDirect {
    storyAdsInDiscClassFingerprint().findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("ads_insertion")
        }
    }.firstOrNull()
        ?: storyAdsInDiscClassFingerprint().findMethod {
            // Fallback: first 0-param void method if string not found (obfuscated builds)
            matcher { returnType = "void"; paramCount = 0 }
        }.first()
}

// ─── Game ad request methods ──────────────────────────────────────────────────

/**
 * The Instant Games JavaScript ad bridge — the methods a game calls to ask for an ad.
 *
 * **Audit 2026-08 — the previous note here was WRONG and has been replaced.** It said all five
 * anchors were absent on FB575 and that this fingerprint matching nothing was "expected".
 * Checking against the running dex shows the opposite: FOUR of the five anchors are alive, all
 * on the same bridge class (60 methods, 42 of them `void(JSONObject)`, plus the
 * `postMessage(String, String)` that section 9 of [HideFacebookAds] needs). Sections 9 and 10
 * DO run on this build.
 *
 * The only anchor really gone is `onGetRewardedInterstitialAsync` — the dex has no
 * `getrewardedinterstitialasync` string left either, so the entry of that name in
 * [GAME_AD_UNAVAILABLE_MESSAGE_TYPES] is harmless dead code. The other four anchors are kept
 * as is; the lost one stays because it costs no extra query (all five share one batch pass)
 * and still catches devices with an old cached module.
 *
 * The defence one layer lower — [quicksilverAdsVoltronGateFingerprint] and
 * [quicksilverBannerAdLoaderMethodsFingerprint] — is confirmed present as well, so the two
 * complement rather than replace each other: the gate stops the ads module from loading, the
 * bridge answers games that loaded it earlier.
 *
 * Lesson for the next audit: an empty `dexMethodList` is NEVER proof that a target is gone —
 * it is only an empty list swallowed by `runCatching`. Scan the dex to know.
 */
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

// ─── Feed collection edge filter ──────────────────────────────────────────────
// Replaces FB571_FEED_COLLECTION_TARGETS (was pinned to X.1vr). "addNewEdgeToCollection"
// is one of the very few feed methods that survives ProGuard with its real name, so it
// can be matched by name + shape on any build. Verified on FB 573:
//   X.1vy.addNewEdgeToCollection(ImmutableList$Builder, GraphQLFeedUnitEdge, X.1cS): boolean
val feedCollectionAddEdgeMethodFingerprint = findMethodDirect {
    val byShape = findMethod {
        matcher {
            name = "addNewEdgeToCollection"
            returnType = "boolean"
            paramTypes(null, "com.facebook.graphql.model.GraphQLFeedUnitEdge", null)
        }
    }.filter { it.isConcreteHookTarget() }

    byShape.firstOrNull()
        // Looser fallback: any concrete addNewEdgeToCollection that takes an edge
        // somewhere in its parameter list (param count/order occasionally shifts).
        ?: findMethod {
            matcher { name = "addNewEdgeToCollection"; returnType = "boolean" }
        }.first {
            it.isConcreteHookTarget() &&
                it.paramTypeNames.any { p -> p == "com.facebook.graphql.model.GraphQLFeedUnitEdge" }
        }
}

// ─── Story ad source providers (all of them) ──────────────────────────────────
// Upstream pinned SIX provider classes by name (FB571_STORY_AD_SOURCE_CLASSES) because
// the single-class DexKit lookup missed the split pipelines. This returns EVERY class
// that both logs "ads_deletion" and carries the provider shape, so no name is needed.
// Verified on FB 573: three classes log "ads_deletion", exactly one carries the shape.
val storyAdsInDiscMethodsFingerprint = findMethodListDirect {
    findMethod {
        matcher { usingStrings("ads_deletion") }
    }.filter { md ->
        val cls = md.declaredClass ?: return@filter false
        cls.findMethod {
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && cls.findMethod {
            matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList") }
        }.isNotEmpty()
    }.distinctBy { it.declaredClass?.name }
}

// ─── Video plugin system: packs, descriptors, static builders ─────────────────
//
// Everything below targets the layer that serves ads INSIDE a video, as opposed to ads
// that arrive as their own feed story. A runtime trace established that this layer, and
// not the ad-break subsystem, is what delivers the sponsored clip that replaces a
// creator's video and the sponsored card that sits under it: with 21 ad-break resolver
// accessors and 50 ad-break state machine methods hooked, not one of them was ever called
// while those ads were on screen.
//
// None of these fingerprints pin a pack or descriptor name. They resolve the SHAPE of the
// plugin API and let the hooks decide per instance, because some ad packs assemble their
// name at runtime and can never be matched by a literal.

/**
 * Every plugin-list getter in the video plugin system.
 *
 * Resolved by shape from a known pack rather than by method name: the 0-argument List
 * getter that plugin packs expose. Includes getters inherited from a shared base, which
 * ad packs and organic packs use in common — hence the per-instance filtering in
 * [hookPluginPackList].
 */
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

/**
 * The eligibility gate shared by every video plugin descriptor — the boolean the player
 * calls to ask a descriptor whether it applies to the current video.
 *
 * Shape is learnt from a descriptor known to be ads-only, so the obfuscated method name is
 * never pinned. There are many implementations (165 on the build this was written
 * against), which is exactly why [hookPluginDescriptorGate] filters per instance instead
 * of trying to fingerprint the ad ones.
 */
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

/**
 * Direct-monetization ad plugins — the in-video ads a creator monetises with.
 *
 * These come from a plain static builder rather than from a pack object, so neither a
 * pack-level nor a descriptor-level hook reaches them; the builder is matched by the one
 * literal it carries.
 */
val directMonetizationAdsPluginListFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            usingStrings("REELS_DIRECT_MONETIZATION_ADS")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}


// ─── Litho render resolution ──────────────────────────────────────────────────
//
// Helpers for locating a Litho render method by string, used by the per-story hooks below.
//
// NOTE: the blanket "hide ad-only components" layer that used to live here — the ~220-tag
// component/section lists, `adRenderMethodsFor`, `rejectSharedFeedComponents` and the
// Stories-ad component fingerprints — was removed along with the `HideFacebookAdComponents`
// patch. Tag-matched component suppression produced too many false positives: a wrongly
// matched class does not crash, the surface simply stops drawing, which is silent and hard
// to trace. Only structural, per-story resolution remains.

/**
 * Return types that prove a 1-argument method is NOT a render.
 *
 * Facebook ships generated string-table classes with signatures like `A00(int): String`
 * that mention nearly every tag in the app. Without this filter, a string-anchored lookup
 * would hook those and corrupt unrelated text. A render always returns a Component or a
 * Section.
 */
private val NON_RENDER_RETURN_TYPES = setOf(
    "java.lang.String", "void", "boolean", "int", "long", "float", "double", "char", "byte", "short"
)

private fun MethodData.isRenderShaped(): Boolean =
    !isConstructor && returnTypeName !in NON_RENDER_RETURN_TYPES

/**
 * Every class that uses at least one of [tags], found in a SINGLE native pass.
 *
 * The obvious way to write this is a loop of `findClass { usingStrings(tag) }`, one query
 * per tag, and that is how it used to be written. Measured against the shipped FB575 dex
 * (16 files, 130 MB), a DexKit class query costs a flat ~110 ms floor whatever it matches,
 * because each one walks the whole string index. At 220 component tags that is 24 seconds
 * of the module's ~36-second cold scan — two thirds of the total, spent re-walking the
 * same index 220 times.
 *
 * `batchFindClassUsingStrings` takes every tag as its own named group and answers them all
 * in one walk. Same inputs, same per-tag grouping, one pass. Measured on the same dex:
 * 24 s -> under 1 s.
 *
 * The group name is the tag itself; results are flattened because callers only ever want
 * the union. Falls back to the per-tag loop if the batch call fails, so a DexKit version
 * without the batch API degrades to the old behaviour rather than to nothing.
 */
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

/**
 * Every method that uses at least one of [tags], found in a SINGLE native pass.
 *
 * The method-level twin of [classesUsingAnyOf], and it exists for the same measured
 * reason: `findMethod { usingStrings(tag) }` costs the same flat ~110 ms index walk per
 * call, so a fingerprint anchored on four logging tags spends four walks to answer one
 * question. `batchFindMethodUsingStrings` answers all of them in one.
 *
 * The batch API filters on strings only, so the shape constraint that used to live in the
 * matcher — a return type, a parameter list — is applied by the caller afterwards against
 * `returnTypeName` / `paramTypeNames`. That is the same test the matcher performed, just
 * evaluated in Kotlin over a much smaller set: the handful of methods that carry the tag,
 * rather than every method in the app.
 *
 * Falls back to the per-tag loop when the batch call fails, so behaviour degrades to the
 * old path rather than to an empty result.
 */
private fun DexKitBridge.methodsUsingAnyOf(tags: List<String>): List<MethodData> {
    if (tags.isEmpty()) return emptyList()
    runCatching {
        batchFindMethodUsingStrings { groups(tags.associateWith { listOf(it) }) }
    }.getOrNull()?.let { batched ->
        return batched.values.flatten().distinctBy { it.descriptor }
    }
    return tags.flatMap { tag ->
        runCatching { findMethod { matcher { usingStrings(tag) } }.toList() }.getOrDefault(emptyList())
    }.distinctBy { it.descriptor }
}

/**
 * The obfuscated Litho render return type for this build, derived rather than pinned: it
 * is simply the return type of a render method already located by string. Both the
 * Component and the Section flavour are resolved this way.
 */
private fun DexKitBridge.renderReturnTypeFrom(seedTags: List<String>): String? =
    seedTags.firstNotNullOfOrNull { tag ->
        runCatching {
            findClass { matcher { usingStrings(tag) } }
                .flatMap { cls -> cls.methods.filter { it.paramTypeNames.size == 1 } }
                .firstOrNull { it.isRenderShaped() }
                ?.returnTypeName
        }.getOrNull()
    }

/**
 * The profile timeline story component's render.
 *
 * Suppressing this component wholesale blanks the entire "all posts" section — it draws
 * organic posts, customised stories and featured highlights as well as ads — so the hook
 * that uses this decides per story rather than per component.
 */
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

// NOTE: fingerprints for the news feed's Reels row (the in-feed-unit pools and the tray
// component that holds the tiles) were removed from this build. They only ever located
// the tile list; nothing consumed them, and a fingerprint that no patch calls resolves
// nothing and blocks nothing. Restoring them is only useful together with a patch that
// drops ad entries FROM that list — suppressing a tile's render leaves it in the tray as
// an empty, unlabelled box.


// ─── Sponsored-story vendor and Instant Games ads ─────────────────────────────

/**
 * The sponsored-story vendor: the two methods the feed calls to pick which advertisement
 * to drop into the next slot.
 *
 * Both return a `GraphQLFeedUnitEdge`, and both already have a no-ad path — the vendor
 * logs `empty_pool` when the pool has nothing eligible and the feed simply continues with
 * organic stories. Returning null puts them permanently on that path, which is a cleaner
 * outcome than filtering the ad out further downstream: no slot is allocated, so nothing
 * has to be collapsed afterwards.
 *
 * Constrained to the `GraphQLFeedUnitEdge` return type rather than matched on the tag
 * alone, so a tag appearing in a logging helper cannot be hooked by mistake.
 */
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

/**
 * The Instant Games ad module's load gate and its banner-ad loader — the two halves of
 * that surface that are obfuscated, so they cannot be reached by name like
 * [QUICKSILVER_ADS_LOADER_CLASS] can.
 *
 *  - `quicksilverAdsVoltronGateFingerprint` is the waiter that blocks until the ads
 *    module finishes loading and reports whether it did. A 0-argument boolean, so
 *    answering "it did not" is a state the caller already handles — it is the same
 *    answer an interrupted load produces, which the method itself logs.
 *  - `quicksilverBannerAdLoaderMethodsFingerprint` is the runnable that builds the
 *    banner ad view over a running game. Whole-class ad code: the only other strings it
 *    carries are its own error message and the placement. `void run()`, so it can simply
 *    not run, and the game keeps its full viewport.
 */
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

// ─── Search results (SERP) ads ────────────────────────────────────────────────
//
// The search results page is its own surface: it runs its own GraphQL query, builds its
// own item list and renders it with its own components. None of the news-feed machinery
// above ever sees it, which is why sponsored results survived every feed-side filter.
//
// Facebook labels these units itself. The search-result unit carries a type enum whose
// constants are plain, unobfuscated names, and the advertisement ones say so outright:
// SEARCH_ADS, TOP_POSITION_SEARCH_ADS, MARKETPLACE_SEARCH_ADS and so on, alongside ~570
// organic kinds. That is a positive identification of exactly the kind this module
// prefers: a unit whose type is in the ad set IS an advertisement, and a unit whose type
// is absent from it is left alone, so the worst outcome is a missed ad rather than an
// emptied results page.

private const val IMMUTABLE_LIST_CLASS = "com.google.common.collect.ImmutableList"
private const val FB_USER_SESSION_CLASS = "com.facebook.auth.usersession.FbUserSession"
private const val SEARCH_RESULTS_CONTEXT_CLASS =
    "com.facebook.search.results.model.SearchResultsMutableContext"

/**
 * Four constants that only the search-result unit type enum carries.
 *
 * Matched as EXACT strings, not by containment: the enum is the single class in the app
 * whose constant pool holds all four, so this resolves to one class with no shape test
 * needed. Four rather than one because a single generic name like "SEARCH_ADS" also
 * appears in logging tables; the combination does not.
 */
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

/**
 * The search-result unit list builder: `static ImmutableList(ImmutableList)`.
 *
 * Every result the page displays passes through here exactly once — the method walks the
 * GraphQL edges of the results connection and wraps each one in a unit object — so it is
 * the single place where the whole page can be filtered, including the blended
 * top-position ad. The declaring class is the unit itself, which is how it is found: the
 * only class in the app that both declares a field of the type enum AND carries a static
 * ImmutableList-to-ImmutableList method.
 *
 * Neither half is pinned to an obfuscated name, and the pair is unique on the shipped
 * dex (verified against FB 575: exactly one class matches).
 */
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

/**
 * The controller for the separate top-position ads query.
 *
 * Identified by its constructor's parameter shape, which needs no obfuscated name at all:
 * `(FbUserSession, SearchResultsMutableContext, int)`. `SearchResultsMutableContext` ships
 * under its real package name, and that constructor shape is unique across the whole app.
 */
private fun DexKitBridge.searchAdsControllerClass(): ClassData =
    findMethod {
        matcher {
            name = "<init>"
            paramTypes(FB_USER_SESSION_CLASS, SEARCH_RESULTS_CONTEXT_CLASS, "int")
        }
    }.firstNotNullOfOrNull { it.declaredClass } ?: error("Search ads controller not found")

/**
 * The "ads have arrived" state of that controller: `(ImmutableList, boolean)`.
 *
 * The controller holds its progress in one field whose type is a sealed base with four
 * states — initial, in flight, failed, and this one, which is the only state that carries
 * a payload. Emptying that payload is what this fingerprint exists for, and it is the
 * gentlest possible intervention: an ads query that returns nothing is an outcome the
 * page already handles on its own every time the server has no ad to sell, so the search
 * results render exactly as they do on a quiet request.
 *
 * The base class is derived, not pinned: it is the controller's only field whose type is
 * neither a platform type nor a Facebook-packaged one, i.e. the only obfuscated one.
 */
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

/**
 * Litho components that exist only to draw a search advertisement.
 *
 * Unlike the profile timeline's component — which draws organic posts too, and so has to
 * decide per story — every class reached by these tags is dedicated: the tags are the
 * components' own registered names, and each declaring class carries a handful of methods
 * and no organic responsibilities. Suppressing the render outright is therefore safe, and
 * it is the backstop for the two ad surfaces that carry no name string of their own (the
 * top-position module and its grid), which the state hook above covers instead.
 *
 * Constrained to the Component render return type, which is derived from a known ad
 * component rather than pinned. That constraint is load-bearing: without it the tag search
 * also reaches a Section-flavoured render on one of Facebook's large shared classes, and
 * blanking that would take far more than an advertisement with it.
 */
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


// ─── Video viewer extensions ──────────────────────────────────────────────────
//
// The video permalink surface — the page a "Đã phát trực tiếp" / was-live replay opens
// into, with the player on top and the comment list below — does NOT assemble its
// overlays from the video plugin system that [allPluginPackListMethodsFingerprint] and
// [pluginDescriptorGateMethodsFingerprint] cover. It uses a second, parallel mechanism:
// viewer *extensions*, an abstract base with
//
//     <name>()   : String                                        - the extension's name
//     <gate>(FbUserSession, Object, Object)          : boolean    - "does this apply?"
//     <render>(FbUserSession, ComponentContext, ..., Object, Object) : Component
//
// and a base wrapper that calls <gate> first and only then <render>. Eighteen of them
// ship on the audited build; exactly two are advertising:
//
//     InstreamAdsViewerCoordinatorExtension - the mid-roll takeover ("your video will
//                                            resume in N seconds")
//     InstreamAdsFooterExtension            - the dockable sponsored card that sits
//                                            between the player and the comments
//
// Those two are why ads leaked on live replays while ordinary videos stayed clean: a
// replay opens the permalink surface, and nothing in NexAlloy reached the extension
// layer. The gate is hooked on EVERY extension and filtered per instance by the
// extension's own name, exactly as [hookPluginPackList] does for packs — an ad
// extension's name is built from a static string, but pinning the obfuscated class
// would not survive the next build.

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

    // Requiring BOTH shapes on the same class is what keeps this off unrelated methods
    // that happen to share the obfuscated gate name: an extension is the only thing in
    // the app that carries a 0-arg name getter and a session-scoped boolean gate.
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

// ─── Instream ad break fetch ──────────────────────────────────────────────────
//
// One layer below the UI: the methods that send the GraphQL query for the advert that
// fills a commercial break. Three of them on the audited build — the video ad fetch, the
// banner ad fetch and the extended-breaks fetch — and all three are `void` and announce
// themselves in their own log line, which is what anchors them here.
//
// Skipping the call is a state the app produces on its own every session: each of these
// methods already has early returns (an in-flight fetch, a blocked query reason) that
// leave the state machine with nothing to play, and the player then simply carries on
// with the host video. That is why the fetch is the chosen choke point rather than the
// response — no half-built ad break is ever created to be torn down.
//
// Deliberately NOT anchored on "commercial_break_query_send": that string is also
// carried by the analytics logger, which must keep running.

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

// ─── Was-live ad break control render ─────────────────────────────────────────
//
// The replay-specific Litho control that inserts the ad break coordinator into a video
// that WAS live but is no longer. Named NonLiveWasLiveAdBreakControlComponent by
// Facebook, exists for nothing else, and returning nothing from a Litho render is a
// state Litho handles natively. A backstop for the gate above, not a replacement.

val wasLiveAdBreakControlRenderMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(listOf("NonLiveWasLiveAdBreakControlComponent"))
        .filter {
            it.paramTypeNames.size == 1 &&
                it.returnTypeName !in NON_RENDER_RETURN_TYPES &&
                it.isHookableMethod()
        }
        .distinctBy { it.descriptor }
}
