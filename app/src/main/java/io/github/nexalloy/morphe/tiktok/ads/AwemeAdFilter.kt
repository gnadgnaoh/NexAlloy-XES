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

internal object AwemeAdFilter {

    @Volatile
    var hideAds = false

    @Volatile
    var hidePromotedMusic = false

    @Volatile
    var hideShopCards = false

    @Volatile
    var hidePaidPartnerships = false

    val enabled get() = hideAds || hidePromotedMusic || hideShopCards || hidePaidPartnerships

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
        return matched && !SignedInUser.owns(item)
    }

    fun isAdItem(item: Any?): Boolean {
        if (!hideAds || item == null || !::awemeClass.isInitialized || !awemeClass.isInstance(item)) return false
        return isAd(item) && !SignedInUser.owns(item)
    }

    private fun isAd(item: Any): Boolean =
        item.callMethodOrNull("isAd") == true ||
                item.callMethodOrNull("isSoftAd") == true ||
                item.callMethodOrNull("getAwemeRawAd") != null

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

    private val SEARCH_AD_FIELDS = listOf("aiAdCard", "brandZoneCard", "preciseAd", "multiAdCard")

    fun isSearchAdCard(card: Any?): Boolean {
        if (!hideAds || card == null) return false
        if (card.callMethodOrNull("isAdOrContainAd") == true) return true
        if (SEARCH_AD_FIELDS.any { card.getObjectFieldOrNull(it) != null }) return true
        return isAdItem(card.getObjectFieldOrNull("aweme"))
    }

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
                null
            }
        }
    }

    private fun isShopCard(item: Any): Boolean {
        val cardInsertInfo = item.callMethodOrNull("getCardInsertInfo") ?: return false
        val cardType = cardInsertInfo.callMethodOrNull("getCardType") as? Int ?: return false
        return cardType in shopCardTypes
    }

    fun filteredCopyOrNull(list: List<*>?, unwrap: (Any?) -> Any? = { it }): ArrayList<Any?>? {
        if (!enabled || list.isNullOrEmpty()) return null
        return try {
            if (list.none { isFiltered(unwrap(it)) }) null
            else list.filterNotTo(ArrayList(list.size)) { isFiltered(unwrap(it)) }
        } catch (_: ConcurrentModificationException) {
            null
        }
    }

    fun filterListField(owner: Any?, fieldName: String, unwrap: (Any?) -> Any? = { it }): Int {
        val list = owner?.getObjectFieldOrNull(fieldName) as? List<*> ?: return 0
        val kept = filteredCopyOrNull(list, unwrap) ?: return 0
        val removed = list.size - kept.size
        @Suppress("UNCHECKED_CAST")
        val edited = runCatching { (list as MutableList<Any?>).run { clear(); addAll(kept) } }.isSuccess
        if (!edited) runCatching { owner.setObjectField(fieldName, kept) }
        return removed
    }

    private val awemeFields = ConcurrentHashMap<Class<*>, Any>()
    private val NO_FIELD = Any()

    fun awemeOf(element: Any?): Any? {
        if (element == null || !::awemeClass.isInitialized) return null
        if (awemeClass.isInstance(element)) return element
        val field = awemeFields.getOrPut(element.javaClass) {
            element.javaClass.findFieldOrNull("aweme")?.takeIf { awemeClass.isAssignableFrom(it.type) } ?: NO_FIELD
        }
        return if (field is Field) field.get(element) else element.callMethodOrNull("getAweme")
    }

    private val singleListFields = ConcurrentHashMap<Class<*>, Any>()

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

    private val seen = object : LinkedHashMap<Int, WeakReference<Any>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WeakReference<Any>>?) = size > 256
    }

    fun markFirstSeen(owner: Any): Boolean = synchronized(seen) {
        val key = System.identityHashCode(owner)
        if (seen[key]?.get() === owner) return false
        seen[key] = WeakReference(owner)
        true
    }
}
