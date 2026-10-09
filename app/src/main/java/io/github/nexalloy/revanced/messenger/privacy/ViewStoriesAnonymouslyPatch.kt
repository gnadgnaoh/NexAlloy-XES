package io.github.nexalloy.revanced.messenger.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val ViewStoriesAnonymously = patch(
    name = "View stories anonymously",
    description = "Opens other people's stories without adding you to their viewer list. " +
        "A story opened this way may still show as unseen on your device.",
) {
    ::montageMarkReadHandlerFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Messenger: story mark-read suppressed (anonymous view)" }
            param.result = null
        }
    }
}
