package io.github.nexalloy.revanced.messenger.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val HideTypingIndicator = patch(
    name = "Hide typing indicator",
    description = "Stops sending your typing status in normal and end-to-end encrypted chats.",
) {
    runCatching {
        ::plainTypingRunnablesFingerprint.dexMethodList.forEach { dexMethod ->
            dexMethod.hookMethod {
                before { param ->
                    Logger.printDebug { "Messenger: plain typing signal blocked" }
                    param.result = null
                }
            }
        }
    }.onFailure { Logger.printException({ "Messenger: plain typing hook failed" }, it) }

    runCatching {
        ::encryptedTypingFingerprint.hookMethod {
            before { param ->
                if (param.args?.getOrNull(1) == true) {
                    param.args[1] = false
                    Logger.printDebug { "Messenger: encrypted typing flag cleared" }
                }
            }
        }
    }.onFailure { Logger.printException({ "Messenger: encrypted typing hook failed" }, it) }
}
