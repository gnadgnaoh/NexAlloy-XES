package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostScreenshot = patch(
    name = "Ghost screenshot",
    description = "Blocks screenshot notification from being sent to the other party.",
) {
    ::screenshotFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: screenshot notification blocked" }
            param.result = null
        }
    }
}
