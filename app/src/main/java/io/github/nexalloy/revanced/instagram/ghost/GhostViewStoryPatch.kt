package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostViewStory = patch(
    name = "Ghost view story",
    description = "Prevents story seen notifications from being sent to the uploader.",
) {
    ::storySeenFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: story seen blocked" }
            param.result = null
        }
    }
}
