package io.github.nexalloy.revanced.instagram.feed

import io.github.nexalloy.morphe.findMethodDirect

private const val USER_SESSION = "com.instagram.common.session.UserSession"

val likeCountHiddenDeciderFingerprint = findMethodDirect {
    val hiddenKey = "like_and_view_counts_disabled".hashCode()
    val deciders = findMethod {
        matcher { paramTypes(USER_SESSION, "java.lang.String", "boolean"); returnType = "boolean" }
    }.filter { decider ->
        findMethod { matcher { addInvoke(decider.descriptor); usingNumbers(hiddenKey) } }.size >= 3
    }
    deciders.singleOrNull() ?: error("Expected 1 like count decider, found ${deciders.size}")
}
