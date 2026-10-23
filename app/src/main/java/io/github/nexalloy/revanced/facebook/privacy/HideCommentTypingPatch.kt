package io.github.nexalloy.revanced.facebook.privacy

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val COMMENT_SEND_TYPING = "CommentTypingContext\$sendActiveStateRunnable\$1"

val HideCommentTyping = patch(
    name = "Hide typing indicator",
    description = "Others don't see that you're typing a comment. Chats are not changed.",
) {
    val runs = ::typingRunnableRunsFingerprint.dexMethodList.map { it.toMethod() }.filter { run ->
        runCatching {
            run.declaringClass.getDeclaredField("__redex_internal_original_name")
                .apply { isAccessible = true }.get(null) == COMMENT_SEND_TYPING
        }.getOrDefault(false)
    }
    check(runs.size == 1) { "comment typing runnable not found (${runs.size})" }
    runs.single().hookMethod(XC_MethodReplacement.DO_NOTHING)
}
