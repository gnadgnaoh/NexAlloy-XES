package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClass
import io.github.nexalloy.findFieldOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.isNotStatic
import io.github.nexalloy.setObjectField
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Ad predicate and list helpers shared by all TikTok feed hooks.
 *
 * Replaces Morphe's FeedItemsFilter (~1000 lines of probes, list fingerprints, TTL caches):
 *  - no allocation when a list contains no ad (the common case);
 *  - a list is only edited where no TikTok loop or adapter holds it yet;
 *  - never throws into TikTok code: any reflection problem keeps the item.
 */
internal object AwemeAdFilter {

    /** Paid ads: Aweme.isAd() / Aweme.isSoftAd(). */
    @Volatile
    var hideAds = false

    /** Videos using a paid promoted sound: Aweme.isWithPromotionalMusic(). */
    @Volatile
    var hidePromotedMusic = false

    /** TikTok Shop cards inserted into the feed: see [isShopCard]. */
    @Volatile
    var hideShopCards = false

    /** Paid partnerships and affiliate disclosures: see [isPaidPartnership]. */
    @Volatile
    var hidePaidPartnerships = false

    val enabled get() = hideAds || hidePromotedMusic || hideShopCards || hidePaidPartnerships

    /**
     * `cardInsertInfo.cardType` values of TikTok Shop cards. Starts with the native EC card; the
     * Lynx EC and Shop search card types are server settings, added as [HideShopAds] sees TikTok
     * request or read them.
     */
    val shopCardTypes: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>().apply { add(NATIVE_EC_CARD_TYPE) }

    private lateinit var awemeClass: Class<*>

    fun init(classLoader: ClassLoader) {
        awemeClass = AWEME_CLASS.findClass(classLoader)
    }

    fun isFiltered(item: Any?): Boolean {
        if (item == null || !::awemeClass.isInitialized || !awemeClass.isInstance(item)) return false
        val matched = (hideAds && isAd(item)) ||
                (hideShopCards && isShopCard(item)) ||
                (hidePaidPartnerships && isPaidPartnership(item)) ||
                (hidePromotedMusic && item.callMethodOrNull("isWithPromotionalMusic") == true)
        // The signed-in account's own posts are never a feed preference (own profile grid,
        // own promoted or branded posts). Only looked up once an item would go.
        return matched && !SignedInUser.owns(item)
    }

    /** Ads only, whatever the other switches say: the search grid and ad-only routes. */
    fun isAdItem(item: Any?): Boolean {
        if (!hideAds || item == null || !::awemeClass.isInitialized || !awemeClass.isInstance(item)) return false
        return isAd(item) && !SignedInUser.owns(item)
    }

    /**
     * `isAd()` and `isSoftAd()` also require `awemeRawAd`; an Aweme that carries the raw ad
     * payload without either flag (boosted / Spark posts) is an ad as well.
     */
    private fun isAd(item: Any): Boolean =
        item.callMethodOrNull("isAd") == true ||
                item.callMethodOrNull("isSoftAd") == true ||
                item.callMethodOrNull("getAwemeRawAd") != null

    /**
     * Creator-paid promotion that is not a TikTok ad slot, all read from Gson models:
     *  - "Paid partnership" / branded content: `brandContentAccounts`, and the non-default
     *    flags of `AwemeCommerceStruct` (the struct itself exists on ordinary posts);
     *  - "Creator earns commission": `anchorsExtras.panel_top_disclosure_label` with a text;
     *  - location affiliate label: `contentModel.standardBusinessModel.localAllianceInfo
     *    .showBottomLabel()`, the check TikTok itself makes before drawing it.
     * `commercialVideoInfo` is deliberately not used: it also describes branded effects that
     * ordinary users post with.
     */
    private fun isPaidPartnership(item: Any): Boolean {
        val accounts = item.callMethodOrNull("getBrandContentAccounts") as? Collection<*>
        if (!accounts.isNullOrEmpty()) return true

        item.callMethodOrNull("getCommerceVideoAuthInfo")?.let { commerce ->
            if (commerce.callMethodOrNull("isBrandedContent") == true ||
                commerce.callMethodOrNull("isBrandOrganicContent") == true ||
                commerce.nonZero("getBrandedContentType") ||
                commerce.nonZero("getBrandOrganicType") ||
                (commerce.callMethodOrNull("getEcSearchBoBcLabelText") as? String).isNullOrBlank().not()
            ) return true
        }

        return hasCommissionDisclosure(item) || hasLocalAllianceLabel(item)
    }

