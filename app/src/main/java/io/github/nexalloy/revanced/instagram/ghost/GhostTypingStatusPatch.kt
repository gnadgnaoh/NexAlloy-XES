package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostTypingStatus = patch(
    name = "Ghost typing status",
    description = "Blocks typing indicator from being sent to the other party.",
) {
    ::typingStatusFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: typing status blocked" }
            param.result = null
        }
    }
}
