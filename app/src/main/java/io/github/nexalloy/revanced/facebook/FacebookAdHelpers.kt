package io.github.nexalloy.revanced.facebook

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.hookMethod
import io.github.nexalloy.isStatic
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─── Constants ───────────────────────────────────────────────────────────────

const val FB_TAG = "NexAlloy/Facebook"
private const val BEFORE_SIZE_EXTRA = "nexalloy_fb_ads_before_size"
private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "nexalloy_fb_noop_ad"
private const val GAME_AD_RECENT_WINDOW_MS    = 30_000L
private const val GAME_AD_PROMISE_WINDOW_MS   = 10 * 60_000L
private const val AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS = 35_000L

const val GRAPHQL_FEED_UNIT_EDGE_CLASS       = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS  = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"

// ── Additional ad-carrying feed units present on the current build ────────────
//
// Found by listing every com.facebook.graphql.model.* type whose CamelCase tokens
// include Ad/Ads/Sponsored/Promotion, then subtracting the two already handled above.
// Three of the remainder are advertisements and are added here. The rest are not, and
// are deliberately left out:
//
//   GraphQLGreetingCardPromotionFeedUnit, GraphQLThrowbackPromotionFeedUnit
//       the user's own memories and birthday cards — organic content that merely
//       carries "Promotion" in its name.
//   GraphQLAdCreative, GraphQLAdImage, GraphQLAdsReportingInformation
//       fragments of an ad rather than a feed unit; they never appear as the
//       inflated unit on an edge, so testing for them here would never fire.

/** The non-templated Quick Promotion unit. Only the *NativeTemplate* flavour was
 *  handled before, so a promo delivered through the plain server-rendered unit was
 *  never positively identified as sponsored. */
const val GRAPHQL_QUICK_PROMOTION_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionFeedUnit"

/** The holdout arm of an ad experiment. It occupies a feed slot exactly as a normal ad
 *  does — the difference is in what the server measures, not in what the feed shows —
 *  so it is refused on the same terms. */
const val GRAPHQL_HOLDOUT_AD_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLHoldoutAdFeedUnit"

/** Banner ads delivered as a Bloks payload rather than as a native feed story. */
const val GRAPHQL_BLOKS_BANNER_ADS_CLASS =
    "com.facebook.graphql.model.GraphQLXFBBloksBannerAds"

/**
 * Every inflated feed-unit type that is, on its own, proof of an advertisement.
 *
 * Membership is a positive identification and never a heuristic: an item whose feed
 * unit is one of these is refused without consulting its category or its token scan.
 */
val GRAPHQL_AD_FEED_UNIT_CLASSES = setOf(
    GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS,
    GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS,
    GRAPHQL_QUICK_PROMOTION_FEED_UNIT_CLASS,
    GRAPHQL_HOLDOUT_AD_FEED_UNIT_CLASS,
    GRAPHQL_BLOKS_BANNER_ADS_CLASS,
)

/**
 * The same identification, done by GraphQL *type name* instead of by Java class name.
 *
 * [GRAPHQL_AD_FEED_UNIT_CLASSES] only fires for units that ship as a real
 * `com.facebook.graphql.model.*` class. Auditing every `*FeedUnit` / `*AdUnit` type name
 * in the shipped dex showed that two ad units no longer do: `RediscoveryAdsFeedUnit` and
 * `LeadGenQualityAdUnit` are both carried by obfuscated tree models (`X.41K`, `X.azs`),
 * so the class-name test could never see them. Their `getTypeName()` still returns the
 * real name, and the inspector already reads it, so testing it here costs nothing.
 *
 * The token scan does not cover them either: [FEED_AD_SIGNAL_TOKENS] has no bare "ads"
 * entry — deliberately, since "ads" hides inside Threads and Heads — and neither name
 * contains "sponsored", "promotion" or "multiads". So both were passing every test.
 *
 * This is a positive identification, exactly like the class set: membership proves an
 * advertisement, absence proves nothing. `GreetingCardPromotionFeedUnit` and
 * `ThrowbackPromotionFeedUnit` are the user's own memories and birthday cards and stay
 * out, for the same reason they are excluded from the class set.
 */
val GRAPHQL_AD_FEED_UNIT_TYPE_NAMES = setOf(
    "FBMultiAdsFeedUnit",
    "HoldoutAdFeedUnit",
    "QuickPromotionFeedUnit",
    "QuickPromotionNativeTemplateFeedUnit",
    "VibesRifuQuickPromotionFeedUnit",
    "XFBBloksBannerAds",
    // No GraphQL model class on this build — the whole reason this set exists.
    "RediscoveryAdsFeedUnit",
    "LeadGenQualityAdUnit",
)

const val AUDIENCE_NETWORK_ACTIVITY_CLASS        = "com.facebook.ads.AudienceNetworkActivity"
const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
const val NEKO_PLAYABLE_ACTIVITY_CLASS           = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

/**
 * The loader for Instant Games advertising, which ships as its own dynamic (Voltron)
 * module rather than as part of the app.
 *
 * Unobfuscated, like the Audience Network and Neko classes above, so it is looked up by
 * name rather than fingerprinted.
 *
 * **Expect this lookup to miss on FB575 and later.** On FB574 the class was defined in
 * the base dex. On FB575 only a reference to it survives there — the definition ships
 * inside the `instantgamesads` Voltron module itself, which is downloaded when a game
 * first asks for an ad, long after patch time. So [hookInstantGamesAdsLoader] returning
 * without hooking anything is the normal outcome on current builds, not a failure.
 *
 * The defence on those builds is carried by [quicksilverAdsVoltronGateFingerprint] and
 * [quicksilverBannerAdLoaderMethodsFingerprint] instead, both of which resolve against
 * the base dex and were verified present on FB575. The gate is the better hook of the
 * two anyway: it is what reports whether the ads module finished loading, so answering
 * "it did not" covers the module however it was fetched.
 *
 * The name lookup is kept because it costs one failed `loadClass` and still lands on any
 * build — or any device with a stale install — where the class is in the base dex.
 *
 * This is a layer below every game-ad hook the module had before. Those all sit on the
 * JavaScript bridge: the game asks for an ad, and the bridge answers that none is
 * available. That works, but the ad code has already been downloaded and loaded into the
 * process by then. Refusing the module load instead means there is nothing there to ask.
 *
 * The app handles this state on its own — it logs an IOException and carries on when the
 * Voltron package cannot be fetched, which is what happens to anyone whose download fails.
 */
const val QUICKSILVER_ADS_LOADER_CLASS =
    "com.facebook.quicksilver.webviewprocess.QuicksilverSeparateProcessAdsLoader"

/** Gate answering "is the ads module loaded", and the module-load waiter behind it. */
const val QUICKSILVER_ADS_LOADED_METHOD = "isInstantGamesAdsLoaded"
const val QUICKSILVER_ADS_LOAD_METHOD   = "loadInstantGamesAdsVoltronModule"
const val QUICKSILVER_ADS_RESET_METHOD  = "resetInstantGamesAdsModuleLoadState"

const val GAME_AD_REJECTION_MESSAGE   = "Game ad request blocked"
const val GAME_AD_REJECTION_CODE      = "CLIENT_UNSUPPORTED_OPERATION"
const val GAME_AD_UNAVAILABLE_MESSAGE = "Rewarded ad unavailable"
const val GAME_AD_UNAVAILABLE_CODE    = "ADS_UNAVAILABLE"

val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync", "getrewardedvideoasync", "getrewardedinterstitialasync",
    "loadadasync", "showadasync", "loadbanneradasync", "hidebanneradasync"
)

/** Only these types are auto-fixed (banner/hide); rewarded/interstitial get ADS_UNAVAILABLE. */
val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf("loadbanneradasync", "hidebanneradasync")

val GAME_AD_UNAVAILABLE_MESSAGE_TYPES = setOf("getrewardedvideoasync", "getrewardedinterstitialasync")

val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(NEKO_PLAYABLE_ACTIVITY_CLASS)

/**
 * Optional, user-supplied class-name overrides. Nothing is pinned in source: this reads
 * an optional comma-separated list from the module's shared prefs so a future Facebook
 * build can be patched without recompiling. Returns an empty set when unset.
 */
fun facebookClassNameOverrides(key: String): Set<String> = runCatching {
    XSharedPreferences(
        BuildConfig.APPLICATION_ID, "facebook_overrides"
    ).takeIf { it.file.canRead() }
        ?.getString(key, null)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
}.getOrNull().orEmpty()

val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardedVideoCompleted", "onRewardedAdCompleted", "onRewardedInterstitialCompleted",
    "onAdComplete", "onAdCompleted"
)

/** Optional runtime override for Audience Network close-listener classes.
 *  Empty by default: no obfuscated class name is pinned in source any more.
 *  Populate via the module override list if a future build needs it. */
val AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES: Set<String> = facebookClassNameOverrides("audience_network_close_listener")

val FEED_AD_CATEGORY_VALUES          = setOf("SPONSORED", "PROMOTION", "AD", "ADVERTISEMENT", "BANNER", "ENGAGEMENT_QP")
val FEED_COLLECTION_AD_CATEGORY_VALUES = setOf("SPONSORED", "PROMOTION", "AD", "ADVERTISEMENT", "BANNER")
val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf("FB_SHORTS", "MULTI_FB_STORIES_TRAY")


// Upstream gate: the BROAD feed/reel-CTA text-marker fallbacks below are disabled by
// default in FacebookAppAdsRemover (higher false-positive risk than the explicit-card
// detector). The explicit "Hide ad" + AdChoices card detector always stays on.
const val ENABLE_FEED_UI_MARKER_FALLBACKS = false

val FEED_SURFACE_AD_MARKER_TOKENS = listOf(
    "hide ad", "ad\u2022", "sponsored", "promoted", "ad choices", "adchoices"
)

val EXPLICIT_FEED_CARD_AD_MARKER_TOKENS = listOf(
    "hide ad", "ad\u2022", "ad choices", "adchoices"
)

val EXPLICIT_FEED_AD_CTA_TOKENS = listOf(
    "apply now", "send message", "learn more", "shop now", "contact us",
    "get quote", "book now", "call now", "sign up", "download"
)

val FEED_REEL_CTA_AD_MARKER_TOKENS = listOf(
    "shared link:", "send message", "your business", "your ad"
)

val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored", "promotion", "multiads", "quickpromotion",
    "reels_banner_ad", "reelsbannerads", "reels_post_loop_deferred_card", "deferred_card",
    "adbreakdeferredcta", "instreamadidlewithbannerstate", "instream_legacy_banner_ad",
    "unified_player_banner_ad", "banner_ad_", "floatingcta"
)

val REELS_AD_SIGNAL_TOKENS = listOf(
    "sponsored", "promotion", "multiads", "quickpromotion",
    "reels_banner_ad", "reelsbannerads", "adbreakdeferredcta",
    "instreamadidlewithbannerstate", "instream_legacy_banner_ad",
    "unified_player_banner_ad", "banner_ad_"
)

val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

// ─── Shared state ─────────────────────────────────────────────────────────────

val gameAdInstanceIds    = ConcurrentHashMap<String, String>()
val gameAdInstanceTypes  = ConcurrentHashMap<String, String>()
val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
val recentGameAdTargets  = Collections.synchronizedMap(WeakHashMap<Any, Long>())
val recentGameAdPayloads = Collections.synchronizedList(ArrayList<GameAdPayloadSnapshot>())
private val gameAdResultHooksInstalled         = AtomicInteger(0)
private val gameAdServiceDispatchHooksInstalled = AtomicInteger(0)
private val gameAdSurfaceHooksInstalled        = AtomicInteger(0)
private val audienceNetworkRewardHooksInstalled = AtomicInteger(0)
private val audienceNetworkRewardClassesHooked  = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val storyAdProviderClassesHooked        = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val feedCsrMethodsHooked                 = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val lateFeedMethodsHooked                = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val sponsoredPoolMethodsHooked           = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val feedComponentMethodsHooked           = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val feedSectionMethodsHooked             = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val feedCollectionMethodsHooked          = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val audienceNetworkRewardAdListeners    = Collections.synchronizedMap(WeakHashMap<Any, Any>())
private val scheduledGameAdActivityCloses       = Collections.synchronizedMap(WeakHashMap<Activity, Long>())
private val scheduledAudienceNetworkExitViews   = Collections.synchronizedMap(WeakHashMap<View, Long>())
private val lastGameAdActivityCloseMs    = AtomicLong(0L)
private val lastUnavailableGameAdMs      = AtomicLong(0L)
private val marketplaceAdsPackCache      = ConcurrentHashMap<String, Boolean>()

// ─── Data classes ─────────────────────────────────────────────────────────────

data class FeedListSanitizerHook(val method: Method, val listArgIndex: Int)
data class FeedCsrFilterHook(val method: Method, val listArgIndex: Int)

data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

data class GameAdPayloadSnapshot(
    val target: Any,
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

data class GameAdPromiseSnapshot(
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

// ─── AdStoryInspector ─────────────────────────────────────────────────────────

/**
 * Accessors that must never be treated as an ad signal.
 *
 * Facebook's GraphQL models expose whole-tree debug serializers — on the build this was
 * traced, `toExpensiveHumanReadableDebugString()` returns the entire story as JSON. Those
 * dumps embed the schema's own field names, and Facebook's schema mentions sponsorship on
 * ordinary stories, so scanning that string for tokens like "sponsored" says true for
 * essentially EVERY story. That single accessor was the sole match on a profile timeline
 * story and it is what made ad detection there useless.
 *
 * They are also ruinously slow: the name says "Expensive" and it means it — serialising a
 * full story tree once per rendered post.
 */
private val DEBUG_SERIALIZER_NAME_FRAGMENTS = listOf(
    "DebugString", "toExpensive", "serialize", "toJson", "toJSON", "toRawString", "getRawJson"
)

private fun isDebugSerializerAccessor(name: String): Boolean =
    name == "toString" || DEBUG_SERIALIZER_NAME_FRAGMENTS.any { name.contains(it, ignoreCase = true) }

class AdStoryInspector(private val adKindEnumClass: Class<*>) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache      = ConcurrentHashMap<Class<*>, List<Field>>()
    private val allMethodCache      = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringMethodCache   = ConcurrentHashMap<Class<*>, List<Method>>()
    private val overridesToStringCache = ConcurrentHashMap<Class<*>, Boolean>()

    fun containsAdStory(
        value: Any?, depth: Int = 0, seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean = containsAdKind(value, depth, seen) && containsReelsAdSignal(value, 0, IdentityHashMap())

    private fun containsAdKind(value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Boolean {
        if (value == null || depth > 4) return false
        if (isAdKind(value)) return true
        val type = value.javaClass
        if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) return false
        if (seen.put(value, true) != null) return false
        if (value is Iterable<*>) { var n = 0; for (i in value) { if (containsAdKind(i, depth+1, seen)) return true; if (++n >= 8) break } }
        if (type.isArray) { val a = value as? Array<*>; if (a != null) { var n = 0; for (i in a) { if (containsAdKind(i, depth+1, seen)) return true; if (++n >= 8) break } } }
        for (m in enumMethodsFor(type)) if (isAdKind(runCatching { m.invoke(value) }.getOrNull())) return true
        for (f in fieldsFor(type)) if (containsAdKind(runCatching { f.get(value) }.getOrNull(), depth+1, seen)) return true
        return false
    }

    private fun containsReelsAdSignal(value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Boolean {
        if (value == null || depth > 4) return false
        if (value is CharSequence) return isReelsAdSignalText(value.toString())
        val type = value.javaClass
        if (isReelsAdSignalText(type.name)) return true
        if (type.isEnum) return isReelsAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        if (seen.put(value, true) != null) return false
        if (value is Iterable<*>) { var n = 0; for (i in value) { if (containsReelsAdSignal(i, depth+1, seen)) return true; if (++n >= 8) break } }
        if (type.isArray) { val a = value as? Array<*>; if (a != null) { var n = 0; for (i in a) { if (containsReelsAdSignal(i, depth+1, seen)) return true; if (++n >= 8) break } } }
        // Unoverridden toString() yields "<class name>@<hex hash>"; the class name is
        // already checked above and a hex hash matches no token, so skip the call.
        if (overridesToString(type) && isReelsAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (m in stringMethodsFor(type)) if (isReelsAdSignalText(runCatching { m.invoke(value) as? String }.getOrNull())) return true
        for (f in fieldsFor(type)) if (containsReelsAdSignal(runCatching { f.get(value) }.getOrNull(), depth+1, seen)) return true
        return false
    }

    private fun isAdKind(v: Any?) = v != null && v.javaClass == adKindEnumClass && v.toString() == "AD"

    private fun enumMethodsFor(type: Class<*>) = enumMethodCache.getOrPut(type) {
        val map = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m ->
                if (!m.isStatic && m.parameterCount == 0 && m.returnType == adKindEnumClass) {
                    m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}", m)
                }
            }; cur = cur.superclass
        }; map.values.toList()
    }

    private fun fieldsFor(type: Class<*>) = fieldCache.getOrPut(type) {
        val list = ArrayList<Field>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 24) {
            cur.declaredFields.forEach { f -> if (!f.isStatic && list.size < 24) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
        }; list
    }

    // Both of these were recomputed for every object visited by the recursive walk —
    // a full class-hierarchy scan plus a LinkedHashMap and one key String per method,
    // per node, per feed item. The result only depends on the Class, so it is cached.
    private fun stringMethodsFor(type: Class<*>) = stringMethodCache.getOrPut(type) {
        allMethodsFor(type).asSequence()
            .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && !isDebugSerializerAccessor(m.name) }
            .take(12).onEach { it.isAccessible = true }.toList()
    }

    private fun allMethodsFor(type: Class<*>): List<Method> = allMethodCache.getOrPut(type) {
        val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!m.isStatic) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }; cur = cur.superclass
        }; map.values.toList()
    }

    private fun overridesToString(type: Class<*>): Boolean = overridesToStringCache.getOrPut(type) {
        runCatching { type.getMethod("toString").declaringClass != Any::class.java }.getOrDefault(true)
    }

    private fun isReelsAdSignalText(v: String?): Boolean {
        if (v.isNullOrBlank()) return false
        return REELS_AD_SIGNAL_TOKENS.any { v.contains(it, ignoreCase = true) }
    }
}

