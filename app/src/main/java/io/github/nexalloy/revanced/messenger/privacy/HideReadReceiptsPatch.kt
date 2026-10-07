package io.github.nexalloy.revanced.messenger.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Stops sending read receipts in normal and end-to-end encrypted chats. " +
        "Chats you open may stay unread on this device.",
    use = false,
) {
    runCatching {
        ::plainReadReceiptRunnableFingerprint.hookMethod {
            before { param ->
                Logger.printDebug { "Messenger: plain read receipt blocked" }
                param.result = null
            }
        }
    }.onFailure { Logger.printException({ "Messenger: plain read-receipt hook failed" }, it) }

    runCatching {
        ::encryptedReadReceiptFingerprint.hookMethod {
            before { param ->
                Logger.printDebug { "Messenger: encrypted read receipt blocked" }
                param.result = null
            }
        }
    }.onFailure { Logger.printException({ "Messenger: encrypted read-receipt hook failed" }, it) }
}
