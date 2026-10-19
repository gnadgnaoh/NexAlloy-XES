package io.github.nexalloy.revanced.instagram.feed

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val ShowHiddenLikeCounts = patch(
    name = "Show hidden like counts",
    description = "Shows how many likes a post or reel has when its owner hid the count, " +
        "as long as Instagram's server still sends the number.",
) {
    ::likeCountHiddenDeciderFingerprint.method.hookMethod {
        before { param -> if (param.args[2] == true) param.args[2] = false }
    }
}
