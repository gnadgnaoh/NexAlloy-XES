package io.github.nexalloy.revanced.facebook.privacy

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.ad.sponsoredPoolAddMethodFingerprint
import io.github.nexalloy.revanced.facebook.hookBlockNull

/**
 * View stories without marking them seen — port of upstream HideSeenStoryHook.
 *
 * Skips the story viewer's non-critical controller setup, which is where the
 * SeenMutationController (the one that tells the server "this story was watched") is
 * configured. Same behaviour as upstream and the original mod: the whole non-critical setup
 * is skipped, not just the seen controller, so other non-critical story-viewer extras that
 * are wired up there may be missing while this is on.
 */
val HideSeenStory = patch(
    name = "View stories without marking seen",
    description = "Stops the story viewer from reporting stories as seen, so the owner does not see you in the viewer list.",
) {
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }
    runCatching { ::storyViewerNonCriticalSetupFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }
}
