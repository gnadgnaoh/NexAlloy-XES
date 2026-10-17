package io.github.nexalloy.revanced.messenger.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val HideTypingIndicator = patch(
    name = "Hide typing indicator",
    description = "Stops sending your typing status in normal and end-to-end encrypted chats.",
) {
    ::plainTypingRunnablesFingerprint.dexMethodList.forEach { dexMethod ->
        dexMethod.hookMethod {
            before { param ->
                Logger.printDebug { "Messenger: plain typing signal blocked" }
                param.result = null
            }
        }
    }

    ::encryptedTypingFingerprint.hookMethod {
        before { param ->
            if (param.args?.getOrNull(1) == true) {
                param.args[1] = false
                Logger.printDebug { "Messenger: encrypted typing flag cleared" }
            }
        }
    }
}
