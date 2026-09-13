package io.github.nexalloy.morphe.twitter.misc.blur

import io.github.nexalloy.patch

val DisableBlur = patch(
    name = "Disable blur effects",
    description = "Disables Haze blur in the Compose UI of the new X app, while preserving the " +
            "configured fallback tint and scrim effects. Has no effect on the older X UI.",
) {
    ::hazeBlurEnabledSetterFingerprint.hookMethod {
        before { param -> param.args[0] = false }
    }
}
