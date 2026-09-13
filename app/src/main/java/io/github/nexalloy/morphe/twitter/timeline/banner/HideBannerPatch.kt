package io.github.nexalloy.morphe.twitter.timeline.banner

import io.github.nexalloy.patch

val HideBanner = patch(
    name = "Hide Banner",
    description = "Hides the \"new posts\" banner shown at the top of the timeline.",
) {
    ShowInstructionsStateConstructorFingerprint.hookMethod {
        before { param ->
            param.args[1] = false
            param.args[5] = false
        }
    }
}
