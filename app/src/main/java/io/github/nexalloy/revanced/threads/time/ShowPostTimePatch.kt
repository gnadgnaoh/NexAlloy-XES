package io.github.nexalloy.revanced.threads.time

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.time.hookExactSeconds

val ShowPostTime = patch(
    name = "Show a post's exact time",
    description = "Shows the date and time a thread or reply went up, like Oct 2, 3:45 PM, instead of how long ago.",
) {
    hookExactSeconds(::postTimestampLabelFingerprint.method)
}