    private fun Any.nonZero(getter: String) = (callMethodOrNull(getter) as? Number)?.toLong()?.let { it != 0L } == true

    private const val COMMISSION_DISCLOSURE = "panel_top_disclosure_label"

    private fun hasCommissionDisclosure(item: Any): Boolean {
        val extras = (item.callMethodOrNull("getAnchorsExtras") as? String)?.trim()
        if (extras.isNullOrEmpty() || !extras.contains("\"$COMMISSION_DISCLOSURE\"")) return false
        return runCatching {
            val label = JSONObject(extras).optJSONObject(COMMISSION_DISCLOSURE) ?: return false
            label.optString("display_text").isNotBlank() || label.optString("truncatable_text").isNotBlank()
        }.getOrDefault(false)
    }

    private fun hasLocalAllianceLabel(item: Any): Boolean {
        val business = item.callMethodOrNull("getContentModel")
            ?.getObjectFieldOrNull("standardBusinessModel") ?: return false
        val alliance = business.callMethodOrNull("getLocalAllianceInfo") ?: return false
        return alliance.callMethodOrNull("showBottomLabel") == true
    }

    // region search grid

    /** Ad shapes of a search result card (SearchMixFeed and its base class). */
    private val SEARCH_AD_FIELDS = listOf("aiAdCard", "brandZoneCard", "preciseAd", "multiAdCard")

    /**
     * A search result card that is an ad by TikTok's own verdict (`isAdOrContainAd()`: the
     * card carries ad creative ids), by one of its ad shapes, or because the video it wraps is.
     */
    fun isSearchAdCard(card: Any?): Boolean {
        if (!hideAds || card == null) return false
        if (card.callMethodOrNull("isAdOrContainAd") == true) return true
        if (SEARCH_AD_FIELDS.any { card.getObjectFieldOrNull(it) != null }) return true
        return isAdItem(card.getObjectFieldOrNull("aweme"))
    }

    // endregion

    // region own posts

    /**
     * The signed-in account, read through TikTok's ServiceManager and IAccountUserService by
     * their kept names. Cached for a few seconds: the account can change.
     */
    private object SignedInUser {
        private const val TTL_MS = 10_000L

        @Volatile private var uid: String? = null
        @Volatile private var readAt = 0L
        @Volatile private var unavailable = false
        private var serviceManager: Any? = null
        private var getService: java.lang.reflect.Method? = null
        private var accountClass: Class<*>? = null

        fun owns(item: Any): Boolean {
            val self = currentUid() ?: return false
            val author = item.callMethodOrNull("getAuthor") ?: return false
            return self == author.callMethodOrNull("getUid")
        }

        private fun currentUid(): String? {
            val now = System.currentTimeMillis()
            if (now - readAt < TTL_MS) return uid
            uid = read()
            readAt = now
            return uid
        }

        private fun read(): String? {
            if (unavailable) return null
            return try {
                if (accountClass == null) {
                    val loader = awemeClass.classLoader
                    val manager = Class.forName("com.ss.android.ugc.aweme.framework.services.ServiceManager", false, loader)
                    accountClass = Class.forName("com.ss.android.ugc.aweme.IAccountUserService", false, loader)
                    getService = manager.getMethod("getService", Class::class.java)
                    serviceManager = manager.getMethod("get").invoke(null)
                }
                val service = getService!!.invoke(serviceManager, accountClass) ?: return null
                if (service.callMethodOrNull("isLogin") != true) return null
                (service.callMethodOrNull("getCurUserId") as? String)?.takeIf { it.isNotEmpty() && it != "0" }
            } catch (_: ClassNotFoundException) {
                unavailable = true
                null
            } catch (_: NoSuchMethodException) {
                unavailable = true
                null
            } catch (_: Throwable) {
                null // service not ready yet; asked again after the TTL
            }
        }
    }

