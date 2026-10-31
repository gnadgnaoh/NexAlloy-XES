package io.github.nexalloy.revanced.facebook.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.ad.sponsoredPoolAddMethodFingerprint
import io.github.nexalloy.revanced.facebook.hookBlockNull
import org.luckypray.dexkit.wrap.DexMethod

val HideSeenStory = patch(
    name = "View stories without marking seen",
    description = "Stops the story viewer from reporting stories as seen, so the owner does not see you in the viewer list.",
) {
    ::sponsoredPoolAddMethodFingerprint.method

    fun hookAll(list: List<DexMethod>): Int = list.count { dm -> hookBlockNull(dm.toMethod()) }

    val sender = hookAll(::storySeenMutationSenderFingerprint.dexMethodList)
    if (sender > 0) {
        Logger.printInfo { "HideSeenStory: blocked seen mutation sender ($sender)" }
        return@patch
    }

    val flush = hookAll(::storySeenHelperFlushFingerprint.dexMethodList)
    if (flush > 0) {
        Logger.printInfo { "HideSeenStory: sender not found, blocked seen helper flush ($flush)" }
        return@patch
    }

    val setup = hookAll(::storyViewerNonCriticalSetupFingerprint.dexMethodList)
    check(setup > 0) { "HideSeenStory: no seen sender, flush or viewer setup method found" }
    Logger.printInfo {
        "HideSeenStory: seen pipeline not found, fell back to blocking whole setup ($setup) " +
            "- story reply-bar extras such as the + button may be missing"
    }
}
