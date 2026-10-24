package io.github.nexalloy.revanced.facebook.privacy

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.presence.hideJavaMqttPresence
import io.github.nexalloy.revanced.shared.presence.hideNativeMqttPresence
import io.github.nexalloy.revanced.shared.presence.hideUpiPresence

val HideActiveStatus = patch(
    name = "Hide your active status",
    description = "Stops people from seeing Active now for you. You still see when they're active.",
) {
    hideUpiPresence()
    hideNativeMqttPresence()
    hideJavaMqttPresence()
}
