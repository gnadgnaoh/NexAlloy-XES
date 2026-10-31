package io.github.nexalloy.revanced.threads.time

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.revanced.shared.time.CONTEXT
import io.github.nexalloy.revanced.shared.time.STRING
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

private const val POST_TIMESTAMP_NOTE = "com.instagram.barcelona.feed.post.ui.PostHeaderTimestamp (PostTimestamp.kt:"

val postTimestampLabelFingerprint = findMethodDirect {
    val header = findMethod { matcher { usingStrings(listOf(POST_TIMESTAMP_NOTE), StringMatchType.StartsWith) } }.single()
    header.declaredClass!!.methods.filter { m ->
        Modifier.isStatic(m.modifiers) && m.returnTypeName == STRING && m.paramTypeNames == listOf(CONTEXT, "double")
    }.single()
}
