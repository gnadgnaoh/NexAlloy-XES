package io.github.nexalloy.revanced.threads.dm

import io.github.nexalloy.patch

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Stops Threads from telling others you read their messages. You still see when they read yours.",
) {
    val sent = ::threadActionSentFieldFingerprint.field.apply { isAccessible = true }
    val done = sent.get(null) ?: error("thread action result is not ready")
    ::markThreadReadFingerprint.hookMethod { before { param -> param.result = done } }
}
