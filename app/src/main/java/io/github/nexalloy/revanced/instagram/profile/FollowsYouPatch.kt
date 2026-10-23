package io.github.nexalloy.revanced.instagram.profile

import android.annotation.SuppressLint
import android.view.View
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import java.lang.reflect.Method

private const val SEPARATOR = " · "
private val PRONOUN_SLOTS = listOf("profile_header_pronouns_above_vanity", "profile_header_pronouns")
private const val TAG_KEY = 0x7f_ff_10_01

val FollowsYou = patch(
    name = "Show if a profile follows you",
    description = "Shows Follows you, Following each other or Doesn't follow you on someone's profile.",
) {
    val friendship = ::userFriendshipStatusGetterFingerprint.method
    val relationFollowedBy = runCatching { ::relationshipFollowedByGetterFingerprint.method }.getOrNull()
    val relationFollowing = runCatching { ::relationshipFollowingGetterFingerprint.method }.getOrNull()
    val userFollowedBy = runCatching { ::userFollowedByGetterFingerprint.method }.getOrNull()
    check(relationFollowedBy != null || userFollowedBy != null) { "no followed_by getter found" }
    dependsOn(ProfileHeaderHook)
    runCatching { ScreenAnswer.load(appContext, classLoader) }
    val reads = Reads(friendship, relationFollowedBy, relationFollowing, userFollowedBy)
    ProfileHeader.onBound { bound -> label(bound, reads) }
}

private class Reads(val friendship: Method, val followedBy: Method?, val following: Method?, val ownFollowedBy: Method?)

private fun statusFlag(user: Any, reads: Reads, getter: Method?): Boolean? {
    getter ?: return null
    val status = runCatching { reads.friendship.invoke(user) }.getOrNull() ?: return null
    return runCatching { status.javaClass.getMethod(getter.name).invoke(status) as? Boolean }.getOrNull()
}

private fun relation(bound: ProfileHeader.Bound, reads: Reads): Relation? {
    val answer = ScreenAnswer.get()
    val screen = answer?.friendship(bound.viewModel)
    var followedBy = screen?.let { answer.flag(it, ScreenAnswer.FOLLOWED_BY) }
    var following = if (followedBy != null) screen?.let { answer.flag(it, ScreenAnswer.FOLLOWING) } else null
    if (followedBy == null) followedBy = statusFlag(bound.user, reads, reads.followedBy)
    if (followedBy == null) followedBy = reads.ownFollowedBy?.let { runCatching { it.invoke(bound.user) as? Boolean }.getOrNull() }
    followedBy ?: return null
    if (following == null) following = statusFlag(bound.user, reads, reads.following)
    return when {
        !followedBy -> Relation.DOESNT_FOLLOW_YOU
        following == true -> Relation.FOLLOWING_EACH_OTHER
        else -> Relation.FOLLOWS_YOU
    }
}

private fun textOf(relation: Relation) = when (relation) {
    Relation.FOLLOWS_YOU -> R.string.ig_follows_you
    Relation.FOLLOWING_EACH_OTHER -> R.string.ig_following_each_other
    Relation.DOESNT_FOLLOW_YOU -> R.string.ig_doesnt_follow_you
}

private class Slot(val holder: Any, val getView: Method, val setVisibility: Method) {
    fun view(): TextView? = runCatching { getView.invoke(holder) as? TextView }.getOrNull()
    fun show(visible: Boolean) = runCatching { setVisibility.invoke(holder, if (visible) View.VISIBLE else View.GONE) }
}

private val slots = java.util.WeakHashMap<Any, Slot>()

@SuppressLint("DiscouragedApi")
private fun slotOf(header: View): Slot? {
    val ids = PRONOUN_SLOTS.map { header.resources.getIdentifier(it, "id", header.context.packageName) }.filter { it != 0 }.toSet()
    val owner = header.tag ?: return null
    synchronized(slots) { slots[owner] }?.let { return it }
    fun wraps(holder: Any): Boolean = generateSequence<Class<*>>(holder.javaClass) { it.superclass }
        .takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && View::class.java.isAssignableFrom(it.type) }
        .any { f ->
            val v = runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
            v != null && (v.id in ids || (v as? android.view.ViewStub)?.inflatedId in ids)
        }
    var c: Class<*>? = owner.javaClass
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive || !f.type.isInterface) continue
            val getView = runCatching { f.type.getMethod("getView") }.getOrNull()?.takeIf { View::class.java.isAssignableFrom(it.returnType) } ?: continue
            val setVisibility = runCatching { f.type.getMethod("setVisibility", Int::class.javaPrimitiveType) }.getOrNull() ?: continue
            val holder = runCatching { f.isAccessible = true; f.get(owner) }.getOrNull() ?: continue
            if (!wraps(holder)) continue
            return Slot(holder, getView, setVisibility).also { synchronized(slots) { slots[owner] = it } }
        }
        c = c.superclass
    }
    return null
}

private fun label(bound: ProfileHeader.Bound, reads: Reads) {
    val header = bound.header
    val holder = slotOf(header)
    val direct = PRONOUN_SLOTS.firstNotNullOfOrNull { name ->
        val id = header.resources.getIdentifier(name, "id", header.context.packageName)
        if (id == 0) null else header.findViewById<View>(id) as? TextView
    }
    if (bound.isViewer) {
        direct?.let { clean(it) }
        FollowChip.clear(header)
        return
    }
    val relation = relation(bound, reads) ?: return
    val text = I18n.t(header.context, textOf(relation))
    if (text.isEmpty()) return
    val slot = direct ?: holder?.view()
    if (FollowChip.enabled && FollowChip.show(header, relation, text, slot)) {
        slot?.let { clean(it) }
        return
    }
    FollowChip.clear(header)
    slot ?: return
    val base = clean(slot)
    val shownBefore = slot.visibility == View.VISIBLE && base.isNotBlank()
    val suffix = if (shownBefore) SEPARATOR + text else text
    slot.text = (if (shownBefore) base else "") + suffix
    slot.setTag(TAG_KEY, suffix)
    holder?.show(true)
    slot.visibility = View.VISIBLE
}

private fun clean(slot: TextView): String {
    val added = slot.getTag(TAG_KEY) as? String
    val current = slot.text?.toString().orEmpty()
    val base = if (added != null && current.endsWith(added)) current.removeSuffix(added) else current
    if (added != null) {
        slot.text = base
        slot.setTag(TAG_KEY, null)
        if (base.isBlank()) slot.visibility = View.GONE
    }
    return base
}