// ─── FeedItemInspector ────────────────────────────────────────────────────────

class FeedItemInspector(itemContractTypes: Collection<Class<*>>) {
    // NOTE: every cache is declared BEFORE the accessor properties below. Kotlin runs
    // property initialisers top to bottom, and resolving the accessors already calls
    // allInstanceMethods() — if its cache were declared later it would still be null at
    // that moment and the constructor would throw.
    private val categoryMethodCache       = ConcurrentHashMap<Class<*>, Method>()
    private val edgeAccessorCache         = ConcurrentHashMap<Class<*>, Method>()
    private val edgeCategoryAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val feedUnitAccessorCache     = ConcurrentHashMap<Class<*>, Method>()
    private val backendDataAccessorCache  = ConcurrentHashMap<Class<*>, Method>()
    private val typeNameMethodCache       = ConcurrentHashMap<Class<*>, Method>()
    private val stringAccessorCache       = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache          = ConcurrentHashMap<Class<*>, List<Field>>()
    private val allInstanceMethodsCache   = ConcurrentHashMap<Class<*>, List<Method>>()
    private val overridesToStringCache    = ConcurrentHashMap<Class<*>, Boolean>()

    // Accessors are resolved purely by SHAPE (parameter count + return type), never by
    // obfuscated method name. Verified against FB 573: the item contract interface
    // exposes exactly one 0-arg boolean (network), one 0-arg Object (edge) and one
    // 0-arg model getter, so the type-based resolvers below pick the right ones.
    private val itemModelAccessor   = resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor    = resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor = resolveItemNetworkAccessor(itemContractTypes)

    private companion object {
        /** Marker stored for "this class has no such accessor", so misses are cached too. */
        val NO_ACCESSOR: Method = FeedItemInspector::class.java
            .getDeclaredMethod("absentAccessorSentinel")
    }

    @Suppress("unused")
    private fun absentAccessorSentinel() = Unit

    private data class FeedItemFacts(
        val modelCategory: String?,
        val edgeCategory: String?,
        val network: Boolean?,
        val inflatedUnitClass: String?,
        val inflatedTypeName: String?,
        val backendUnitClass: String?,
        val backendTypeName: String?
    )

    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (isDefinitelySponsoredFeedItem(value)) return true
        val model       = invokeNoThrow(itemModelAccessor, value)
        val edge        = edgeFrom(value)
        val feedUnit    = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)
        if (containsKnownAdSignals(value))       return true
        if (containsKnownAdSignals(model))       return true
        if (containsKnownAdSignals(edge))        return true
        if (containsKnownAdSignals(feedUnit))    return true
        if (containsKnownAdSignals(backendData)) return true
        return false
    }

    fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false
        val model         = invokeNoThrow(itemModelAccessor, value)
        val modelCategory = readCategory(model)
        if (isSafeFeedContainerCategory(modelCategory)) return false
        if (isSponsoredFeedCategory(modelCategory))     return true

        val edge         = edgeFrom(value)
        val edgeCategory = readEdgeCategory(edge) ?: readCategory(edge)
        if (isSafeFeedContainerCategory(edgeCategory)) return false
        if (isSponsoredFeedCategory(edgeCategory))     return true

        val feedUnit               = feedUnitFrom(edge)
        val backendData            = backendDataFrom(edge)
        val inflatedUnitClassName  = feedUnit?.javaClass?.name
        val backendUnitClassName   = backendData?.javaClass?.name
        if (inflatedUnitClassName in GRAPHQL_AD_FEED_UNIT_CLASSES ||
            backendUnitClassName in GRAPHQL_AD_FEED_UNIT_CLASSES) return true

        // Both sides are read, rather than the first non-null one, because this test can
        // only ever say "yes": a unit whose type name is in the set is an advertisement,
        // and one whose name is absent falls through to exactly the checks below.
        val feedUnitTypeName = readTypeName(feedUnit)
        val backendTypeName  = readTypeName(backendData)
        if (feedUnitTypeName in GRAPHQL_AD_FEED_UNIT_TYPE_NAMES ||
            backendTypeName  in GRAPHQL_AD_FEED_UNIT_TYPE_NAMES) return true

        val typeName = feedUnitTypeName ?: backendTypeName
        if (isLikelyAdTypeName(typeName) ||
            isAdSignalText(inflatedUnitClassName) ||
            isAdSignalText(backendUnitClassName)) return true

        return false
    }

    /** Kept as a separate hook from [isDefinitelySponsoredFeedItem] so the strict-block
     *  reason can later be differentiated (e.g. "strict" vs. a broader network-based
     *  reason) without changing [hookStoryPoolAdd]'s call site. */
    fun storyPoolBlockReason(value: Any?): String? =
        if (isDefinitelySponsoredFeedItem(value)) "strict" else null

    /** Resolves the edge for [value] and returns true only when its category is one of
     *  the strict FEED_COLLECTION_AD_CATEGORY_VALUES — used by the cached-feed section
     *  sanitizer and the addNewEdgeToCollection filter, where only an explicitly-tagged
     *  sponsored edge should be dropped (pagination-preserving). */
    fun isExplicitlySponsoredFeedEdge(value: Any?): Boolean {
        val edge = edgeFrom(value) ?: return false
        val edgeCategory = readEdgeCategory(edge) ?: readCategory(edge)
        return edgeCategory != null && edgeCategory in FEED_COLLECTION_AD_CATEGORY_VALUES
    }

    /** True only when [value] itself exposes a tracking payload naming an ad id, e.g. a
     *  string accessor returning `{"adid":"1202471980…"}`. Unlike [isSponsoredFeedItem]
     *  this does not walk into the edge/feedUnit/backendData siblings and does not fall
     *  back to category or generic ad-signal-token checks — a bare GraphQLStory on the
     *  profile timeline has no category at all, so those tests answered "ad" for every
     *  post there. The ad id is the only signal this checks. */
    fun hasAdTrackingId(value: Any?): Boolean {
        if (value == null) return false
        for (m in stringAccessorsFor(value.javaClass)) {
            val s = invokeNoThrow(m, value) as? String ?: continue
            if (s.contains("\"adid\"", ignoreCase = true)) return true
        }
        return false
    }

    fun describe(item: Any?): String {
        if (item == null) return "null"
        val facts = factsFor(item)
        val modelCategory     = facts.modelCategory ?: "unknown"
        val edgeCategory      = facts.edgeCategory ?: "unknown"
        val network           = facts.network?.toString() ?: "unknown"
        val inflatedUnitClass = facts.inflatedUnitClass ?: "null"
        val inflatedTypeName  = facts.inflatedTypeName ?: "unknown"
        val backendUnitClass  = facts.backendUnitClass ?: "null"
        val backendTypeName   = facts.backendTypeName ?: "unknown"
        return "modelCat=$modelCategory edgeCat=$edgeCategory isAd=${isSponsoredFeedItem(item)} " +
            "network=$network wrapper=${item.javaClass.name} " +
            "inflated=$inflatedUnitClass/$inflatedTypeName backend=$backendUnitClass/$backendTypeName"
    }

    private fun factsFor(item: Any?): FeedItemFacts {
        val model       = invokeNoThrow(itemModelAccessor, item)
        val edge        = edgeFrom(item)
        val feedUnit    = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)
        return FeedItemFacts(
            modelCategory     = readCategory(model),
            edgeCategory      = readEdgeCategory(edge) ?: readCategory(edge),
            network           = invokeNoThrow(itemNetworkAccessor, item) as? Boolean,
            inflatedUnitClass = feedUnit?.javaClass?.name,
            inflatedTypeName  = readTypeName(feedUnit),
            backendUnitClass  = backendData?.javaClass?.name,
            backendTypeName   = readTypeName(backendData)
        )
    }

    private fun edgeFrom(value: Any?): Any? {
        if (value == null) return null
        if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value
        invokeNoThrow(itemEdgeAccessor, value)?.let { d -> if (d.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return d }
        val fallback = cachedMethod(edgeAccessorCache, value.javaClass) {
            resolveChildAccessor(value) { it != null && it.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
        }
        return invokeNoThrow(fallback, value)
    }

    /** Tries the known obfuscated accessor names first ("BL9"/"A03"); falls back to the
     *  generic child-accessor heuristic, now also excluding "FeedBackendData" (the
     *  sibling node added by [backendDataFrom]) so the two never resolve to each other. */
    private fun feedUnitFrom(edge: Any?): Any? {
        if (edge == null) return null
        val accessor = cachedMethod(feedUnitAccessorCache, edge.javaClass) {
            resolveNamedNoArgAccessor(edge.javaClass, "BL9")
                ?: resolveNamedNoArgAccessor(edge.javaClass, "A03")
                ?: resolveChildAccessor(edge) { v ->
                    val cn = v?.javaClass?.name
                    cn in GRAPHQL_AD_FEED_UNIT_CLASSES ||
                    readTypeName(v)?.let { it != "FeedUnitEdge" && it != "FeedBackendData" } == true
                }
        }
        return invokeNoThrow(accessor, edge)
    }

    /** "FeedBackendData" is a sibling of the inflated feed unit on the same edge — a
     *  second, independent data path that can also carry ad signals (this is the
     *  upstream fix for ads that don't show up via the normal inflated feedUnit). */
    private fun backendDataFrom(edge: Any?): Any? {
        if (edge == null) return null
        val accessor = cachedMethod(backendDataAccessorCache, edge.javaClass) {
            resolveNamedNoArgAccessor(edge.javaClass, "BL0")
                ?: resolveNamedNoArgAccessor(edge.javaClass, "A05")
                ?: resolveChildAccessor(edge) { v -> readTypeName(v) == "FeedBackendData" }
        }
        return invokeNoThrow(accessor, edge)
    }

    private fun readEdgeCategory(value: Any?): String? {
        if (value == null) return null
        val accessor = cachedMethod(edgeCategoryAccessorCache, value.javaClass) {
            resolveNamedNoArgAccessor(value.javaClass, "B4k")
                ?: allInstanceMethods(value.javaClass).firstOrNull { m ->
                    m.parameterCount == 0 && m.returnType.isEnum &&
                    m.returnType.enumConstants?.any { val n = it.toString(); n == "SPONSORED" || n == "PROMOTION" } == true
                }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    /** Looks for an exact-named accessor on the item-contract interfaces first
     *  (the obfuscated name is stable across most Facebook builds for a given
     *  feed-item contract), before falling back to the generic shape-based search. */
    private fun resolveItemContractAccessor(itemContractTypes: Collection<Class<*>>, methodName: String): Method? =
        itemContractTypes.asSequence()
            .flatMap { allInstanceMethods(it).asSequence() }
            .firstOrNull { m -> m.parameterCount == 0 && m.name == methodName }
            ?.apply { isAccessible = true }

    private fun resolveNamedNoArgAccessor(type: Class<*>, methodName: String): Method? =
        allInstanceMethods(type).firstOrNull { m -> m.parameterCount == 0 && m.name == methodName }
            ?.apply { isAccessible = true }

    fun describeAccessors(): String =
        "model=${accessorName(itemModelAccessor)} edge=${accessorName(itemEdgeAccessor)} network=${accessorName(itemNetworkAccessor)}"

    private fun accessorName(method: Method?): String =
        method?.let { "${it.declaringClass.name}.${it.name}" } ?: "unresolved"

    private fun readCategory(value: Any?): String? {
        if (value == null) return null
        if (value.javaClass.isEnum) return value.toString()
        val accessor = cachedMethod(categoryMethodCache, value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { m ->
                m.parameterCount == 0 && m.returnType.isEnum &&
                m.returnType.enumConstants?.any { val n = it.toString(); n == "SPONSORED" || n == "PROMOTION" } == true
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    private fun readTypeName(value: Any?): String? {
        if (value == null) return null
        val accessor = cachedMethod(typeNameMethodCache, value.javaClass) {
            resolveNamedNoArgAccessor(value.javaClass, "getTypeName")
                ?: allInstanceMethods(value.javaClass).firstOrNull { m ->
                    m.parameterCount == 0 && m.returnType == String::class.java && m.name == "getTypeName"
                }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value) as? String
    }

    /**
     * Memoises accessor resolution per class — **including misses**.
     *
     * Previously a null result was not stored, so every feed item of a class that has no
     * matching accessor re-ran the resolver on every single call. Those resolvers are
     * not cheap: [resolveChildAccessor] walks the whole class hierarchy and then
     * reflectively *invokes* each candidate getter until one matches. On a scrolling
     * feed that was hundreds of wasted reflective invocations per second, forever.
     * A miss is now recorded with [NO_ACCESSOR] and costs one map lookup thereafter.
     */
    private fun cachedMethod(cache: ConcurrentHashMap<Class<*>, Method>, type: Class<*>, resolver: () -> Method?): Method? {
        cache[type]?.let { return if (it === NO_ACCESSOR) null else it }
        val resolved = resolver()
        cache.putIfAbsent(type, resolved ?: NO_ACCESSOR)
        return resolved
    }

    // Excludes "A02"/"BG7" in addition to "clone" — those are the named edge/other
    // accessors that could otherwise be mistakenly picked up by this generic search.
    private fun resolveItemModelAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m ->
            m.parameterCount == 0 && m.name != "clone" && m.name != "A02" && m.name != "BG7" &&
            !m.returnType.isPrimitive && m.returnType != Any::class.java && m.returnType != String::class.java && !m.returnType.isEnum
        }
        ?.apply { isAccessible = true }

    private fun resolveItemEdgeAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m ->
            m.parameterCount == 0 && m.name != "clone" &&
            (m.returnType == Any::class.java || m.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS)
        }
        ?.apply { isAccessible = true }

    private fun resolveItemNetworkAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m -> m.parameterCount == 0 && m.returnType == Boolean::class.javaPrimitiveType }
        ?.apply { isAccessible = true }

    private fun resolveChildAccessor(target: Any, acceptsValue: (Any?) -> Boolean): Method? =
        allInstanceMethods(target.javaClass).asSequence()
            .filter { m -> m.parameterCount == 0 && !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != String::class.java && !m.returnType.isEnum && m.declaringClass != Any::class.java }
            .sortedByDescending { m -> scoreChildAccessor(m.returnType) }
            .firstOrNull { m -> acceptsValue(invokeNoThrow(m.apply { isAccessible = true }, target)) }

    private fun scoreChildAccessor(type: Class<*>): Int = when {
        type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS                             -> 4
        type.name.startsWith("com.facebook.graphql.model.")                   -> 3
        type.name.startsWith("com.facebook.")                                 -> 2
        !type.name.startsWith("java.") && !type.name.startsWith("javax.") &&
        !type.name.startsWith("android.") && !type.name.startsWith("kotlin.") -> 1
        else                                                                   -> 0
    }

    private fun containsKnownAdSignals(value: Any?): Boolean {
        if (value == null) return false
        if (value is CharSequence) return isAdSignalText(value.toString())
        val type = value.javaClass
        if (isAdSignalText(type.name)) return true
        if (type.isEnum) return isAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        // When toString() is not overridden it returns "<class name>@<hex hash>". The
        // class name was already tested one line above and a hex hash cannot contain any
        // of the tokens, so calling it would be pure waste — and on GraphQL model
        // objects an overridden toString can serialise a whole subtree.
        if (overridesToString(type) && isAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (m in stringAccessorsFor(type)) if (isAdSignalText(invokeNoThrow(m, value) as? String)) return true
        for (f in stringFieldsFor(type)) if (isAdSignalText(runCatching { f.get(value) as? String }.getOrNull())) return true
        return false
    }

    private fun overridesToString(type: Class<*>): Boolean = overridesToStringCache.getOrPut(type) {
        runCatching { type.getMethod("toString").declaringClass != Any::class.java }.getOrDefault(true)
    }

    private fun stringAccessorsFor(type: Class<*>) = stringAccessorCache.getOrPut(type) {
        allInstanceMethods(type).asSequence()
            .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && m.declaringClass != Any::class.java && !isDebugSerializerAccessor(m.name) }
            .take(12).onEach { m -> m.isAccessible = true }.toList()
    }

    private fun stringFieldsFor(type: Class<*>) = stringFieldCache.getOrPut(type) {
        val list = ArrayList<Field>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 12) {
            cur.declaredFields.forEach { f -> if (!f.isStatic && f.type == String::class.java && list.size < 12) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
        }; list
    }

    /** Case-insensitive containment without allocating a lowercased copy of every
     *  candidate string. Same tokens, same result. */
    fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return FEED_AD_SIGNAL_TOKENS.any { value.contains(it, ignoreCase = true) }
    }

    private fun isSponsoredFeedCategory(v: String?)    = v != null && v in FEED_AD_CATEGORY_VALUES
    private fun isSafeFeedContainerCategory(v: String?) = v != null && v in FEED_SAFE_CONTAINER_CATEGORY_VALUES
    private fun isLikelyAdTypeName(v: String?)          = v != null && (v.contains("QuickPromotion", ignoreCase = true) || isAdSignalText(v))

    /**
     * Memoised: the walk allocates a LinkedHashMap plus one key String per method of the
     * whole hierarchy, and it was re-run on every resolution attempt. Same result for a
     * given Class every time, so it is computed once. Order is preserved, so every
     * "firstOrNull" caller keeps picking exactly the method it picked before.
     */
    private fun allInstanceMethods(type: Class<*>): List<Method> =
        allInstanceMethodsCache.getOrPut(type) { computeAllInstanceMethods(type) }

    private fun computeAllInstanceMethods(type: Class<*>): List<Method> {
        val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!m.isStatic) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }
            cur.interfaces.forEach { iface -> iface.declaredMethods.forEach { m -> if (!m.isStatic) { m.isAccessible = true; map.putIfAbsent("${iface.name}#${m.name}/${m.parameterCount}", m) } } }
            cur = cur.superclass
        }; return map.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?) =
        if (method == null || target == null) null else runCatching { method.invoke(target) }.getOrNull()
}


