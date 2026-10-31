package io.github.nexalloy.morphe.tiktok.readreceipts

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val TAG = "[TikTok read receipts]"
private const val NOT_SENT = -1014

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Stops TikTok from telling others you read their messages. You still see when they read yours, " +
        "and chats are still marked read on this phone.",
) {
    val result = ::markReadResultFingerprint.constructor.apply { isAccessible = true }
    ::markReadSendFingerprint.hookMethod {
        before { param ->
            runCatching { param.result = result.newInstance(true, "", NOT_SENT, null) }
                .onFailure { Logger.printException({ "$TAG could not answer the read request" }, it) }
        }
    }
    ::markReadHandlerSendFingerprint.hookMethod { before { param -> param.result = null } }
}
