package io.github.nexalloy.revanced.instagram.ghost

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.tigon.TigonRequestHook
import io.github.nexalloy.revanced.shared.tigon.addTigonRequestRule

val GhostInterceptor = patch(
    name = "Ghost interceptor",
    description = "Blocks ghost mode network requests via TigonServiceLayer (screenshot, view once, story seen).",
) {
    addTigonRequestRule("GhostInterceptor") { _, path ->
        // Screenshot
        path.endsWith("/screenshot/") ||
            path.endsWith("/ephemeral_screenshot/") ||
            // View once
            path.endsWith("/item_replayed/") ||
            (path.contains("/direct") && path.endsWith("/item_seen/")) ||
            // Story seen
            path.contains("/api/v2/media/seen/")
    }
    dependsOn(TigonRequestHook)
}
