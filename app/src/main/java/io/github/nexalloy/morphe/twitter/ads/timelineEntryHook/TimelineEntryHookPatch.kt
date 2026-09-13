package io.github.nexalloy.morphe.twitter.ads.timelineEntryHook

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal var hideAdsEnabled = false
internal var hideRevisitPinnedPostsEnabled = false
internal var hideCommunitiesToJoinEnabled = false
internal var hideCreatorsToSubscribeEnabled = false
internal var hideDetailedPostsEnabled = false
internal var hidePremiumPromptEnabled = false
internal var hideRevisitBookmarksEnabled = false
internal var hideTodaysNewsEnabled = false
internal var hideTopPeopleSearchEnabled = false
internal var hideWhoToFollowEnabled = false

internal fun isEntryIdRemove(entryId: String?): Boolean {
    if (entryId == null) return false
    val split = entryId.split("-")
    val head = split.getOrElse(0) { "" }

    if (head == "cursor" || head == "Guide" || head.startsWith("semantic_core")) return false

    return when {
        (entryId.contains("promoted") || (head == "conversationthread" && split.size == 3)) && hideAdsEnabled -> true
        (head == "superhero" || head == "eventsummary") && hideAdsEnabled -> true
        entryId.contains("rtb") && hideAdsEnabled -> true
        head.startsWith("tweetdetail") && hideDetailedPostsEnabled -> true
        head == "bookmarked" && hideRevisitBookmarksEnabled -> true
        entryId.startsWith("community-to-join") && hideCommunitiesToJoinEnabled -> true
        entryId.startsWith("who-to-follow") && hideWhoToFollowEnabled -> true
        entryId.startsWith("who-to-subscribe") && hideCreatorsToSubscribeEnabled -> true
        entryId.startsWith("pinned-tweets") && hideRevisitPinnedPostsEnabled -> true
        entryId.startsWith("messageprompt-") && hidePremiumPromptEnabled -> true
        (entryId.startsWith("main-event-") || head == "pivot") && hideAdsEnabled -> true
        head == "toptabsrpusermodule" && hideTopPeopleSearchEnabled -> true
        entryId.startsWith("stories") && hideTodaysNewsEnabled -> true
        else -> false
    }
}

/** Logs the component and entryId of every timeline item (for finding new recommendation ids). */
internal var logTimelineComponents = false

/** ClientEventInfo component names to hide, matched case-insensitively as substrings. */
internal val recommendationComponents: MutableSet<String> = ConcurrentHashMap.newKeySet()

private fun matchesComponent(component: String?): Boolean {
    if (component == null || recommendationComponents.isEmpty()) return false
    val c = component.lowercase()
    return recommendationComponents.any { c.contains(it.lowercase()) }
}

// The mapper runs on several background threads; a plain HashMap here could corrupt itself.
private val NO_FIELD = Any()
private val promotedFieldCache = ConcurrentHashMap<Class<*>, Any>()

private fun promotedFieldOf(itemClass: Class<*>, promotedClass: Class<*>): Field? =
    promotedFieldCache.getOrPut(itemClass) {
        itemClass.declaredFields
            .firstOrNull { it.type == promotedClass }
            ?.apply { isAccessible = true }
            ?: NO_FIELD
    } as? Field

private inline fun <T> resolving(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        throw Exception("TimelineEntryHook: could not resolve $what", e)
    }

val TimelineEntryHook = patch(name = "<TimelineEntryHook>") {
    val itemInterface: Class<*> = resolving("mapper row -> UrtTimelineItem") {
        DbTimelineEntryToItemFingerprint.method.returnType
    }

    val entryIdGetter: Method = resolving("entryId getter on UrtTimelineItem") {
        itemInterface.declaredMethods.single {
            it.parameterCount == 0 && it.returnType == String::class.java
        }.apply { isAccessible = true }
    }

    val promotedClass: Class<*> = resolving("class TimelinePromotedMetadata") {
        PromotedMetadataToStringFingerprint.declaredClass
    }

    val componentReader: Lazy<Pair<Method, Field>> = lazy {
        val clientEventInfoClass = resolving("class ClientEventInfo") {
            ClientEventInfoToStringFingerprint.declaredClass
        }
        val getter = resolving("ClientEventInfo getter on UrtTimelineItem") {
            itemInterface.declaredMethods.single {
                it.parameterCount == 0 && it.returnType == clientEventInfoClass
            }.apply { isAccessible = true }
        }

        val field = resolving("component field of ClientEventInfo") {
            clientEventInfoClass.declaredFields
                .filter { it.type == String::class.java }
                .minByOrNull { it.name }!!
                .apply { isAccessible = true }
        }
        getter to field
    }

    fun componentOf(item: Any): String? {
        val (getter, field) = componentReader.value
        val info = getter.invoke(item) ?: return null
        return field.get(info) as? String
    }

    fun shouldRemove(item: Any): Boolean {
        if (hideAdsEnabled && promotedFieldOf(item.javaClass, promotedClass)?.get(item) != null) {
            return true
        }

        if (logTimelineComponents || recommendationComponents.isNotEmpty()) {
            val component = componentOf(item)
            if (logTimelineComponents) {
                Logger.printInfo {
                    "[Twitter] timeline item: ${item.javaClass.simpleName} component=$component " +
                        "entryId=${entryIdGetter.invoke(item)}"
                }
            }
            if (matchesComponent(component)) return true
        }

        return isEntryIdRemove(entryIdGetter.invoke(item) as? String)
    }

    DbTimelineEntryToItemFingerprint.hookMethod {
        after { param ->
            val item = param.result ?: return@after
            if (shouldRemove(item)) param.result = null
        }
    }
}
