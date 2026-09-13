package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClass
import io.github.nexalloy.findFieldOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.isNotStatic
import io.github.nexalloy.setObjectField
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

    val enabled get() = hideAds || hidePromotedMusic

    private lateinit var awemeClass: Class<*>

    fun init(classLoader: ClassLoader) {
        awemeClass = AWEME_CLASS.findClass(classLoader)
    }

    fun isFiltered(item: Any?): Boolean {
        if (item == null || !::awemeClass.isInitialized || !awemeClass.isInstance(item)) return false
        if (hideAds && (item.callMethodOrNull("isAd") == true || item.callMethodOrNull("isSoftAd") == true)) {
            return true
        }
        return hidePromotedMusic && item.callMethodOrNull("isWithPromotionalMusic") == true
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
