package io.github.nexalloy.revanced.threads.dm

import io.github.nexalloy.patch

val HideTypingIndicator = patch(
    name = "Hide typing indicator",
    description = "Stops Threads from showing others that you are typing. You still see when they type.",
) {
    ::publishTypingFingerprint.hookMethod { before { param -> param.result = null } }
}