/** Stable per-method key used to dedup hook installation across the DexKit-resolved and
 *  the FB571 hardcoded-name fast paths (both can resolve the same underlying method). */
fun methodHookKey(method: Method): String =
    "${method.declaringClass.name}#${method.name}(" +
        method.parameterTypes.joinToString(",") { it.name } + "):${method.returnType.name}"

// ─── Hook installers – Reels / list-builder ───────────────────────────────────

fun hookListBuilderAppend(method: Method, inspector: AdStoryInspector) {
    val listArgIndex = method.listParameterIndexes().singleOrNull()
    if (listArgIndex == null) {
        return
    }
    method.hookMethod {
        before { param ->
            param.setObjectExtra(BEFORE_SIZE_EXTRA, (param.args.getOrNull(listArgIndex) as? List<*>)?.size ?: -1)
        }
        after { param ->
            val beforeSize = param.getObjectExtra(BEFORE_SIZE_EXTRA) as? Int ?: return@after
            val list = param.args.getOrNull(listArgIndex) as? MutableList<Any?> ?: return@after
            if (beforeSize < 0 || beforeSize > list.size) return@after
            var removed = 0
            for (i in list.lastIndex downTo beforeSize) { if (inspector.containsAdStory(list[i])) { list.removeAt(i); removed++ } }
        }
    }
}

fun hookListResultFilter(method: Method, source: String, inspector: AdStoryInspector) {
    method.hookMethod {
        after { param ->
            val result = param.result as? MutableList<Any?> ?: return@after
            filterAdItems(result, inspector)
        }
    }
}

fun hookPluginPackFallback(method: Method, inspector: AdStoryInspector) {
    method.hookMethod {
        before { param ->
            if (isAdOnlyPluginPack(param.thisObject)) {
                param.result = arrayListOf<Any?>(); return@before
            }
            if (inspector.containsAdStory(param.thisObject)) {
                param.result = arrayListOf<Any?>()
            }
        }
        after { param ->
            if (isAdOnlyPluginPack(param.thisObject)) return@after
            val result = param.result as? MutableList<Any?> ?: return@after
            filterAdItems(result, inspector)
        }
    }
}

/**
 * True when a video plugin pack or plugin descriptor exists purely to serve ads, in which
 * case its whole contribution is dropped rather than filtered item by item.
 *
 * Decided from the object's OWN name getter rather than from its (obfuscated) class name,
 * so it works for packs whose name is assembled at runtime. The token list is derived from
 * pack names observed in a live log, not guessed:
 *
 *   emptied  - VideoAdsPluginPack, MarketplaceAdsPluginPack, AdsSmartOverlayPluginPack,
 *              AdBreakPluginPack, AdBreakFooterPluginPack, PlayableAdOverlayPluginPack,
 *              InlineVideoAdsFooterPluginPack,
 *              SmartStates-REELS_DIRECT_MONETIZATION_ADS.pack
 *   untouched - GrootCore, GrootInlineVideo360, LiveInline, HuddleInline, AudioModeInline,
 *              ThirtySecondClip, VideoPlayerReliability, WatchTopicsInline,
 *              ConcurrentViewCount, Videohighlights, Tofu, UnifiedPlayer,
 *              FbShortsViewer, InlineVideoFooterCue
 *
 * Deliberately NOT a bare "Ad": that substring hides inside ordinary words such as
 * Loading and Adaptive, and matching it would silently disable organic plugins.
 */
private fun isAdOnlyPluginPack(instance: Any): Boolean {
    val className = instance.javaClass.name
    return marketplaceAdsPackCache.getOrPut(className) {
        runCatching {
            instance.javaClass.declaredMethods
                .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && !m.isStatic }
                .any { m ->
                    m.isAccessible = true
                    val name = m.invoke(instance) as? String ?: return@any false
                    AD_ONLY_PLUGIN_PACK_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
                }
        }.getOrDefault(false)
    }
}

/**
 * See [isAdOnlyPluginPack].
 *
 * "SqueezebackAd" was added after listing every plugin and descriptor name in the app
 * that contains an ad token and checking which of them the three original tokens miss.
 * All but one of the misses are trackers or organic plugins; the exception is the
 * squeezeback ad — the advert that shrinks a live video into a corner while it plays —
 * which is delivered by SqueezebackAdPlugin and SqueezebackAdFullscreenPlugin and was
 * therefore never refused at the descriptor gate.
 *
 * Still deliberately NOT a bare "Ad": that substring hides inside ordinary words such
 * as Loading and Adaptive, and matching it would silently disable organic plugins.
 */
val AD_ONLY_PLUGIN_PACK_TOKENS = listOf("Ads", "AdBreak", "AdOverlay", "SqueezebackAd")

private val pluginHooksInstalled = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

/**
 * Empties the plugin list of an ads-only pack.
 *
 * Hooked on EVERY pack's list getter, then filtered per instance. Two reasons it cannot
 * be done by fingerprinting pack names instead:
 *  - some ad packs inherit the getter from a shared base they share with organic packs,
 *    so the method alone does not identify the pack;
 *  - some ad pack names are assembled at runtime and no static string can match them.
 */
fun hookPluginPackList(method: Method) {
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        after { param ->
            val instance = param.thisObject ?: return@after
            if (!isAdOnlyPluginPack(instance)) return@after
            if ((param.result as? Collection<*>)?.isEmpty() == true) return@after
            param.result = emptyList<Any?>()
        }
    }
}

/**
 * Disables an ads-only plugin DESCRIPTOR at its own eligibility gate.
 *
 * Descriptors sit one level below packs: a pack lists them, then the player asks each one
 * whether it applies to the video being played. This is the layer that catches ad plugins
 * delivered by packs no name-based hook can reach.
 */
fun hookPluginDescriptorGate(method: Method) {
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        before { param ->
            val instance = param.thisObject ?: return@before
            if (!isAdOnlyPluginPack(instance)) return@before
            param.result = false
        }
    }
}

/**
 * Removes ad plugins from a list built by a static builder rather than by a pack object.
 *
 * Filters ELEMENT BY ELEMENT and never empties the list wholesale. That distinction is
 * not cosmetic: the builder this targets is a general video-surface builder that happens
 * to contain a direct-monetization ads branch — a runtime trace showed one of its methods
 * returning fifteen plugins, nearly all of them playback controls. Emptying it would
 * strip the whole player, not the ads.
 *
 * Each element is judged by its own name getter, the same test used for packs and
 * descriptors, so organic plugins in the same list survive untouched.
 */
/**
 * Skips rendering a profile timeline story that carries an advertisement's tracking id.
 *
 * Only the render is short-circuited, and only for that one story: the component this
 * hooks also draws every organic post on the page, so suppressing it wholesale blanks the
 * profile. Per-story is the only safe granularity here.
 *
 * Detection is [FeedItemInspector.hasAdTrackingId] and nothing else. Earlier attempts used
 * the category test and the token scan; on this surface a story carries no category, and
 * both tests ended up answering "ad" for every post, which emptied the page three times
 * over. An ad id is present or it is not.
 */
fun hookTimelineStoryRender(method: Method, inspector: FeedItemInspector) {
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    // The story type is derived, not guessed: the component declares a private boolean
    // guard taking exactly the story it renders, so that parameter names the type, and the
    // field of that type is the story. "First interface-typed field" would pick the wrong
    // one — the component holds several unrelated obfuscated fields.
    val storyType = method.declaringClass.declaredMethods.firstOrNull { candidate ->
        candidate.returnType == Boolean::class.javaPrimitiveType && candidate.parameterCount == 1
    }?.parameterTypes?.firstOrNull() ?: return

    val storyField = method.declaringClass.declaredFields.firstOrNull { field ->
        !field.isStatic && field.type == storyType
    }?.apply { isAccessible = true } ?: return

    method.hookMethod {
        before { param ->
            val story = runCatching { storyField.get(param.thisObject) }.getOrNull() ?: return@before
            if (!runCatching { inspector.hasAdTrackingId(story) }.getOrDefault(false)) return@before
            param.result = null
        }
    }
}

// ─── Hook installers – ad REQUEST layer ───────────────────────────────────────
//
// Everything below stops an advertisement from being asked for, as opposed to removing
// it once it has arrived. That is the cheaper half of the job and the less visible one:
// a slot that was never filled leaves no gap to collapse, no placeholder to blank and
// no impression to report, and it saves the bandwidth the creative would have cost.
//
// Each installer refuses to touch a method whose return type it cannot satisfy,
// because the failure mode of guessing wrong here is a ClassCastException inside
// Facebook's own code rather than a missed ad.

/**
 * Skips a `void` ad-request method entirely.
 *
 * Restricted to `void` on purpose. Xposed reports "skip the body" by setting a result,
 * and for any other return type that result has to be a value the caller can use — a
 * null returned to code expecting a list or a primitive crashes the surface instead of
 * silencing it. Callers that need a value use [hookNullAdResult] instead.
 */
fun hookAdRequestNoOp(method: Method) {
    if (method.returnType != Void.TYPE) return
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        before { param -> param.result = null }
    }
}

/**
 * Answers "there is no advertisement to serve" from a method whose whole job is to hand
 * one back.
 *
 * Distinct from [hookAdRequestNoOp], which only handles `void`: these methods
 * return a single feed-unit edge and already have a documented no-ad path — the vendor
 * logs `empty_pool` and the caller moves on to the next organic story. Null is the value
 * that path produces, so it is the value returned here.
 *
 * Shape-checked like the other request-layer installers: a primitive or `void` return
 * cannot take a null, so those are left alone rather than crashed.
 */
fun hookNullAdResult(method: Method) {
    val returnType = method.returnType
    if (returnType == Void.TYPE || returnType.isPrimitive) return
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        before { param -> param.result = null }
    }
}

/**
 * Stops Instant Games advertising being loaded into the process at all.
 *
 * Three methods on one unobfuscated class, each shape-checked before it is touched:
 *
 *  - `isInstantGamesAdsLoaded()` is answered `false`, so every caller sees the state it
 *    already sees on a device where the module download failed.
 *  - `loadInstantGamesAdsVoltronModule(Context)` is skipped, so the download is never
 *    started. It is `void` and already logs-and-returns on IOException, so callers do
 *    not depend on it having succeeded.
 *  - `resetInstantGamesAdsModuleLoadState()` is deliberately left alone: it clears the
 *    loader's cached state, and letting it run keeps the loader consistent with itself.
 *
 * Complementary to the JavaScript-bridge hooks rather than a replacement for them. The
 * bridge answers a game that asks for an ad; this removes the code that would have
 * served one. A game running against an older cached module still meets the bridge.
 *
 * Returns silently when the class is not present. That is the expected result on FB575
 * and later, where the class moved into the Voltron module — see
 * [QUICKSILVER_ADS_LOADER_CLASS] for why, and for which hooks carry the surface instead.
 */
