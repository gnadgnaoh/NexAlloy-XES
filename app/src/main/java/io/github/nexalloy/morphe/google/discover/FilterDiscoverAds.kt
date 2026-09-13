package io.github.nexalloy.morphe.google.discover

import app.morphe.extension.shared.Logger
import io.github.nexalloy.isStatic
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

val FilterDiscoverAds = patch(
    name = "Filter Discover ads",
    description = "Filters promoted ad cards from the Google Discover feed.",
) {
    ::streamRenderableListFingerprint.hookMethod {
        after { param ->
            val items = param.result as? List<*> ?: return@after
            if (items.isEmpty()) return@after

            val fp = DiscoverAdFilter.fastFingerprint(items)
            if (fp == DiscoverAdFilter.lastFingerprint) {
                DiscoverAdFilter.lastFilteredSnapshot?.let { param.result = it }
                return@after
            }

            var removed = 0
            val filtered = ArrayList<Any?>(items.size)
            for (item in items) {
                val key = item?.let(DiscoverAdFilter::stableItemKey)
                if (key != null && DiscoverAdFilter.isAdItem(key)) {
                    removed++
                    Logger.printDebug { "Discover: blocked ad key=$key" }
                } else {
                    filtered += item
                }
            }

            DiscoverAdFilter.lastFingerprint = fp

            if (removed == 0) {
                DiscoverAdFilter.lastFilteredSnapshot = null
            } else {
                Logger.printDebug { "Discover: removed $removed ad(s) from ${items.size} items" }
                DiscoverAdFilter.lastFilteredSnapshot = filtered
                param.result = filtered
            }
        }
    }
}

/**
 * Runtime caches of the Discover filter. The stream method can be called from several threads,
 * so every cache is concurrent.
 */
internal object DiscoverAdFilter {
    /** Ad-slot cluster tokens found in Discover content ids. */
    private val adClusterTokens = setOf("feedads")

    private val decisionCache = ConcurrentHashMap<String, Boolean>()
    private val instanceFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val nonSliceClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @Volatile
    var lastFingerprint: Long = Long.MIN_VALUE

    @Volatile
    var lastFilteredSnapshot: List<Any?>? = null

    fun isAdItem(key: String): Boolean = decisionCache.getOrPut(key) {
        val lower = key.lowercase(Locale.ROOT)
        adClusterTokens.any { it in lower }
    }

    /**
     * `SimpleClassName#<first non-blank String value>` of an item, or null when it has none. A
     * class is remembered as "not a content slice" the first time one of its items yields no
     * key, and all its later items are skipped.
     *
     * The content id sits in an obfuscated field whose name changes between AGSA builds, so the
     * item is not read by field name: the first non-blank String instance value wins.
     */
    fun stableItemKey(item: Any): String? {
        val cls = item.javaClass
        if (cls in nonSliceClasses) return null

        for (f in instanceFieldsCache.getOrPut(cls) { instanceFieldsOf(cls) }) {
            val value = runCatching { f.get(item) as? String }.getOrNull()
            if (!value.isNullOrBlank()) return "${cls.simpleName}#$value"
        }

        nonSliceClasses.add(cls)
        return null
    }

    /**
     * Identity based fingerprint of up to four sampled elements, so the exact same list returned
     * again on a redraw is not filtered twice.
     */
    fun fastFingerprint(items: List<*>): Long {
        var hash = items.size.toLong()
        val step = (items.size / 3).coerceAtLeast(1)
        var i = 0
        var n = 0
        while (i < items.size && n < 4) {
            hash = hash * 31L + System.identityHashCode(items[i]).toLong()
            i += step
            n++
        }
        return hash
    }

    private fun instanceFieldsOf(cls: Class<*>): List<Field> =
        generateSequence(cls) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !it.isStatic && !it.isSynthetic }
            .filter { runCatching { it.isAccessible = true }.isSuccess }
            .toList()
}