    // endregion

    /**
     * A card the card-insert platform put into the feed (an Aweme carrying `cardInsertInfo`)
     * whose type is a TikTok Shop card. Ordinary videos have no cardInsertInfo; other inserted
     * cards (story recap, milestones, mini dramas...) have other types and are kept.
     */
    private fun isShopCard(item: Any): Boolean {
        val cardInsertInfo = item.callMethodOrNull("getCardInsertInfo") ?: return false
        val cardType = cardInsertInfo.callMethodOrNull("getCardType") as? Int ?: return false
        return cardType in shopCardTypes
    }

    /**
     * @param unwrap maps a list element to its Aweme ([awemeOf] for wrapper lists).
     * @return a new list without filtered items, or null when nothing has to be removed.
     */
    fun filteredCopyOrNull(list: List<*>?, unwrap: (Any?) -> Any? = { it }): ArrayList<Any?>? {
        if (!enabled || list.isNullOrEmpty()) return null
        return try {
            if (list.none { isFiltered(unwrap(it)) }) null
            else list.filterNotTo(ArrayList(list.size)) { isFiltered(unwrap(it)) }
        } catch (_: ConcurrentModificationException) {
            null
        }
    }

    /**
     * Removes filtered items from `owner.<fieldName>`: edits the list in place when it is mutable
     * (other holders keep the same instance), otherwise replaces the field.
     *
     * @return number of removed items.
     */
    fun filterListField(owner: Any?, fieldName: String, unwrap: (Any?) -> Any? = { it }): Int {
        val list = owner?.getObjectFieldOrNull(fieldName) as? List<*> ?: return 0
        val kept = filteredCopyOrNull(list, unwrap) ?: return 0
        val removed = list.size - kept.size
        @Suppress("UNCHECKED_CAST")
        val edited = runCatching { (list as MutableList<Any?>).run { clear(); addAll(kept) } }.isSuccess
        if (!edited) runCatching { owner.setObjectField(fieldName, kept) }
        return removed
    }

    // region wrappers

    private val awemeFields = ConcurrentHashMap<Class<*>, Any>()
    private val NO_FIELD = Any()

    /**
     * Maps FollowFeed / ProfileAdData / Aweme to its Aweme through the kept Gson member
     * `getAweme()` or `aweme`.
     */
    fun awemeOf(element: Any?): Any? {
        if (element == null || !::awemeClass.isInitialized) return null
        if (awemeClass.isInstance(element)) return element
        val field = awemeFields.getOrPut(element.javaClass) {
            element.javaClass.findFieldOrNull("aweme")?.takeIf { awemeClass.isAssignableFrom(it.type) } ?: NO_FIELD
        }
        return if (field is Field) field.get(element) else element.callMethodOrNull("getAweme")
    }

    private val singleListFields = ConcurrentHashMap<Class<*>, Any>()

    /**
     * The only List instance field of a payload / event object. Null when there are zero or
     * several, so a layout change degrades to "no filtering" instead of editing the wrong list.
     */
    fun singleListField(owner: Any): Field? =
        singleListFields.getOrPut(owner.javaClass) {
            generateSequence(owner.javaClass) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.isNotStatic && List::class.java.isAssignableFrom(it.type) }
                .singleOrNull()
                ?.apply { isAccessible = true }
                ?: NO_FIELD
        } as? Field

    // endregion

    // region first read

    /**
     * Identity based "seen once" set (bounded LRU of weak references keyed by identityHashCode).
     * The model's equals()/hashCode() are never called and nothing is leaked.
     */
    private val seen = object : LinkedHashMap<Int, WeakReference<Any>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WeakReference<Any>>?) = size > 256
    }

    /** @return true exactly once per live object. */
    fun markFirstSeen(owner: Any): Boolean = synchronized(seen) {
        val key = System.identityHashCode(owner)
        if (seen[key]?.get() === owner) return false
        seen[key] = WeakReference(owner)
        true
    }

    // endregion
}