fun hookInstantGamesAdsLoader(classLoader: ClassLoader) {
    val loaderClass = runCatching { classLoader.loadClass(QUICKSILVER_ADS_LOADER_CLASS) }.getOrNull() ?: return

    runCatching {
        loaderClass.declaredMethods
            .firstOrNull { it.name == QUICKSILVER_ADS_LOADED_METHOD && it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.let { hookForceBoolean(it, false) }
    }

    runCatching {
        loaderClass.declaredMethods
            .firstOrNull { it.name == QUICKSILVER_ADS_LOAD_METHOD && it.returnType == Void.TYPE }
            ?.apply { isAccessible = true }
            ?.let { hookAdRequestNoOp(it) }
    }
}

/**
 * Forces an eligibility gate to answer [value].
 *
 * The gates this is pointed at are the ones an ad pipeline asks before allocating a
 * slot, so answering "not eligible" removes the slot rather than emptying it.
 */
fun hookForceBoolean(method: Method, value: Boolean = false) {
    if (method.returnType != Boolean::class.javaPrimitiveType && method.returnType != Boolean::class.javaObjectType) return
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        before { param -> param.result = value }
    }
}

fun hookAdPluginListBuilder(method: Method) {
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        after { param ->
            val current = param.result as? Iterable<*> ?: return@after
            val kept = ArrayList<Any?>()
            var removed = 0
            for (plugin in current) {
                if (plugin != null && isAdOnlyPluginPack(plugin)) removed++ else kept.add(plugin)
            }
            if (removed == 0) return@after
            param.result = buildImmutableListLike(param.result, kept) ?: return@after
        }
    }
}

// ─── Hook installers – Feed CSR / late-list ───────────────────────────────────

fun hookFeedCsrFilterInput(hook: FeedCsrFilterHook, inspector: FeedItemInspector): Boolean {
    if (!feedCsrMethodsHooked.add(methodHookKey(hook.method))) return false
    hook.method.hookMethod {
        before { param ->
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return@before
            val kept = ArrayList<Any?>(); var removed = 0
            // Strict check on the way IN: Facebook's own pipeline hasn't finished
            // resolving every item yet here, so the broader heuristic risks false
            // positives — only drop items we're certain are ads.
            for (item in originalList) { if (inspector.isDefinitelySponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed <= 0) return@before
            buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), kept)?.let { param.args[hook.listArgIndex] = it }
        }
        after { param ->
            val resultItems = extractFeedItemsFromResult(param.result) ?: return@after
            val kept = ArrayList<Any?>(); var removed = 0
            // Upstream uses the STRICT check here too (isDefinitelySponsoredFeedItem),
            // not the broader heuristic — kept 1:1 with FacebookAppAdsRemover.
            for (item in resultItems) { if (inspector.isDefinitelySponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed > 0) replaceFeedItemsInResult(param, kept)
        }
    }
    return true
}

fun hookLateFeedListSanitizer(hook: FeedListSanitizerHook, inspector: FeedItemInspector): Boolean {
    if (!lateFeedMethodsHooked.add(methodHookKey(hook.method))) return false
    hook.method.hookMethod {
        before { param ->
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return@before
            val kept = ArrayList<Any?>(); var removed = 0
            // Upstream uses the STRICT check (isDefinitelySponsoredFeedItem) — kept 1:1.
            for (item in originalList) { if (inspector.isDefinitelySponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed <= 0) return@before
            buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), kept)?.let {
                param.args[hook.listArgIndex] = it
            }
        }
    }
    return true
}

fun hookStoryPoolAdd(method: Method, inspector: FeedItemInspector) {
    method.hookMethod {
        before { param ->
            val item = param.args.getOrNull(0)
            // Block only what is DEFINITELY sponsored (strict). Upstream also calls
            // isSponsoredFeedItem(broad) + describe() here, but only to feed its logger; with no
            // logger those two heavy reflection walks would run for every story while scrolling
            // for nothing, so they are left out to save battery.
            if (inspector.storyPoolBlockReason(item) == null) return@before
            param.result = false
        }
    }
}

fun hookInstreamBannerEligibility(method: Method) {
    method.hookMethod {
        before { param -> param.result = false }
    }
}

fun hookIndicatorPillAdEligibility(method: Method) {
    method.hookMethod {
        before { param -> param.result = false }
    }
}

fun hookReelsBannerRender(method: Method) {
    method.hookMethod {
        before { param -> param.result = null }
    }
}

// ─── Hook installers – Sponsored pool ────────────────────────────────────────

fun hookSponsoredPoolAdd(method: Method): Boolean {
    if (!sponsoredPoolMethodsHooked.add(methodHookKey(method))) return false
    method.hookMethod {
        before { param -> param.result = false }
    }
    return true
}

fun hookSponsoredStoryNext(method: Method) {
    method.hookMethod {
        before { param -> param.result = null }
    }
}

fun hookSponsoredPoolListMethods(poolClass: Class<*>) {
    poolClass.declaredMethods.filter { m -> !m.isStatic && m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType) }.forEach { m ->
        m.isAccessible = true
        m.hookMethod { before { param -> param.result = arrayListOf<Any?>() } }
    }
}

fun hookSponsoredPoolResultMethods(poolClass: Class<*>) {
    poolClass.declaredMethods.filter { m ->
        !m.isStatic && isSponsoredResultCarrier(m.returnType) &&
        (m.parameterCount == 0 || (m.parameterCount == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType))
    }.forEach { m ->
        m.isAccessible = true
        m.hookMethod { before { param -> buildSponsoredEmptyResult(m.returnType)?.let { param.result = it } } }
    }
}

/** Blocks any list/collection-returning getter on the sponsored story manager class
 *  whose params are limited to int/long/boolean (paging/cursor style accessors) —
 *  these back the "next sponsored story" vending path alongside the single-item
 *  [hookSponsoredStoryNext] hook. */
fun hookSponsoredStoryListMethods(managerClass: Class<*>) {
    managerClass.declaredMethods.filter { m -> !m.isStatic && isSponsoredStoryListMethod(m) }.forEach { m ->
        m.isAccessible = true
        m.hookMethod {
            before { param ->
                buildEmptyListReturn(m.returnType)?.let { param.result = it }
            }
        }
    }
}

private fun isSponsoredStoryListMethod(method: Method): Boolean {
    if (method.parameterCount > 2) return false
    if (!Iterable::class.java.isAssignableFrom(method.returnType) &&
        method.returnType.name != "com.google.common.collect.ImmutableList") {
        return false
    }
    return method.parameterTypes.all { type ->
        type == Int::class.javaPrimitiveType || type == Long::class.javaPrimitiveType || type == Boolean::class.javaPrimitiveType
    }
}

private fun buildEmptyListReturn(returnType: Class<*>): Any? {
    if (returnType.name == "com.google.common.collect.ImmutableList") {
        return runCatching {
            val of = returnType.getDeclaredMethod("of")
            of.isAccessible = true
            of.invoke(null)
        }.getOrNull()
    }
    return when {
        returnType.isAssignableFrom(ArrayList::class.java) -> arrayListOf<Any?>()
        Iterable::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
        else -> null
    }
}

// ─── List builder method resolution (flexible, score-based) ──────────────────
//
// Facebook occasionally ships a list-builder variant with a different parameter
// count/order than the structural 6-param shape we search for at the DexKit level
// (see listBuilderClassFingerprint). Once the CLASS is resolved — which IS cached
// via DexKit — we pick the append/factory method by scoring every candidate method
// on that class via plain reflection, instead of requiring an exact param shape.
// This mirrors upstream FacebookAppAdsRemover's resolveAppendMethod/resolveFactoryMethod.

private fun Method.listParameterIndexes(): List<Int> =
    parameterTypes.mapIndexedNotNull { index, type -> index.takeIf { List::class.java.isAssignableFrom(type) } }

private fun resolveListBuilderMethods(clazz: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    (clazz.declaredMethods + clazz.methods).forEach { method ->
        if (method.name != "<init>" && method.name != "<clinit>") {
            methods.putIfAbsent("${method.name}/${method.parameterCount}/${method.isStatic}", method)
        }
    }
    return methods.values.toList()
}

private fun scoreAppendMethod(method: Method, owner: Class<*>): Int {
    val listIndex = method.listParameterIndexes().firstOrNull() ?: return Int.MIN_VALUE
    var score = 0
    if (listIndex == method.parameterCount - 1) score += 10_000
    if (method.parameterCount == 6) score += 5_000
    if (!method.isStatic) score += 2_000
    if (method.isStatic && method.parameterTypes.getOrNull(1) == owner) score += 1_500
    if (method.isStatic && method.parameterTypes.firstOrNull() == owner) score += 750
    score -= method.parameterCount * 10
    return score
}

private fun scoreFactoryMethod(method: Method, owner: Class<*>): Int {
    var score = 0
    if (method.parameterCount == 6) score += 4_000
    if (method.parameterCount == 5) score += 3_000
    if (method.parameterTypes.getOrNull(1) == owner) score += 2_000
    if (method.parameterTypes.firstOrNull() == owner) score += 1_000
    if (method.parameterTypes.firstOrNull()?.name == "com.facebook.auth.usersession.FbUserSession") score += 500
    score -= method.parameterCount * 10
    return score
}

/** Resolves the static/instance "append item(s) to the in-progress Reels list" method:
 *  exactly one List-typed parameter, positioned last, on the resolved list-builder class. */
fun resolveListBuilderAppendMethod(listBuilderClass: Class<*>): Method =
    resolveListBuilderMethods(listBuilderClass)
        .filter { method ->
            method.returnType == Void.TYPE &&
            method.listParameterIndexes().size == 1 &&
            method.listParameterIndexes().first() == method.parameterCount - 1
        }
        .maxByOrNull { method -> scoreAppendMethod(method, listBuilderClass) }
        ?.apply { isAccessible = true }
        ?: error("Unable to resolve the Facebook Reels list append method on ${listBuilderClass.name}")

/** Resolves the static "ArrayList factory(listBuilder, ..., boolean)" method —
 *  optional: not every Facebook build has a separate factory method. */
fun resolveListBuilderFactoryMethod(listBuilderClass: Class<*>): Method? =
    resolveListBuilderMethods(listBuilderClass)
        .filter { method ->
            method.isStatic &&
            method.returnType == ArrayList::class.java &&
            method.parameterTypes.lastOrNull() == Boolean::class.javaPrimitiveType &&
            (method.parameterTypes.firstOrNull() == listBuilderClass || method.parameterTypes.getOrNull(1) == listBuilderClass)
        }
        .maxByOrNull { method -> scoreFactoryMethod(method, listBuilderClass) }
        ?.apply { isAccessible = true }

// ─── Hook installers – Story ad providers ────────────────────────────────────

fun hookStoryAdsNoOp(method: Method, reason: String = "story ad", source: String = method.declaringClass.name) {
    method.hookMethod {
        before { param ->
            param.result = null
        }
    }
}

fun hookStoryAdsMerge(method: Method, source: String = method.declaringClass.name) {
    method.hookMethod {
        before { param ->
            val originalBuckets = param.args.getOrNull(2)
            if (originalBuckets != null) {
                param.result = originalBuckets
            }
        }
    }
}

fun hookStoryAdProvider(provider: StoryAdProviderHooks) {
    if (!storyAdProviderClassesHooked.add(provider.providerClass.name)) return
    provider.mergeMethod?.let { method ->
        hookStoryAdsMerge(method, provider.providerClass.name)
    }
    provider.fetchMoreAdsMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad fetchMoreAds", provider.providerClass.name)
    }
    provider.deferredUpdateMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad deferred update", provider.providerClass.name)
    }
    provider.insertionTriggerMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad insertion trigger", provider.providerClass.name)
    }
}

// ─── Hook installers – Game ads ───────────────────────────────────────────────

fun hookGameAdRequest(method: Method) {
    method.hookMethod {
        before { param ->
            val payload = param.args.getOrNull(0) ?: return@before
            val messageType = inferGameAdMessageType(method, payload)
            rememberGameAdPayload(param.thisObject, payload, messageType)
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType, "request ${method.declaringClass.name}.${method.name}")) { param.result = null; return@before }
            if (!shouldAutofixGameAdMessage(messageType)) return@before
            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
            }
        }
    }
}

fun hookGameAdBridge(method: Method) {
    method.hookMethod {
        before { param ->
            val raw = param.args.getOrNull(0) as? String ?: return@before
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@before
            val type = payload.optString("type"); if (type !in GAME_AD_MESSAGE_TYPES) return@before
            rememberGameAdPayload(param.thisObject, payload, type)
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, type, "bridge ${method.declaringClass.name}.${method.name}")) { param.result = null; return@before }
            if (!shouldAutofixGameAdMessage(type)) return@before
            if (resolveGameAdPayload(param.thisObject, payload, type)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, type)
                param.result = null
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
            }
        }
    }
}

/** Hook resolve/reject methods on the bridge class for deeper interception. */
fun hookGameAdResultMethods(bridgeClass: Class<*>) {
    if (!gameAdResultHooksInstalled.compareAndSet(0, 1)) return
    val resolveMethod = resolveGameAdResolveMethod(bridgeClass)
    val rejectMethod  = resolveGameAdRejectMethod(bridgeClass)
    val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(bridgeClass)

    resolveMethod?.let { m ->
        m.hookMethod {
            before { param ->
                val promiseId = param.args.getOrNull(0) as? String ?: return@before
                val snapshot = gameAdPromiseSnapshots[promiseId] ?: return@before
                if (snapshot.messageType !in GAME_AD_MESSAGE_TYPES) return@before
                if (!shouldAutofixGameAdMessage(snapshot.messageType)) return@before
                val original = param.args.getOrNull(1)
                param.args[1] = forceGameAdSuccessResult(promiseId, original, snapshot.payload, snapshot.messageType)
            }
        }
    }

    if (rejectMethod != null && resolveMethod != null) {
        rejectMethod.hookMethod {
            before { param ->
                val promiseId = param.args.getOrNull(0) as? String ?: return@before
                val reason = param.args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return@before
                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(promiseId, null, snapshot?.payload, snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason))
                runCatching { XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success)); param.result = null }
            }
        }
    }

    if (bridgeRejectMethod != null && resolveMethod != null && bridgeRejectMethod != rejectMethod) {
        bridgeRejectMethod.hookMethod {
            before { param ->
                val payload = param.args.getOrNull(2) as? JSONObject ?: return@before
                val promiseId = extractPromiseId(payload) ?: return@before
                val reason = param.args.take(2).joinToString(" ") { it?.toString().orEmpty() }
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return@before
                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(promiseId, null, snapshot?.payload ?: payload, snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason))
                runCatching { XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success)); param.result = null }
            }
        }
    }
}

/** Hook Bundle-based service dispatch methods on the bridge class. */
fun hookGameAdServiceDispatchMethods(bridgeClass: Class<*>) {
    if (!gameAdServiceDispatchHooksInstalled.compareAndSet(0, 1)) return
    val methods = (bridgeClass.declaredMethods + bridgeClass.methods).filter { m ->
        !m.isStatic && m.returnType == Void.TYPE && m.parameterCount == 2 && m.parameterTypes[0] == Bundle::class.java
    }.distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
    methods.forEach { m ->
        m.isAccessible = true
        m.hookMethod {
            before { param ->
                val bundle = param.args.getOrNull(0) as? Bundle ?: return@before
                val messageType = param.args.getOrNull(1)?.toString()?.lowercase()?.takeIf { it in GAME_AD_MESSAGE_TYPES } ?: return@before
                val payload = buildGameAdPayloadFromServiceBundle(bundle, messageType)
                rememberGameAdPayload(param.thisObject, payload, messageType)
                if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType, "service dispatch ${m.declaringClass.name}.${m.name}")) { param.result = null; return@before }
                if (!shouldAutofixGameAdMessage(messageType)) return@before
                if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                    dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType); param.result = null
                }
            }
        }
    }
}

