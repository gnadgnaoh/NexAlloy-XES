package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostSeenState = patch(
    name = "Ghost seen state",
    description = "Blocks DM read receipts from being sent.",
) {
    ::seenStateFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: DM seen state blocked" }
            param.result = null
        }
    }
}
