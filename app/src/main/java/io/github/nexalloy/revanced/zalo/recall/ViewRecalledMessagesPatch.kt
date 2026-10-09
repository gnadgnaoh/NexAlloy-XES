package io.github.nexalloy.revanced.zalo.recall

import io.github.nexalloy.patch

val ViewRecalledMessages = patch(
    name = "View recalled messages",
    description = "Keeps messages visible in the chat when the sender recalls them.",
) {

    ::applyUndoEntryFingerprint.hookMethod {
        before { param -> param.result = null }
    }
}