fun hookPlayableAdActivity(method: Method) {
    method.hookMethod {
        after { param ->
            val activity = param.thisObject as? Activity ?: return@after
            if (activity.javaClass.name != method.declaringClass.name) return@after
            handleGameAdActivity(activity, "direct hook ${method.declaringClass.name}.${method.name}")
        }
    }
}

fun hookGlobalGameAdActivityLifecycleFallback() {
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods)
        .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }?.apply { isAccessible = true } ?: return
    onResume.hookMethod {
        after { param ->
            val activity = param.thisObject as? Activity ?: return@after
            // Always schedule a surface sweep on resume (catches async ad loads)
            scheduleGameAdSurfaceSweep(activity.window?.decorView, "activity resume ${activity.javaClass.name}")
            if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return@after
            handleGameAdActivity(activity, "global lifecycle fallback")
        }
    }
}

fun hookGameAdActivityLaunchFallbacks() {
    val methods = LinkedHashMap<String, Method>()
    listOf(Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java).forEach { type ->
        (type.declaredMethods + type.methods).filter { m ->
            m.name in setOf("execStartActivity","startActivity","startActivityForResult","startActivityIfNeeded") &&
            m.parameterTypes.any { it == Intent::class.java }
        }.forEach { m -> m.isAccessible = true; methods.putIfAbsent("${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString(",") { it.name }})", m) }
    }
    methods.values.forEach { m ->
        runCatching {
            m.hookMethod {
                before { param ->
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return@before
                    val target = intent.component?.className ?: return@before
                    if (target !in GAME_AD_ACTIVITY_CLASS_NAMES) return@before
                    if (!shouldBlockGameAdActivityLaunch(target)) return@before
                    completeRecentGameAdRequests("launch fallback $target")
                    param.result = if (m.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            }
        }
    }
}

/** Hook ViewGroup.addView, TextView.setText, View.setContentDescription, WebView
 *  methods to catch native ad views and text-marker-based ad cards. */
fun hookGlobalGameAdSurfaceFallbacks() {
    if (!gameAdSurfaceHooksInstalled.compareAndSet(0, 1)) return

    (ViewGroup::class.java.declaredMethods + ViewGroup::class.java.methods)
        .filter { m -> m.name == "addView" && m.parameterTypes.any { it == View::class.java } }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            m.hookMethod {
                after { param ->
                    val parent = param.thisObject as? ViewGroup
                    val child = param.args.firstOrNull { it is View } as? View ?: return@after
                    when {
                        isPotentialNativeGameAdView(child) -> {
                            hideLikelyAdContainer(child, "native ad view add ${child.javaClass.name}")
                            scheduleGameAdSurfaceSweep(child, "native ad view add ${child.javaClass.name}")
                        }
                        isPotentialExplicitFeedAdMarkerView(child) -> {
                            hideLikelyAdContainer(child, "explicit feed ad view add ${child.javaClass.name}")
                            scheduleGameAdSurfaceSweep(child, "explicit feed ad view add ${child.javaClass.name}")
                        }
                        ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedAdMarkerView(child) -> {
                            hideLikelyAdContainer(child, "feed ad marker view add ${child.javaClass.name}")
                            scheduleGameAdSurfaceSweep(child, "feed ad marker view add ${child.javaClass.name}")
                        }
                        ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedReelCtaAdMarkerView(child) -> {
                            hideLikelyFeedReelCtaAdContainer(child, "feed reel CTA view add ${child.javaClass.name}")
                            scheduleGameAdSurfaceSweep(child, "feed reel CTA view add ${child.javaClass.name}")
                        }
                        shouldScheduleFeedRowSweep(parent, child) -> {
                            scheduleFeedRowSweep(child, "feed row add ${child.javaClass.name}")
                        }
                        child is WebView -> injectGameAdHidingScript(child)
                    }
                }
            }
        }

    (TextView::class.java.declaredMethods + TextView::class.java.methods)
        .filter { m -> m.name == "setText" && m.parameterTypes.isNotEmpty() && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0]) }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            m.hookMethod {
                after { param ->
                    val tv = param.thisObject as? TextView ?: return@after
                    if (isExplicitFeedAdMarkerText(tv.text)) {
                        hideLikelyAdContainer(tv, "explicit feed ad text")
                        return@after
                    }
                    if (!ENABLE_FEED_UI_MARKER_FALLBACKS) return@after
                    if (isAnyAdMarkerText(tv.text)) {
                        hideLikelyAdContainer(tv, "ad marker text")
                    } else if (isFeedReelCtaAdMarkerText(tv.text)) {
                        hideLikelyFeedReelCtaAdContainer(tv, "feed reel CTA text")
                    }
                }
            }
        }

    (View::class.java.declaredMethods + View::class.java.methods)
        .filter { m -> m.name == "setContentDescription" && m.parameterTypes.size == 1 && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0]) }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            m.hookMethod {
                after { param ->
                    val v = param.thisObject as? View ?: return@after
                    if (isExplicitFeedAdMarkerText(v.contentDescription)) {
                        hideLikelyAdContainer(v, "explicit feed ad content description")
                        return@after
                    }
                    if (!ENABLE_FEED_UI_MARKER_FALLBACKS) return@after
                    if (isFeedAdMarkerText(v.contentDescription)) {
                        hideLikelyAdContainer(v, "feed ad content description")
                    } else if (isFeedReelCtaAdMarkerText(v.contentDescription)) {
                        hideLikelyFeedReelCtaAdContainer(v, "feed reel CTA content description")
                    }
                }
            }
        }

    (WebView::class.java.declaredMethods + WebView::class.java.methods)
        .filter { m -> m.name in setOf("loadUrl","loadData","loadDataWithBaseURL","onAttachedToWindow") }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            m.hookMethod {
                after { param ->
                    val wv = param.thisObject as? WebView ?: return@after
                    injectGameAdHidingScript(wv)
                    scheduleGameAdSurfaceSweep(wv, "webview ${m.name}")
                }
            }
        }

}

/**
 * Hook Audience Network reward classes to fire completion callbacks.
 *
 * Differs from upstream (battery): upstream hooks ClassLoader.loadClass FOREVER, so its
 * afterHookedMethod runs on EVERY class load for the lifetime of the app. The reward classes
 * are loaded once (when a reward ad appears) and then cached, so watching loadClass forever
 * is waste. Here:
 *   1. The six reward classes are loaded and hooked directly first (they usually already sit
 *      in the com.facebook.ads dex). If both CORE classes (RewardedVideoAd /
 *      RewardedInterstitialAd — where show() lives) are hooked, no loadClass hook is installed.
 *   2. Only when one is missing (a reward class in a split that is not loaded yet) is the
 *      loadClass hook installed — and it REMOVES itself as soon as both core classes are
 *      hooked (the first time the user actually opens a reward ad). loadClass is back to zero
 *      overhead afterwards.
 * Functionally identical to upstream; the loadClass hook is just short-lived.
 */
private val AUDIENCE_NETWORK_CORE_REWARD_CLASSES = setOf(
    "com.facebook.ads.RewardedVideoAd",
    "com.facebook.ads.RewardedInterstitialAd"
)

fun hookAudienceNetworkRewardFallbacks(classLoader: ClassLoader) {
    if (!audienceNetworkRewardHooksInstalled.compareAndSet(0, 1)) return

    listOf(
        "com.facebook.ads.RewardedVideoAd",
        "com.facebook.ads.RewardedInterstitialAd",
        "com.facebook.ads.RewardedVideoAdListener",
        "com.facebook.ads.RewardedInterstitialAdListener",
        "com.facebook.ads.RewardedVideoAd\$RewardedVideoAdLoadConfigBuilder",
        "com.facebook.ads.RewardedInterstitialAd\$RewardedInterstitialAdLoadConfigBuilder"
    ).forEach { cn -> runCatching { tryHookAudienceNetworkRewardClass(classLoader.loadClass(cn)) } }

    // Both core classes hooked directly: no need to watch loadClass.
    if (audienceNetworkRewardClassesHooked.containsAll(AUDIENCE_NETWORK_CORE_REWARD_CLASSES)) return

    // A core class is missing: install a TEMPORARY loadClass hook that removes itself when done.
    val loadClassUnhooks = Collections.synchronizedList(ArrayList<XC_MethodHook.Unhook>())
    (ClassLoader::class.java.declaredMethods + ClassLoader::class.java.methods)
        .filter { m -> m.name == "loadClass" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            val unhook = XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clazz = param.result as? Class<*> ?: return
                    if (isAudienceNetworkRewardRelevantClass(clazz.name)) tryHookAudienceNetworkRewardClass(clazz)
                    // Both core classes hooked: remove every loadClass hook.
                    if (audienceNetworkRewardClassesHooked.containsAll(AUDIENCE_NETWORK_CORE_REWARD_CLASSES)) {
                        synchronized(loadClassUnhooks) {
                            loadClassUnhooks.forEach { u -> runCatching { u.unhook() } }
                            loadClassUnhooks.clear()
                        }
                    }
                }
            })
            loadClassUnhooks.add(unhook)
        }
}

private fun tryHookAudienceNetworkRewardClass(clazz: Class<*>) {
    val className = clazz.name
    if (!isAudienceNetworkRewardRelevantClass(className) || !audienceNetworkRewardClassesHooked.add(className)) return
    val methods = runCatching { clazz.declaredMethods + clazz.methods }.getOrDefault(emptyArray())
    methods.distinctBy { m -> m.name + m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } }
        .forEach { m ->
            runCatching {
                m.isAccessible = true
                if (isAudienceNetworkRewardShowMethod(clazz, m)) {
                    m.hookMethod {
                        before { param ->
                            val adObject = param.thisObject ?: return@before
                            if (!completeAudienceNetworkRewardObject(adObject, "show ${clazz.name}.${m.name}")) return@before
                            param.result = when (m.returnType) {
                                Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                                else -> null
                            }
                        }
                    }
                } else if (isAudienceNetworkRewardListenerRegistrationMethod(m)) {
                    m.hookMethod {
                        before { param -> rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m) }
                        after { param ->
                            rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m)
                            rememberAudienceNetworkRewardListeners(param.result, param.args, m)
                        }
                    }
                } else if (isAudienceNetworkRewardLoadMethod(clazz, m)) {
                    m.hookMethod {
                        before { param -> rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m) }
                    }
                }
            }
        }
}

private fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method) =
    clazz.name.lowercase().contains("reward") &&
    method.name.lowercase().contains("load") &&
    !method.isStatic &&
    method.parameterCount >= 1

// ─── Game ad payload helpers ──────────────────────────────────────────────────

fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false
    val promiseId = extractPromiseId(payload) ?: return false
    val resolveMethod = resolveGameAdResolveMethod(target.javaClass) ?: return false
    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching { resolveMethod.invoke(target, promiseId, successPayload); true }.getOrElse { false }
}

fun rejectGameAdPayload(
    target: Any?, payload: Any?,
    message: String = GAME_AD_REJECTION_MESSAGE,
    code: String = GAME_AD_REJECTION_CODE
): Boolean {
    if (target == null || payload == null) return false
    resolveGameAdBridgeRejectMethod(target.javaClass)?.let { m ->
        if (runCatching { m.invoke(target, message, code, payload); true }.getOrElse { false }) return true
    }
    val promiseId = extractPromiseId(payload) ?: return false
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass) ?: return false
    return runCatching { rejectMethod.invoke(target, promiseId, message, code); true }.getOrElse { false }
}

private fun rejectUnavailableGameAdPayloadIfNeeded(target: Any?, payload: Any?, messageType: String?, source: String = "unknown"): Boolean {
    if (!shouldMakeGameAdUnavailable(payload, messageType)) return false
    if (!rejectGameAdPayload(target, payload, GAME_AD_UNAVAILABLE_MESSAGE, GAME_AD_UNAVAILABLE_CODE)) {
        return false
    }
    lastUnavailableGameAdMs.set(System.currentTimeMillis())
    return true
}

private fun shouldMakeGameAdUnavailable(payload: Any?, messageType: String?): Boolean {
    if (messageType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true
    if (messageType !in setOf("loadadasync", "showadasync")) return false
    val content = extractGameAdContent(payload)
    val adInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val knownType = adInstanceId?.let { gameAdInstanceTypes[it] }
    if (knownType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true
    val placementText = listOf(
        content?.optString("placementID").orEmpty(),
        content?.optString("adType").orEmpty(),
        content?.optString("type").orEmpty(),
        content?.optString("format").orEmpty()
    ).joinToString(" ").lowercase()
    if (placementText.contains("reward")) return true
    return payload?.toString()?.lowercase()?.contains("rewarded") == true
}

fun shouldAutofixGameAdMessage(messageType: String?) = messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES

private fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
    return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES ||
        (className in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS) &&
         isRecentUnavailableGameAd())
}

private fun isRecentUnavailableGameAd(): Boolean {
    val rejectedAt = lastUnavailableGameAdMs.get()
    return rejectedAt > 0 && System.currentTimeMillis() - rejectedAt < GAME_AD_RECENT_WINDOW_MS
}

private fun isRecentGameAdActivityClose(): Boolean {
    val closedAt = lastGameAdActivityCloseMs.get()
    return closedAt > 0 && System.currentTimeMillis() - closedAt < 15_000L
}

private fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
    val snapshot = gameAdPromiseSnapshots[promiseId]
    if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true
    val normalized = reason.lowercase()
    if (!isRecentGameAdActivityClose()) return false
    return normalized.contains("banner")
}

fun rememberGameAdPayload(target: Any?, payload: Any?, messageType: String?) {
    if (target == null || payload !is JSONObject || messageType !in GAME_AD_MESSAGE_TYPES) return
    val now = System.currentTimeMillis()
    recentGameAdTargets[target] = now
    val snapshotPayload = runCatching { JSONObject(payload.toString()) }.getOrNull() ?: payload
    extractGameAdContent(snapshotPayload)?.optString("adInstanceID")?.takeIf { it.isNotBlank() }?.let { id ->
        messageType?.let { gameAdInstanceTypes[id] = it }
    }
    extractPromiseId(snapshotPayload)?.let { promiseId ->
        gameAdPromiseSnapshots.entries.removeIf { now - it.value.timestampMs > GAME_AD_PROMISE_WINDOW_MS }
        gameAdPromiseSnapshots[promiseId] = GameAdPromiseSnapshot(snapshotPayload, messageType, now)
    }
    synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.add(GameAdPayloadSnapshot(target, snapshotPayload, messageType, now))
        while (recentGameAdPayloads.size > 20) recentGameAdPayloads.removeAt(0)
    }
}

fun completeRecentGameAdRequests(source: String) {
    val now = System.currentTimeMillis()
    val snapshots = synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.toList()
    }
    var resolved = 0
    snapshots.asReversed().forEach { s ->
        if (shouldAutofixGameAdMessage(s.messageType) && resolveGameAdPayload(s.target, s.payload, s.messageType)) {
            dispatchPostResolveGameAdSignals(s.target, s.payload, s.messageType); resolved++
        }
    }
    val targets = synchronized(recentGameAdTargets) {
        recentGameAdTargets.entries.removeIf { now - it.value > GAME_AD_RECENT_WINDOW_MS }; recentGameAdTargets.keys.toList()
    }
    targets.forEach { t -> dispatchGameEvent(t, "hidebannerad", JSONObject().put("completed", true)) }
}

private fun dispatchPostResolveGameAdSignals(target: Any?, payload: Any?, messageType: String?) {
    if (messageType in setOf("loadbanneradasync", "hidebanneradasync")) {
        val content = buildGameAdSuccessPayload(payload, messageType)
        dispatchGameEvent(target, "hidebannerad", content)
    }
}

fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveMessageType = messageType ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = extractGameAdContent(payload)
    val result = JSONObject()
    val placementId    = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedInstId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }
    result.put("success", true)
    if (effectiveMessageType?.contains("reward", ignoreCase = true) == true) {
        result.put("completed", true).put("didComplete", true).put("watched", true)
              .put("rewarded", true).put("completionGesture", "post")
    }
    if (placementId != null)    result.put("placementID", placementId)
    if (bannerPosition != null) result.put("bannerPosition", bannerPosition)
    val adInstanceId = when {
        requestedInstId != null -> { gameAdInstanceIds.putIfAbsent(requestedInstId, requestedInstId); requestedInstId }
        placementId != null && effectiveMessageType != "loadbanneradasync" ->
            resolveGameAdInstanceId(placementId, effectiveMessageType, bannerPosition)
        else -> null
    }
    if (adInstanceId != null) {
        result.put("adInstanceID", adInstanceId)
        effectiveMessageType.takeIf { it.isNotBlank() }?.let { type ->
            gameAdInstanceTypes.putIfAbsent(adInstanceId, type)
        }
    }
    return result
}

private fun forceGameAdSuccessResult(promiseId: String, original: Any?, payload: JSONObject?, messageType: String?): JSONObject {
    val result = (original as? JSONObject)?.let { copyJsonObject(it) } ?: JSONObject()
    val success = buildGameAdSuccessPayload(payload ?: JSONObject().put("content", JSONObject().put("promiseID", promiseId)), messageType)
    val keys = success.keys(); while (keys.hasNext()) { val k = keys.next(); result.put(k, success.opt(k)) }
    result.put("success", true)
    if (messageType?.contains("reward", ignoreCase = true) == true)
        result.put("completed", true).put("didComplete", true).put("watched", true).put("rewarded", true).put("completionGesture", "post")
    return result
}

private fun inferGameAdMessageType(method: Method, payload: Any?): String? {
    val payloadType = (payload as? JSONObject)?.optString("type")?.takeIf { it.isNotBlank() }
    if (payloadType != null) return payloadType
    return when (method.name) {
        "D3s" -> "getinterstitialadasync"; "D3x" -> "getrewardedinterstitialasync"
        "D3z" -> "getrewardedvideoasync"; "D55" -> "hidebanneradasync"
        "D9v" -> "loadadasync"; "D9x" -> "loadbanneradasync"; "DX0" -> "showadasync"
        else -> null
    }
}

private fun gameAdPromiseTypeFromReason(reason: String): String? {
    val n = reason.lowercase()
    return when {
        n.contains("reward") && n.contains("interstitial") -> "getrewardedinterstitialasync"
        n.contains("reward") -> "getrewardedvideoasync"
        n.contains("interstitial") -> "getinterstitialadasync"
        n.contains("banner") -> "loadbanneradasync"
        n.contains("show") || n.contains("watch") || n.contains("complete") -> "showadasync"
        n.contains("load") -> "loadadasync"
        else -> null
    }
}

// ─── Activity helpers ─────────────────────────────────────────────────────────

private fun handleGameAdActivity(activity: Activity, source: String) {
    when (activity.javaClass.name) {
        AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS -> {
            forceAudienceNetworkRewardCompletion(activity, source)
            finishGameAdActivity(activity, source)
        }
        else -> finishGameAdActivity(activity, source)
    }
}

private fun buildGameAdActivityResultIntent(): Intent =
    Intent().apply { putExtra("success", true) }

private fun finishGameAdActivity(activity: Activity, source: String) {
    if (activity.isFinishing) return
    lastGameAdActivityCloseMs.set(System.currentTimeMillis())
    completeRecentGameAdRequests(source)
    if (activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES) {
        activity.setResult(Activity.RESULT_OK, buildGameAdActivityResultIntent())
    } else {
        activity.setResult(Activity.RESULT_CANCELED, Intent())
    }
    activity.finish()
}

private fun forceAudienceNetworkRewardCompletion(activity: Activity, source: String) {
    if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
    val seen = IdentityHashMap<Any, Boolean>()
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(activity to 0)
    var inspected = 0; var invoked = 0
    while (!queue.isEmpty() && inspected < 96) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue; inspected++
        invoked += invokeAudienceNetworkRewardCompletionMethods(value)
        if (depth >= 5 || !shouldTraverseAudienceNetworkObject(value, value === activity)) continue
        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            when (fieldValue) {
                is Iterable<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                }
                is Array<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                }
                else -> if (shouldQueueAudienceNetworkObject(fieldValue)) {
                    queue.add(fieldValue to depth + 1)
                }
            }
        }
    }
}

private fun invokeAudienceNetworkRewardCompletionMethods(target: Any): Int {
    var invoked = 0
    audienceNetworkMethodsFor(target.javaClass).filter { m ->
        !m.isStatic && m.parameterCount == 0 &&
        (m.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES || (m.name.contains("Reward", ignoreCase = true) && m.name.contains("Complete", ignoreCase = true)))
    }.forEach { m -> runCatching { m.invoke(target); invoked++ } }
    return invoked
}

private fun completeAudienceNetworkRewardObject(adObject: Any, source: String = "unknown"): Boolean {
    val listeners = LinkedHashSet<Any>()
    synchronized(audienceNetworkRewardAdListeners) { audienceNetworkRewardAdListeners[adObject]?.let { listeners.add(it) } }
    listeners.addAll(findAudienceNetworkRewardListeners(adObject))
    var invoked = 0
    listeners.forEach { listener -> invoked += invokeAudienceNetworkRewardListenerCallbacks(listener, adObject, source) }
    if (invoked > 0) { completeRecentGameAdRequests(source); return true }
    return false
}

private fun invokeAudienceNetworkRewardListenerCallbacks(listener: Any, adObject: Any, source: String): Int {
    var invoked = 0
    val methodGroups = listOf(
        setOf("onAdLoaded", "onLoggingImpression", "onInterstitialDisplayed"),
        setOf("onRewardedVideoCompleted", "onRewardedAdCompleted", "onRewardedInterstitialCompleted", "onAdComplete", "onAdCompleted"),
        setOf("onRewardedVideoClosed", "onRewardedInterstitialClosed", "onAdClosed", "onInterstitialDismissed")
    )
    methodGroups.forEach { group ->
        audienceNetworkRewardMethodsFor(listener.javaClass)
            .filter { m -> m.name in group }
            .forEach { m ->
                val args = audienceNetworkCallbackArgs(m, adObject) ?: return@forEach
                runCatching { m.invoke(listener, *args); invoked++ }
            }
    }
    return invoked
}

private fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? =
    when (method.parameterCount) {
        0 -> emptyArray()
        1 -> { val pt = method.parameterTypes[0]; if (pt.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null }
        else -> null
    }

/** Like audienceNetworkMethodsFor but also includes interface-declared methods — needed for listener callbacks. */
private fun audienceNetworkRewardMethodsFor(type: Class<*>): List<Method> {
    val map = LinkedHashMap<String, Method>()
    var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java) {
        (cur.declaredMethods + cur.methods).forEach { m ->
            if (!m.isStatic) {
                m.isAccessible = true
                map.putIfAbsent("${m.name}/${m.parameterTypes.joinToString { it.name }}", m)
            }
        }
        cur = cur.superclass
    }
    return map.values.toList()
}

private fun findAudienceNetworkRewardListeners(root: Any?): List<Any> {
    if (root == null) return emptyList()
    val listeners = LinkedHashSet<Any>(); val seen = IdentityHashMap<Any, Boolean>()
    val queue = ArrayDeque<Pair<Any, Int>>(); queue.add(root to 0)
    var inspected = 0
    while (!queue.isEmpty() && inspected < 96 && listeners.size < 8) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue; inspected++
        if (value !== root && isAudienceNetworkRewardListenerObject(value)) { listeners.add(value); continue }
        if (depth >= 5 || !shouldQueueAudienceNetworkObject(value)) continue
        audienceNetworkFieldsFor(value.javaClass).forEach { f ->
            val fv = runCatching { f.get(value) }.getOrNull() ?: return@forEach
            when (fv) {
                is Iterable<*> -> fv.take(12).forEach { item -> if (item != null && (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))) queue.add(item to depth + 1) }
                is Array<*>    -> fv.take(12).forEach { item -> if (item != null && (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))) queue.add(item to depth + 1) }
                else -> if (isAudienceNetworkRewardListenerObject(fv) || shouldQueueAudienceNetworkObject(fv)) queue.add(fv to depth + 1)
            }
        }
    }
    return listeners.toList()
}

private fun rememberAudienceNetworkRewardListeners(owner: Any?, args: Array<Any?>?, method: Method) {
    if (owner == null || args == null) return
    args.forEach { arg ->
        if (arg != null && isAudienceNetworkRewardListenerObject(arg)) {
            audienceNetworkRewardAdListeners[owner] = arg
        } else {
            findAudienceNetworkRewardListeners(arg).firstOrNull()?.let { audienceNetworkRewardAdListeners[owner] = it }
        }
    }
}

private fun isAudienceNetworkRewardListenerObject(value: Any?): Boolean {
    if (value == null) return false
    val type = value.javaClass
    val cn = type.name.lowercase()
    if (cn.contains("listener") && (cn.contains("reward") || cn.contains("ad"))) return true
    if (audienceNetworkInterfacesFor(type).any { iface ->
            val ifn = iface.name.lowercase()
            ifn.contains("listener") && (ifn.contains("reward") || ifn.contains("ad"))
        }) return true
    return audienceNetworkRewardMethodsFor(type).any { m ->
        m.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
        m.name.contains("Reward", ignoreCase = true) ||
        m.name.contains("InterstitialDismissed", ignoreCase = true)
    }
}

private fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
    val interfaces = LinkedHashSet<Class<*>>()
    fun collect(current: Class<*>?) {
        if (current == null || current == Any::class.java) return
        current.interfaces.forEach { iface -> if (interfaces.add(iface)) collect(iface) }
        collect(current.superclass)
    }
    collect(type)
    return interfaces.toList()
}

private fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
    val n = className.lowercase()
    return (n.startsWith("com.facebook.ads.") || n.startsWith("com.facebook.audiencenetwork.") || n.contains("audiencenetwork")) &&
           (n.contains("reward") || n.contains("adlistener") || n.contains("adconfig") || n.endsWith(".ad"))
}

private fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method) =
    clazz.name.lowercase().contains("reward") &&
    method.name == "show" &&
    !method.isStatic &&
    method.parameterCount <= 1 &&
    (method.returnType == Void.TYPE ||
     method.returnType == Boolean::class.javaPrimitiveType ||
     method.returnType == Boolean::class.java)

private fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
    if (method.isStatic || method.parameterCount == 0) return false
    if (method.name.lowercase().contains("listener")) return true
    return method.parameterTypes.any { t -> t.name.lowercase().contains("listener") && (t.name.lowercase().contains("reward") || t.name.lowercase().contains("ad")) }
}

private fun shouldQueueAudienceNetworkObject(value: Any): Boolean {
    val type = value.javaClass
    if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) return false
    return shouldTraverseAudienceNetworkObject(value, false)
}

private fun shouldTraverseAudienceNetworkObject(value: Any, isRootActivity: Boolean): Boolean {
    if (isRootActivity) return true
    val cn = value.javaClass.name.lowercase()
    return cn.startsWith("com.facebook.ads.") || cn.startsWith("com.facebook.audiencenetwork.") ||
           cn.contains("audiencenetwork") || cn.contains("reward") || cn.contains("interstitial") ||
           cn.contains("fullscreen") || cn.contains("listener") || cn.contains(".ads.")
}

private fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
    val list = ArrayList<Field>(); var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java && list.size < 48) {
        cur.declaredFields.forEach { f -> if (!f.isStatic && list.size < 48) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
    }; return list
}

private fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
    val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java) {
        cur.declaredMethods.forEach { m -> if (!m.isStatic) { m.isAccessible = true; map.putIfAbsent("${cur.name}.${m.name}/${m.parameterCount}", m) } }; cur = cur.superclass
    }; return map.values.toList()
}

// ─── Native ad view helpers ───────────────────────────────────────────────────

private val GAME_AD_WEBVIEW_HIDE_SCRIPT = """
(function(){
  if (window.__nexalloyFbAdSweep) return;
  window.__nexalloyFbAdSweep = true;
  function textOf(el) {
    try { return (el.innerText || el.textContent || '').toLowerCase(); } catch (e) { return ''; }
  }
  function attrsOf(el) {
    try { return ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('src') || '')).toLowerCase(); } catch (e) { return ''; }
  }
  function nearBottom(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.height > 0 && r.height < Math.max(260, window.innerHeight * 0.35) && r.bottom > window.innerHeight * 0.55;
    } catch (e) { return false; }
  }
  function isAd(el) {
    var t = textOf(el);
    var a = attrsOf(el);
    if (t.indexOf('ads served by meta') >= 0 || t.indexOf('ad choices') >= 0) return true;
    if (!nearBottom(el)) return false;
    if ((el.tagName || '').toLowerCase() === 'iframe') return true;
    return /audiencenetwork|adchoices|fbinstant.*ad|instant.*ad|banner.?ad|ad.?banner|ad-container|ad_container|sponsored/.test(a);
  }
  function hide(el) {
    try {
      var target = el;
      for (var i = 0; i < 4 && target.parentElement && nearBottom(target.parentElement); i++) target = target.parentElement;
      target.style.setProperty('display', 'none', 'important');
      target.style.setProperty('visibility', 'hidden', 'important');
      target.style.setProperty('height', '0px', 'important');
      target.style.setProperty('min-height', '0px', 'important');
      target.style.setProperty('pointer-events', 'none', 'important');
    } catch (e) {}
  }
  function sweep() {
    try {
      document.querySelectorAll('iframe, div, section, aside, [id], [class], [aria-label]').forEach(function(el) {
        if (isAd(el)) hide(el);
      });
    } catch (e) {}
  }
  sweep();
  new MutationObserver(sweep).observe(document.documentElement || document.body, {childList:true, subtree:true, attributes:true});
  setInterval(sweep, 1000);
})();
""".trimIndent()

private fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
    val root = view?.rootView ?: view ?: return
    longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
        root.postDelayed({ sweepGameAdSurface(root, reason) }, delayMs)
    }
}

/** Feed rows often lazy-render their "Sponsored"/"Hide ad" markers after initial layout —
 *  schedule a short delayed sweep whenever a row is added directly under a RecyclerView. */
private fun shouldScheduleFeedRowSweep(parent: ViewGroup?, child: View?): Boolean {
    if (parent == null || child !is ViewGroup) return false
    return parent.javaClass.name.contains("RecyclerView")
}

private fun scheduleFeedRowSweep(view: View?, reason: String) {
    val subtree = view ?: return
    longArrayOf(60L, 500L, 1_500L, 3_000L).forEach { delayMs ->
        subtree.postDelayed({ sweepGameAdSurface(subtree, reason) }, delayMs)
    }
}

private fun sweepGameAdSurface(view: View?, reason: String): Boolean {
    if (view == null) return false
    var hidden = false
    if (view is WebView) injectGameAdHidingScript(view)
    if (isLikelyExplicitFeedAdCardContainer(view)) {
        hidden = hideLikelyExplicitFeedAdCardContainer(view, reason) || hidden
    }
    if (isPotentialNativeGameAdView(view) || isPotentialExplicitFeedAdMarkerView(view) ||
        (ENABLE_FEED_UI_MARKER_FALLBACKS &&
         (isPotentialFeedAdMarkerView(view) || (view is TextView && isAnyAdMarkerText(view.text))))) {
        hidden = hideLikelyAdContainer(view, reason) || hidden
    }
    if (ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedReelCtaAdMarkerView(view)) {
        hidden = hideLikelyFeedReelCtaAdContainer(view, reason) || hidden
    }
    val group = view as? ViewGroup ?: return hidden
    for (i in 0 until group.childCount) {
        hidden = sweepGameAdSurface(group.getChildAt(i), reason) || hidden
    }
    return hidden
}

