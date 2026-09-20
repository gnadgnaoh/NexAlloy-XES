package io.github.nexalloy.revanced.instagram.download

import android.os.Build
import app.morphe.extension.shared.Logger
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.patch

/**
 * Media download for Instagram, ported from InstaEclipse.
 *
 * Every entry point shares one engine ([FeedVideoDownloadHook]): it resolves Instagram's
 * obfuscated media model, pulls the CDN URL out of it and writes the file. [MediaDownloadCore]
 * installs that engine once and the feature patches depend on it, so enabling two of them does
 * not resolve the model twice.
 *
 * Saved files go to `Download/NexAlloy` through MediaStore, which needs no storage permission
 * because Instagram's own process owns the entry it creates.
 */

// ──────────────────────────────────────────────────────────────────────────────
// Shared engine
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the media model and installs the CDN-URL capture hook.
 *
 * Not listed in [io.github.nexalloy.revanced.instagram.InstagramPatches]: the download
 * patches pull it in with `dependsOn`, so it runs exactly once and only when something
 * actually needs it.
 */
val MediaDownloadCore = patch {
    // Descriptors resolved by the ported hooks are cached per Instagram version, so the
    // DexKit search only runs again after the app updates.
    runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION") info.versionCode.toString()
        }
        DexKitCache.init(appContext, version)
    }.onFailure { Logger.printException({ "MediaDownloadCore: DexKit cache init failed" }, it) }

    FeedVideoDownloadHook().install(classLoader)
    withDexKit { bridge ->
        FeedVideoDownloadHook.installVideoUrlCaptureHook(bridge, classLoader)
    }
}

/** Installs [MediaDownloadCore], then runs [block]. */
private fun PatchExecutor.withCore(block: PatchExecutor.() -> Unit) {
    dependsOn(MediaDownloadCore)
    block()
}

// ──────────────────────────────────────────────────────────────────────────────
// Entry points
// ──────────────────────────────────────────────────────────────────────────────

val ReelDownload: Patch = patch(
    name = "Download reels",
    description = "Adds a Download entry to the reel overflow menu. Reels whose video and " +
        "audio arrive as separate streams are merged before saving.",
) {
    withCore {
        FeatureFlags.enableReelDownload = true
        withDexKit { bridge -> ReelDownloadHook().install(bridge, classLoader) }
    }
}

val PostDownload: Patch = patch(
    name = "Download posts",
    description = "Adds a Download entry to the three-dots menu on a post. Carousels open a " +
        "sheet to save the current slide or every slide at once.",
) {
    withCore {
        FeatureFlags.enablePostDownload = true
        withDexKit { bridge -> PostDownloadContextMenuHook().install(bridge, classLoader) }
    }
}

val StoryDownload: Patch = patch(
    name = "Download stories",
    description = "Adds a download button to the story viewer. A story with music offers the " +
        "video and the still photo separately, since the photo has no soundtrack to strip.",
) {
    withCore {
        FeatureFlags.enableStoryDownload = true
        withDexKit { bridge -> StoryDownloadHook().install(bridge, classLoader) }
    }
}

val ProfilePictureDownload: Patch = patch(
    name = "Download profile pictures",
    description = "Adds a Download entry when a profile picture is opened, saving the " +
        "full-resolution image rather than the cropped thumbnail.",
) {
    withCore {
        FeatureFlags.enableProfileDownload = true
        ProfilePicDownloadHook.install()
    }
}

val SaveInstants: Patch = patch(
    name = "Save instants",
    description = "Long-press a received instant (quicksnap) in a direct message to save it.",
    use = false,
) {
    withCore {
        FeatureFlags.saveInstants = true
        InstantSaveHook().install(classLoader)
    }
}

val UploadInstants: Patch = patch(
    name = "Upload instants from gallery",
    description = "Adds a chip to the instant camera that sends a picture from the gallery " +
        "instead of one taken right then.",
    use = false,
) {
    withCore {
        FeatureFlags.uploadInstants = true
        InstantUploadHook().install(classLoader)
    }
}

val CopyMediaLink: Patch = patch(
    name = "Copy media link",
    description = "Adds a Copy link entry next to Download that copies the direct CDN URL of " +
        "the media. Needs the post or reel download patch for the menu entry to appear.",
    use = false,
) {
    FeatureFlags.copyMediaLink = true
}

// ──────────────────────────────────────────────────────────────────────────────
// Destination options
//
// NexAlloy's settings screen only offers a switch per patch, so these two options are
// patches of their own rather than fields on a download settings screen. They change
// where the shared engine writes, so they affect every entry point above.
// ──────────────────────────────────────────────────────────────────────────────

val DownloadIntoUsernameFolders: Patch = patch(
    name = "Sort downloads into username folders",
    description = "Saves each file into a sub-folder named after the account it came from, " +
        "instead of dropping everything into one folder.",
    use = false,
) {
    FeatureFlags.downloaderUsernameFolder = true
}

val TimestampDownloadedFilenames: Patch = patch(
    name = "Add a timestamp to download filenames",
    description = "Appends the date and time to every saved filename, so saving the same post " +
        "twice keeps both copies.",
    use = false,
) {
    FeatureFlags.downloaderAddTimestamp = true
}
