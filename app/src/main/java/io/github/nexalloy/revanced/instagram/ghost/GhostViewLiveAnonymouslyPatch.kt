package io.github.nexalloy.revanced.instagram.ghost

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.tigon.TigonRequestHook
import io.github.nexalloy.revanced.shared.tigon.addTigonRequestRule

val GhostViewLiveAnonymously = patch(
    name = "View live anonymously",
    description = "Prevents Instagram from knowing you viewed a live stream " +
            "by blocking the heartbeat/viewer-count endpoint.",
) {
    addTigonRequestRule("GhostViewLiveAnonymously") { _, path ->
        path.contains("/heartbeat_and_get_viewer_count/")
    }
    dependsOn(TigonRequestHook)
}