private fun injectGameAdHidingScript(webView: WebView) {
    webView.post { runCatching { webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null) } }

}

private fun hideLikelyAdContainer(view: View, reason: String): Boolean {
    val root = view.rootView
    val target = when {
        shouldUseExplicitFeedMarkerCardTarget(view) -> {
            resolveLikelyExplicitFeedAdCardTarget(view) ?: run {
                return false
            }
        }
        shouldUseFeedMarkerCardTarget(view) -> {
            resolveLikelyFeedMarkerCardTarget(view) ?: run {
                return false
            }
        }
        else -> resolveLikelyAdContainerTarget(view)
    }
    return hideResolvedAdSurfaceTarget(target, view, root, reason, forceCollapseHeight = false)
}

private fun hideLikelyExplicitFeedAdCardContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyExplicitFeedAdCardTarget(view) ?: return false
    return hideResolvedAdSurfaceTarget(target, view, view.rootView, "$reason explicit feed card", forceCollapseHeight = true)
}

private fun hideResolvedAdSurfaceTarget(
    target: View, source: View, root: View?, reason: String, forceCollapseHeight: Boolean
): Boolean {
    var hidden = false
    if (target.visibility != View.GONE) { target.visibility = View.GONE; hidden = true }
    target.minimumHeight = 0
    target.layoutParams?.let { params ->
        if (forceCollapseHeight || target !== source ||
            isLikelyBannerSized(target, root) || isPotentialNativeGameAdView(target) ||
            isPotentialFeedAdMarkerView(source) || isPotentialExplicitFeedAdMarkerView(source)) {
            params.height = 0; target.layoutParams = params; hidden = true
        }
    }
    target.requestLayout()
    return hidden
}

private fun hideLikelyFeedReelCtaAdContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyFeedReelCtaAdContainerTarget(view) ?: return false
    var hidden = false
    if (target.visibility != View.GONE) { target.visibility = View.GONE; hidden = true }
    target.minimumHeight = 0
    target.layoutParams?.let { params -> params.height = 0; target.layoutParams = params; hidden = true }
    target.requestLayout()
    return hidden
}

/** Walks up from [view] while the parent still looks like a single post container
 *  (≥82% root width, height bounded relative to root and to the child) — used by
 *  the native-ad / game-ad fallback path where we don't have explicit text markers. */
private fun resolveLikelyAdContainerTarget(view: View): View {
    val root = view.rootView ?: return view
    var current = view
    var selected = view
    val rootWidth  = root.width.takeIf { it > 0 } ?: 0
    val rootHeight = root.height.takeIf { it > 0 } ?: 0
    while (true) {
        val parentView = current.parent as? View ?: break
        if (parentView.javaClass.name.contains("RecyclerView")) break
        val parentWidth  = parentView.width
        val parentHeight = parentView.height
        val looksLikePostContainer = rootWidth > 0 && rootHeight > 0 &&
            parentWidth >= (rootWidth * 0.82f).toInt() &&
            parentHeight > 0 && parentHeight < (rootHeight * 0.72f).toInt()
        if (!looksLikePostContainer) break
        val currentHeight = current.height.takeIf { it > 0 } ?: parentHeight
        if (currentHeight > 0 && parentHeight > maxOf((currentHeight * 1.25f).toInt(), currentHeight + 180)) break
        selected = parentView; current = parentView
    }
    return selected
}

private fun shouldUseFeedMarkerCardTarget(view: View): Boolean =
    isPotentialFeedAdMarkerView(view) || (view is TextView && isFeedAdMarkerText(view.text))

private fun shouldUseExplicitFeedMarkerCardTarget(view: View): Boolean =
    isPotentialExplicitFeedAdMarkerView(view) || (view is TextView && isExplicitFeedAdMarkerText(view.text))

private data class ExplicitFeedAdCardSignals(
    val hasHideAd: Boolean, val hasAdLabel: Boolean, val hasSharedLink: Boolean, val hasStrongCta: Boolean
)

/** Walks UP from a marker view to find the full post card that should be collapsed —
 *  picks the largest matching ancestor rather than the first one found. */
private fun resolveLikelyExplicitFeedAdCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth  = root.width.takeIf  { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null
    var current: View? = view
    var best: View? = null
    var bestHeight = -1
    while (current != null) {
        if (isLikelyExplicitFeedAdCardContainer(current, rootWidth, rootHeight)) {
            val h = current.height
            if (h > bestHeight) { best = current; bestHeight = h }
        }
        current = current.parent as? View ?: break
    }
    return best
}

private fun resolveLikelyFeedMarkerCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth  = root.width.takeIf  { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null
    var current: View? = view
    var best: View? = null
    var bestHeight = -1
    while (current != null) {
        if (isSafeFeedMarkerCardCandidate(current, rootWidth, rootHeight)) {
            val h = current.height
            if (h > bestHeight) { best = current; bestHeight = h }
        }
        current = current.parent as? View ?: break
    }
    return best
}

/** Bounding-box safety check to avoid the broad feed-marker fallback collapsing
 *  something that isn't a full post card (e.g. a toolbar or a comment row). */
private fun isSafeFeedMarkerCardCandidate(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width; val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(360, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.82f).toInt()) return false
    val location = IntArray(2)
    val topOnScreen = runCatching { view.getLocationOnScreen(location); location[1] }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height
    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.96f).toInt()) return false
    return true
}

private fun isLikelyExplicitFeedAdCardContainer(view: View): Boolean {
    val root = view.rootView ?: return false
    val rootWidth  = root.width.takeIf  { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    return isLikelyExplicitFeedAdCardContainer(view, rootWidth, rootHeight)
}

private fun isLikelyExplicitFeedAdCardContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    if (view !is ViewGroup) return false
    val width = view.width; val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(420, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.96f).toInt()) return false
    val location = IntArray(2)
    val topOnScreen = runCatching { view.getLocationOnScreen(location); location[1] }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height
    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.98f).toInt()) return false
    val signals = collectExplicitFeedAdCardSignals(view)
    return signals.hasHideAd && (signals.hasAdLabel || signals.hasSharedLink || signals.hasStrongCta)
}

/** Requires "Hide ad" PLUS at least one of: AdChoices label, "Shared link:", or a
 *  strong CTA phrase ("Learn More", "Shop Now"...) — this combination is what makes
 *  the explicit detector safe to leave always-on (low false positive rate). */
private fun collectExplicitFeedAdCardSignals(root: View): ExplicitFeedAdCardSignals {
    val queue = ArrayDeque<View>(); queue.add(root)
    var visited = 0
    var hasHideAd = false; var hasAdLabel = false; var hasSharedLink = false; var hasStrongCta = false
    while (queue.isNotEmpty() && visited < 192 && !(hasHideAd && (hasAdLabel || hasSharedLink || hasStrongCta))) {
        val view = queue.removeFirst(); visited++
        for (marker in collectViewMarkerTexts(view)) {
            val normalized = marker.lowercase()
            if (!hasHideAd && normalized.contains("hide ad")) hasHideAd = true
            if (!hasAdLabel && isExplicitFeedAdMarkerText(normalized)) hasAdLabel = true
            if (!hasSharedLink && normalized.contains("shared link:")) hasSharedLink = true
            if (!hasStrongCta && isExplicitFeedAdCtaText(normalized)) hasStrongCta = true
        }
        val group = view as? ViewGroup ?: continue
        for (i in 0 until group.childCount) queue.addLast(group.getChildAt(i))
    }
    return ExplicitFeedAdCardSignals(hasHideAd, hasAdLabel, hasSharedLink, hasStrongCta)
}

private fun resolveLikelyFeedReelCtaAdContainerTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth  = root.width.takeIf  { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null
    var current: View? = view
    while (current != null) {
        if (isLikelyFeedReelCtaAdContainer(current, rootWidth, rootHeight)) return current
        current = current.parent as? View ?: break
    }
    return null
}

private data class FeedReelCtaAdSignals(
    val hasSharedLink: Boolean, val hasSendMessageCta: Boolean,
    val hasReelSurface: Boolean, val hasLeadGenPrompt: Boolean
)

private fun isLikelyFeedReelCtaAdContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width; val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < (rootHeight * 0.45f).toInt() || height > (rootHeight * 0.92f).toInt()) return false
    val location = IntArray(2)
    val topOnScreen = runCatching { view.getLocationOnScreen(location); location[1] }.getOrDefault(view.top)
    if (topOnScreen < (rootHeight * 0.08f).toInt()) return false
    val signals = collectFeedReelCtaAdSignals(view)
    return signals.hasSharedLink && signals.hasSendMessageCta && (signals.hasReelSurface || signals.hasLeadGenPrompt)
}

private fun collectFeedReelCtaAdSignals(root: View): FeedReelCtaAdSignals {
    val queue = ArrayDeque<View>(); queue.add(root)
    var visited = 0
    var hasSharedLink = false; var hasSendMessageCta = false; var hasReelSurface = false; var hasLeadGenPrompt = false
    while (queue.isNotEmpty() && visited < 128 &&
           !(hasSharedLink && hasSendMessageCta && (hasReelSurface || hasLeadGenPrompt))) {
        val view = queue.removeFirst(); visited++
        val className = view.javaClass.name.lowercase()
        val contentDescription = view.contentDescription?.toString().orEmpty().lowercase()
        val text = (view as? TextView)?.text?.toString().orEmpty().lowercase()
        val marker = "$className $contentDescription $text"
        if (!hasSharedLink && marker.contains("shared link:")) hasSharedLink = true
        if (!hasSendMessageCta && marker.contains("send message")) hasSendMessageCta = true
        if (!hasLeadGenPrompt && (marker.contains("your business") || marker.contains("your ad"))) hasLeadGenPrompt = true
        if (!hasReelSurface && (marker.contains("reel") || className.contains("surfaceview") ||
                                 className.contains("textureview") || className.contains("videoview"))) hasReelSurface = true
        val group = view as? ViewGroup ?: continue
        for (i in 0 until group.childCount) queue.addLast(group.getChildAt(i))
    }
    return FeedReelCtaAdSignals(hasSharedLink, hasSendMessageCta, hasReelSurface, hasLeadGenPrompt)
}

/** Collects visible text + accessibility text/content-description — Meta's ad labels
 *  are sometimes accessibility-only (invisible on screen but read by screen readers
 *  and inspectable here), which is why both sources are checked. */
private fun collectViewMarkerTexts(view: View?): List<String> {
    if (view == null) return emptyList()
    val values = LinkedHashSet<String>()
    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    runCatching {
        val info = view.createAccessibilityNodeInfo() ?: return@runCatching
        try {
            info.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            info.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
        } finally { info.recycle() }
    }
    return values.toList()
}

private fun isPotentialFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedAdMarkerText)
}

private fun isPotentialExplicitFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isExplicitFeedAdMarkerText)
}

private fun isPotentialFeedReelCtaAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedReelCtaAdMarkerText)
}

private fun isAnyAdMarkerText(value: CharSequence?): Boolean =
    isGameAdMarkerText(value) || isFeedAdMarkerText(value)

private fun isFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return FEED_SURFACE_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
}

private fun isExplicitFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_CARD_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
}

private fun isExplicitFeedAdCtaText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_AD_CTA_TOKENS.any { token -> normalized.contains(token) }
}

private fun isFeedReelCtaAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return FEED_REEL_CTA_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
}

private fun isLikelyBannerSized(view: View, root: View?): Boolean {
    val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
    val height = view.height
    if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[1] + height > rootHeight / 2
    }.getOrDefault(true)
}

private fun isPotentialNativeGameAdView(view: View?): Boolean {
    val cn = view?.javaClass?.name?.lowercase() ?: return false
    return cn == "com.facebook.ads.adview" || (cn.endsWith(".adview") && (cn.startsWith("com.facebook.ads.") || cn.contains("audiencenetwork"))) || cn.contains("adchoices")
}

private fun isGameAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val n = value.toString().lowercase()
    return n.contains("ads served by meta") || n.contains("ad choices") || n.contains("adchoices")
}

// ─── Resolve / reject helpers ─────────────────────────────────────────────────

private fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null
    val candidates = (type.declaredMethods + type.methods).filter { m ->
        !m.isStatic && m.returnType == Void.TYPE && m.parameterCount == 2 &&
        m.parameterTypes[0] == String::class.java && !m.parameterTypes[1].isPrimitive
    }
    return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
        ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
        ?: candidates.firstOrNull())?.apply { isAccessible = true }
}

private fun resolveGameAdBridgeRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !m.isStatic && m.returnType == Void.TYPE && m.parameterCount == 3 &&
        m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java && m.parameterTypes[2] == JSONObject::class.java
    }?.apply { isAccessible = true }
}

private fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !m.isStatic && m.returnType == Void.TYPE && m.parameterCount == 3 && m.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

private fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
    if (target == null) return false
    val method = resolveGameEventDispatchMethod(target.javaClass) ?: return false
    val eventValue = resolveGameEventValue(method.parameterTypes[0], eventType) ?: return false
    return runCatching { method.invoke(target, eventValue, content ?: JSONObject.NULL); true }.getOrElse { false }
}

private fun resolveGameEventDispatchMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !m.isStatic && m.returnType == Void.TYPE && m.parameterCount == 2 &&
        m.parameterTypes[0] != String::class.java && m.parameterTypes[1] == Any::class.java
    }?.apply { isAccessible = true }
}

private fun resolveGameEventValue(eventType: Class<*>, eventName: String): Any? {
    val valuesMethod = (eventType.declaredMethods + eventType.methods).firstOrNull { m ->
        m.isStatic && m.parameterCount == 0 && m.returnType.isArray && m.returnType.componentType == eventType
    }?.apply { isAccessible = true }
    val values = runCatching { valuesMethod?.invoke(null) as? Array<*> }.getOrNull().orEmpty()
    values.firstOrNull { it?.toString() == eventName }?.let { return it }
    return eventType.declaredFields.firstOrNull { f ->
        f.isStatic && f.type == eventType &&
        runCatching { f.isAccessible = true; f.get(null)?.toString() == eventName }.getOrDefault(false)
    }?.let { f -> runCatching { f.get(null) }.getOrNull() }
}

fun extractPromiseId(payload: Any?): String? {
    val jClass = payload?.javaClass ?: return null
    if (jClass.name != "org.json.JSONObject") return null
    val getJSONObject = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getJSONObject" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val getString = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getString" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val content = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

private fun extractGameAdContent(payload: Any?): JSONObject? = (payload as? JSONObject)?.optJSONObject("content")

private fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject =
    JSONObject().put("type", messageType).put("content", bundleToJsonObject(bundle))

private fun bundleToJsonObject(bundle: Bundle): JSONObject {
    val json = JSONObject()
    runCatching { bundle.keySet().toList() }.getOrDefault(emptyList()).forEach { key ->
        val value = runCatching { bundle.get(key) }.getOrNull()
        when (value) {
            null            -> json.put(key, JSONObject.NULL)
            is String       -> json.put(key, value)
            is Boolean      -> json.put(key, value)
            is Number       -> json.put(key, value)
            is JSONObject   -> json.put(key, value)
            is org.json.JSONArray -> json.put(key, value)
            is Bundle       -> json.put(key, bundleToJsonObject(value))
            else            -> json.put(key, value.toString())
        }
    }
    return json
}

private fun resolveGameAdInstanceId(placementId: String, messageType: String?, bannerPosition: String?): String {
    val key = listOf(messageType.orEmpty(), placementId, bannerPosition.orEmpty()).joinToString("|")
    return gameAdInstanceIds.computeIfAbsent(key) { "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_${key.hashCode().toLong() and 0xffffffffL}" }
}

private fun copyJsonObject(source: JSONObject): JSONObject {
    val result = JSONObject(); val keys = source.keys()
    while (keys.hasNext()) { val k = keys.next(); result.put(k, source.opt(k)) }; return result
}

// ─── List / result manipulation helpers ──────────────────────────────────────

fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0; val it = list.iterator()
    while (it.hasNext()) { if (inspector.containsAdStory(it.next())) { it.remove(); removed++ } }; return removed
}

fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val cl = Class.forName("com.google.common.collect.ImmutableList", false, sample.javaClass.classLoader)
        cl.getDeclaredMethod("copyOf", Iterable::class.java).invoke(null, items)
    }.getOrNull()
}

fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false; val rebuilt = rebuildFeedResult(result, items) ?: return false
    param.result = rebuilt; return true
}

private fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching { type.declaredFields.onEach { it.isAccessible = true } }.getOrNull() ?: return null
    val listField    = fields.firstOrNull { !it.isStatic && Iterable::class.java.isAssignableFrom(it.type) } ?: return null
    val intArrayField = fields.firstOrNull { !it.isStatic && it.type == IntArray::class.java } ?: return null
    val intFields    = fields.filter { !it.isStatic && it.type == Int::class.javaPrimitiveType }
    if (intFields.size < 3) return null
    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList  = buildImmutableListLike(originalList, items) ?: return null
    val stats        = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints         = intFields.map { f -> runCatching { f.getInt(result) }.getOrNull() ?: return null }
    val ctor = type.declaredConstructors.firstOrNull { c ->
        c.parameterCount == 5 && c.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
        c.parameterTypes.getOrNull(1) == IntArray::class.java && c.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null
    ctor.isAccessible = true
    return runCatching { ctor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2]) }.getOrNull()
}

fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result
    return runCatching {
        val f = result.javaClass.declaredFields.firstOrNull { Iterable::class.java.isAssignableFrom(it.type) } ?: return null
        f.isAccessible = true; f.get(result) as? Iterable<*>
    }.getOrNull()
}

// ─── Sponsored pool result type helpers ──────────────────────────────────────

fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" } ?: return null
    ctor.isAccessible = true; return runCatching { ctor.newInstance(null, emptyReason) }.getOrNull()
}

// ─── Litho render method detection ───────────────────────────────────────────

fun resolveLithoRenderMethod(componentClass: Class<*>): Method? =
    componentClass.declaredMethods.firstOrNull { m ->
        !m.isStatic && !m.isBridge && !m.isSynthetic && m.parameterCount == 1 &&
        !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != Any::class.java &&
        m.returnType.isAssignableFrom(componentClass)
    }?.apply { isAccessible = true }

// ─── Story ad provider resolution ────────────────────────────────────────────

// ─── Instream banner eligibility resolution ───────────────────────────────────
// Mirrors upstream resolveInstreamBannerEligibilityMethod: prefer a non-static
// boolean()/0-param method declared on (or inherited by) the resolved candidate
// class; if none exists there, walk up the superclass chain looking for one
// declared directly on an ancestor. Needs a real Class<*> (classLoader), so —
// like the list-builder append/factory methods above — this runs here rather
// than as a DexKit fingerprint.
fun resolveInstreamBannerEligibilityMethod(candidateClass: Class<*>): Method? {
    (candidateClass.declaredMethods + candidateClass.methods)
        .firstOrNull { m ->
            !m.isStatic &&
            m.returnType == Boolean::class.javaPrimitiveType &&
            m.parameterCount == 0
        }
        ?.apply { isAccessible = true }
        ?.let { return it }

    var current: Class<*>? = candidateClass.superclass
    while (current != null && current != Any::class.java) {
        current.declaredMethods.firstOrNull { m ->
            !m.isStatic &&
            m.returnType == Boolean::class.javaPrimitiveType &&
            m.parameterCount == 0
        }?.let { it.isAccessible = true; return it }
        current = current.superclass
    }
    return null
}

fun resolveStoryAdProviderHooks(
    providerClass: Class<*>,
    includeInsertionTrigger: Boolean,
    insertionTriggerMethod: Method? = null
): StoryAdProviderHooks {
    val methods = providerClass.declaredMethods + providerClass.methods
    // NOTE: upstream's DexKit matchers for all three methods below never filtered on
    // static vs. instance — only on shape (return type + param types) — so we don't
    // either, to avoid silently missing a method that happens to be static.
    val mergeMethod = methods.firstOrNull { m ->
        m.parameterCount == 3 &&
        m.returnType.name == "com.google.common.collect.ImmutableList" &&
        m.parameterTypes[0].name == "com.facebook.auth.usersession.FbUserSession" &&
        m.parameterTypes[2].name == "com.google.common.collect.ImmutableList"
    }?.apply { isAccessible = true }
    val fetchMoreAdsMethod = methods.firstOrNull { m ->
        m.parameterCount == 2 && m.returnType == Void.TYPE &&
        m.parameterTypes[0].name == "com.google.common.collect.ImmutableList" &&
        m.parameterTypes[1] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true }
    val deferredUpdateMethod = methods.firstOrNull { m ->
        m.parameterCount == 2 && m.returnType == Void.TYPE &&
        m.parameterTypes[1].name == "com.google.common.collect.ImmutableList"
    }?.apply { isAccessible = true }
    // insertionTriggerMethod is resolved via DexKit fingerprint (usingStrings("ads_insertion"))
    // and passed in directly — avoids picking wrong 0-param void method via broad reflection
    val resolvedInsertionTrigger = if (includeInsertionTrigger) insertionTriggerMethod else null
    return StoryAdProviderHooks(providerClass, mergeMethod, fetchMoreAdsMethod, deferredUpdateMethod, resolvedInsertionTrigger)
}

private fun isFeedListType(type: Class<*>): Boolean =
    Iterable::class.java.isAssignableFrom(type) ||
        type.name == "com.google.common.collect.ImmutableList"

// ─── Feed collection edge filter (DexKit-resolved) ───────────────────────────
// Replaces the former FB571 hardcoded X.1vr.addNewEdgeToCollection fast path. The
// method name survives ProGuard on every build seen so far, so it is resolved by
// name + parameter shape via feedCollectionAddEdgeMethodFingerprint instead of by
// obfuscated class name.
fun hookFeedCollectionAddEdge(method: Method, inspector: FeedItemInspector) {
    val edgeIndex = method.parameterTypes.indexOfFirst { it.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
        .let { if (it >= 0) it else 1 }
    if (!feedCollectionMethodsHooked.add(methodHookKey(method))) return
    method.hookMethod {
        before { param ->
            val edge = param.args.getOrNull(edgeIndex) ?: return@before
            if (!inspector.isExplicitlySponsoredFeedEdge(edge)) return@before
            param.result = false
        }
    }
}

// ─── Search results (SERP) ads ────────────────────────────────────────────────

/**
 * Search-result unit types that ARE an advertisement.
 *
 * These are constant names on Facebook's own search-result unit type enum, read straight
 * off the shipped dex — the enum carries roughly 570 constants and these are the ones the
 * app itself groups as ads. Membership is a positive identification: a unit of one of
 * these types is an ad, and a unit of any other type is not touched, so the failure mode
 * is a missed advertisement rather than a blanked results page.
 *
 * The app keeps a narrower set of its own (the four that its internal `isAd` predicate
 * tests). This one is a superset: Marketplace's two ad kinds, the shoppable variant, the
 * ads discovery header and its "see more" footer are ads by name and by behaviour, but
 * sit outside the app's own set because it uses that set for ad *ranking* rather than for
 * classification.
 */
val SEARCH_AD_UNIT_TYPE_NAMES = setOf(
    "SEARCH_ADS",
    "TOP_POSITION_SEARCH_ADS",
    "TOP_POSITION_SHOPPABLE_ADS",
    "DEPENDENT_SEARCH_ADS",
    "LATE_DEPENDENT_SEARCH_ADS",
    "MARKETPLACE_SEARCH_ADS",
    "MARKETPLACE_BOOSTED_LISTING_SEARCH_ADS",
    "SEARCH_ADS_DISCOVERY_HEADER",
    "SEARCH_ADS_FLOATING_SEE_MORE",
    "FACEBOOK_ADVERTISING",
)

private val searchAdMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

/**
 * Reads a search-result unit's declared type.
 *
 * There is no name guessing and no tree walking here, in deliberate contrast to
 * [FeedItemInspector]: the unit type enum class is resolved by fingerprint, so the field
 * holding it is simply "the one instance field whose type is that class". One field read
 * and one set lookup per unit, which matters because this runs for every result on the
 * page, on every page of results.
 */
class SearchResultUnitInspector(private val unitTypeEnumClass: Class<*>) {
    /**
     * Cached as a list rather than as a nullable Field so that a MISS is cached too — a
     * unit class with no type field would otherwise re-walk its whole hierarchy on every
     * result, forever. An empty list is the miss; a singleton list is the hit. The
     * alternative, a sentinel Field looked up by its own name, would not survive this
     * module's own minification.
     */
    private val typeFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun unitTypeName(unit: Any?): String? {
        if (unit == null) return null
        val field = typeFieldFor(unit.javaClass) ?: return null
        return runCatching { field.get(unit)?.toString() }.getOrNull()
    }

    fun isAdUnit(unit: Any?): Boolean = unitTypeName(unit) in SEARCH_AD_UNIT_TYPE_NAMES

    private fun typeFieldFor(type: Class<*>): Field? =
        typeFieldCache.getOrPut(type) { listOfNotNull(resolveTypeField(type)) }.firstOrNull()

    private fun resolveTypeField(type: Class<*>): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { field ->
                !field.isStatic && field.type == unitTypeEnumClass
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }
}

/**
 * Drops advertisement units from the search results list.
 *
 * Filtered on the way OUT rather than on the way in: the input is raw GraphQL edges that
 * have not been classified yet, while the returned list holds finished units that each
 * state their own type. Dropping them here means the page never learns the ad existed —
 * no slot is reserved, no placeholder is drawn and no impression is reported, which is the
 * same outcome as a query that simply had no ad to return.
 *
 * The list is rebuilt with the same ImmutableList type it arrived as; if that rebuild
 * fails the original result is left untouched rather than replaced with something the
 * caller cannot use.
 */
fun hookSearchResultUnitList(method: Method, inspector: SearchResultUnitInspector) {
    if (!searchAdMethodsHooked.add(methodHookKey(method))) return
    method.hookMethod {
        after { param ->
            val units = param.result as? Iterable<*> ?: return@after
            val kept = ArrayList<Any?>()
            var removed = 0
            for (unit in units) {
                if (runCatching { inspector.isAdUnit(unit) }.getOrDefault(false)) removed++ else kept.add(unit)
            }
            if (removed == 0) return@after
            buildImmutableListLike(param.result, kept)?.let { param.result = it }
        }
    }
}

/**
 * Empties the payload of the "ads have arrived" state.
 *
 * The top-position advertisement is fetched by its own query, separately from the results
 * connection, so it never reaches [hookSearchResultUnitList]. Its response lands here, in
 * the one state object that carries a list, and the renderer reads that list to decide
 * what to draw. Replacing it with an empty list puts the page on the path it already takes
 * whenever the server has no ad to sell — the module's standing preference over blanking a
 * component that has already been given something to draw.
 *
 * The constructor still runs; only its list argument changes, so the state machine sees a
 * perfectly ordinary "loaded, nothing in it" transition and every field it depends on is
 * still set.
 */
fun hookSearchAdsLoadedState(constructor: Member) {
    if (!searchAdMethodsHooked.add("${constructor.declaringClass.name}#<init>")) return
    constructor.hookMethod {
        before { param ->
            val current = param.args.getOrNull(0) as? Iterable<*> ?: return@before
            if (!current.iterator().hasNext()) return@before
            buildImmutableListLike(current, emptyList())?.let { param.args[0] = it }
        }
    }
}

/**
 * Suppresses the render of a component that only ever draws a search advertisement.
 *
 * Safe to apply wholesale, unlike [hookTimelineStoryRender]: each component reached by
 * this hook is registered under its own advertisement name and draws nothing else, so
 * there is no organic content to lose. This is the backstop layer — if a build moves the
 * ad onto a data path the two hooks above do not cover, the creative still has nowhere
 * to be drawn.
 */
fun hookSearchAdComponentRender(method: Method) {
    if (!searchAdMethodsHooked.add(methodHookKey(method))) return
    method.hookMethod {
        before { param ->
            param.result = null
        }
    }
}

// ─── Video viewer extensions ──────────────────────────────────────────────────

/**
 * Name tokens that mark a video viewer extension as advertising-only.
 *
 * Same reasoning as [AD_ONLY_PLUGIN_PACK_TOKENS], one surface over: the eighteen
 * extensions that ship on the audited build are named for what they draw, and exactly
 * two of those names carry an advertising token —
 * `InstreamAdsViewerCoordinatorExtension` (the mid-roll takeover) and
 * `InstreamAdsFooterExtension` (the sponsored card docked above the comments).
 *
 * "InstreamAd" is listed separately from "Ads" so a future singular spelling
 * (`InstreamAdFooterExtension`) is still caught. Still deliberately NOT a bare "Ad" —
 * that substring hides inside Loading and Adaptive, and `LoadingSpinnerExtension` is a
 * real extension on this very surface.
 */
val AD_ONLY_VIDEO_EXTENSION_TOKENS = listOf("Ads", "AdBreak", "AdOverlay", "InstreamAd", "SqueezebackAd")

private val adVideoExtensionCache = ConcurrentHashMap<String, Boolean>()

/**
 * True when [instance] is a viewer extension whose own name says it exists to advertise.
 *
 * Resolved per class and cached, by invoking the extension's 0-argument String getters —
 * the same per-instance test [isAdOnlyPluginPack] applies to plugin packs, and it is
 * per-instance for the same reason: the gate method is inherited from a base that
 * organic extensions share, so the method alone identifies nothing.
 */
private fun isAdOnlyVideoExtension(instance: Any): Boolean {
    val className = instance.javaClass.name
    return adVideoExtensionCache.getOrPut(className) {
        runCatching {
            instance.javaClass.declaredMethods
                .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && !m.isStatic }
                .any { m ->
                    m.isAccessible = true
                    val name = m.invoke(instance) as? String ?: return@any false
                    AD_ONLY_VIDEO_EXTENSION_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
                }
        }.getOrDefault(false)
    }
}

/**
 * Refuses an advertising-only viewer extension at its own eligibility gate.
 *
 * The gate is the first thing the extension host calls, and the render is reached only
 * when the gate said yes, so answering "this extension does not apply here" removes the
 * surface instead of blanking it — the same distinction that makes
 * [hookPluginDescriptorGate] safe on the plugin side. Nothing is drawn, nothing is
 * measured, and the organic extensions sharing this exact method are untouched because
 * the decision is taken per instance.
 *
 * This is the layer that was missing while live replays leaked ads: the replay opens the
 * video permalink surface, which builds its overlays from extensions rather than from
 * video plugin packs.
 */
fun hookVideoViewerExtensionGate(method: Method) {
    if (method.returnType != Boolean::class.javaPrimitiveType) return
    if (!pluginHooksInstalled.add(methodHookKey(method))) return
    method.hookMethod {
        before { param ->
            val instance = param.thisObject ?: return@before
            if (!isAdOnlyVideoExtension(instance)) return@before
            param.result = false
        }
    }
}
