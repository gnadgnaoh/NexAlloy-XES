package io.github.nexalloy.revanced.instagram.ads

import io.github.nexalloy.HookDsl
import io.github.nexalloy.IScopedHookCallback
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal fun interface ReelsRule {
    fun hides(media: Any): Boolean
}

private val rules = CopyOnWriteArrayList<Pair<String, ReelsRule>>()

internal fun addReelsRule(name: String, rule: ReelsRule) {
    synchronized(rules) { if (rules.none { it.first == name }) rules += name to rule }
}

private const val MEDIA = "com.instagram.feed.media.Media"

private val PICKED_BY_INSTAGRAM = setOf(
    "clips_viewer_clips_tab",
    "clips_viewer_homecoming_fyp",
    "clips_viewer_clips_connected",
    "clips_viewer_clips_location",
    "clips_viewer_friends_lane",
    "clips_viewer_trending_only",
    "clips_viewer_clips_shop_lane",
    "clips_viewer_clips_ads_only",
    "clips_viewer_social_debug",
    "clips_viewer_clips_voice_translation_dogfooding",
    "clips_viewer_clips_translated_stickers_dogfooding",
    "clips_viewer_clips_translated_closed_captions_dogfooding",
)

private val NONE = Any()
private val mediaFields = ConcurrentHashMap<Class<*>, Any>()

private fun mediaOf(item: Any): Any? {
    val field = mediaFields.getOrPut(item.javaClass) {
        item.javaClass.declaredFields
            .filter { it.type.name == MEDIA && !Modifier.isStatic(it.modifiers) }
            .singleOrNull()?.apply { isAccessible = true } ?: NONE
    } as? Field ?: return null
    return runCatching { field.get(item) }.getOrNull()
}

private fun moduleOf(callback: Any): String? {
    var type: Class<*>? = callback.javaClass
    while (type != null && type != Any::class.java) {
        for (f in type.declaredFields) {
            if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
            val value = runCatching { f.isAccessible = true; f.get(callback) as? String }.getOrNull()
            if (value?.startsWith("clips_viewer_") == true) return value
        }
        type = type.superclass
    }
    return null
}

private fun filter(items: List<*>): List<*>? {
    var reels = 0
    val kept = items.filter { item ->
        val media = item?.let(::mediaOf) ?: return@filter true
        reels++
        rules.none { (_, rule) -> runCatching { rule.hides(media) }.getOrDefault(false) }
    }
    if (kept.size == items.size) return null
    if (kept.none { it?.let(::mediaOf) != null } && reels > 0) return null
    return ArrayList(kept)
}

internal val ReelsPageFilterHook = patch(name = "<ReelsPageFilterHook>") {
    val callback = ::reelsPageCallbackFingerprint.method
    val sources = ::reelsPageSourceMethodsFingerprint.dexMethodList.map { it.toMethod() }
    check(sources.isNotEmpty()) { "no Reels page source found" }

    val onPage: HookDsl<IScopedHookCallback>.() -> Unit = {
        after { param ->
            if (rules.isEmpty()) return@after
            if (moduleOf(outerParam.thisObject) !in PICKED_BY_INSTAGRAM) return@after
            val items = param.result as? List<*> ?: return@after
            filter(items)?.let { param.result = it }
        }
    }
    callback.hookMethod(scopedHook(*sources.map { it to onPage }.toTypedArray()))
}
