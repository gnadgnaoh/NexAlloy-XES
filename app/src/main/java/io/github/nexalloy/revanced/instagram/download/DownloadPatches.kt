package io.github.nexalloy.revanced.instagram.download

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodListFunc
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method
import kotlin.reflect.KProperty0

/**
 * Media download for Instagram, ported from InstaEclipse.
 *
 * Every entry point shares one engine ([FeedVideoDownloadHook]): it resolves Instagram's
 * obfuscated media model, pulls the CDN URL out of it and writes the file. The three
 * internal patches below install what the entry points share — the engine, the post menu and
 * the reel menu — and the feature patches pull them in with `dependsOn`, so enabling several
 * at once resolves everything only once.
 *
 * Saved files go to `Download/NexAlloy` through MediaStore, which needs no storage permission
 * because Instagram's own process owns the entry it creates.
 *
 * All lookups live in `Fingerprints.kt` and resolve through [PatchExecutor], so they are
 * cached with the rest of the module's descriptors. Where a lookup returns several candidates
 * and the right one can only be told apart by reflecting over the loaded class, that
 * narrowing happens here.
 */

/** Resolves a fingerprint's matches to [Method]s, skipping any that no longer load. */
private fun PatchExecutor.methodsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Method> =
    fingerprint.dexMethodList.mapNotNull { runCatching { it.toMethod() }.getOrNull() }

// ──────────────────────────────────────────────────────────────────────────────
// Shared engine
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the media model and starts capturing CDN URLs.
 *
 * Not listed in [io.github.nexalloy.revanced.instagram.InstagramPatches]: the download
 * patches pull it in with `dependsOn`, so it runs once and only when something needs it.
 */
val MediaDownloadCore = patch {
    FeedVideoDownloadHook().install(classLoader)

    // The dictionary holding the media payload. Absent on builds that folded it into Media,
    // where the resolver falls back to the legacy interfaces or to the getters below.
    val dictClass = runCatching { ::mediaDictClass.clazz }
        .onFailure { Logger.printDebug { "MediaDownloadCore: media dict not found, using fallbacks" } }
        .getOrNull()
    FeedVideoDownloadHook.bindMediaModel(dictClass, classLoader)

    FeedVideoDownloadHook.bindVideoVersionsGetters(methodsOf(::videoVersionsGetterMethods), classLoader)
    FeedVideoDownloadHook.bindCarouselGetter(methodsOf(::carouselMediaGetterMethods))
    FeedVideoDownloadHook.bindIsVideoMethod(methodsOf(::isVideoMethods).firstOrNull())

    // Author resolution: the User class first, then the getters matched against it.
    FeedVideoDownloadHook.bindUserClass(
        methodsOf(::userClassAnchorMethods).firstOrNull()?.declaringClass
    )
    FeedVideoDownloadHook.bindUsernameGetter(methodsOf(::userUsernameGetterMethods).firstOrNull())
    FeedVideoDownloadHook.bindMediaAuthorGetter(methodsOf(::mediaAuthorGetterMethods).firstOrNull())
    FeedVideoDownloadHook.bindDictUserGetter(methodsOf(::dictUserGetterMethods))

    ::videoVersionGetUrlMethods.dexMethodList.forEach { getUrl ->
        getUrl.hookMethod { after { FeedVideoDownloadHook.onVideoUrlReturned(it) } }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Menu integrations
//
// Download and Copy link are two rows in the same menus, so the hooks that build those rows
// are installed once here and the feature patches only decide which rows appear.
// ──────────────────────────────────────────────────────────────────────────────

/** Adds our rows to a post's three-dots menu and dispatches their clicks. */
val PostMenuIntegration = patch {
    dependsOn(MediaDownloadCore)
    PostDownloadContextMenuHook.loadMediaOptionEnum(classLoader)

    val addButton = PostDownloadContextMenuHook
        .resolveAddButtonMethod(methodsOf(::postMenuCreatorVoidMethods))
    if (addButton != null && PostDownloadContextMenuHook.canInjectRows()) {
        addButton.hookMethod {
            before { PostDownloadContextMenuHook.onAddButtonBefore(it) }
            after { PostDownloadContextMenuHook.onAddButtonAfter(it) }
        }
    }

    PostDownloadContextMenuHook
        .selectClickDispatchers(methodsOf(::postOptionClickMethods))
        .forEach { dispatcher ->
            dispatcher.hookMethod { before { PostDownloadContextMenuHook.onOptionClick(it) } }
        }

    // Newer builds filter the menu against an allowlist before rendering, which drops our
    // rows even though they were added. Absent on older builds, hence the empty-safe loop.
    methodsOf(::postMenuAllowlistMethods).firstOrNull()?.hookMethod {
        after { PostDownloadContextMenuHook.onAllowlistBuilt(it) }
    }
}

/** Adds our rows to the reel overflow menu, and unlocks Instagram's own download row. */
val ReelMenuIntegration = patch {
    dependsOn(MediaDownloadCore)

    // Instagram 437+ already carries a working native download row, kept behind two checks.
    // Forcing both is more robust than rebuilding that row and its click handler.
    methodsOf(::reelDownloadEligibleGateMethods).forEach { gate ->
        gate.hookMethod { before { if (FeatureFlags.enableReelDownload) it.result = true } }
    }
    methodsOf(::reelDownloadRestrictedGateMethods).forEach { gate ->
        gate.hookMethod { before { if (FeatureFlags.enableReelDownload) it.result = false } }
    }

    installReelOptionsListPatch()

    val builder = selectReelOptionsBuilder(methodsOf(::reelOptionsControllerMethods))
    if (builder == null) {
        Logger.printInfo { "ReelMenuIntegration: options builder not found" }
    } else {
        ReelDownloadHook.bindOptionsBuilder(builder)
        builder.hookMethod {
            after { if (FeatureFlags.enableReelDownload) ReelDownloadHook.onOptionsBuilt(it) }
        }
    }
}

/**
 * Appends our options to the list Instagram's simplified reel menu builds.
 *
 * That menu dropped DOWNLOAD entirely. The list it returns is mutable, so adding to it sends
 * the entry through the same row builder every other option uses — which means the post
 * menu's click dispatcher already covers the click.
 */
@Suppress("UNCHECKED_CAST")
private fun PatchExecutor.installReelOptionsListPatch() {
    val optionClass = runCatching {
        classLoader.loadClass("com.instagram.feed.media.mediaoption.MediaOption\$Option")
    }.getOrNull() ?: return

    val values = runCatching {
        optionClass.getMethod("values").invoke(null) as Array<*>
    }.getOrNull() ?: return

    val download = values.firstOrNull { it.toString() == "DOWNLOAD" }
    val copyLink = values.firstOrNull { it.toString() == "COPY_LINK" }
    if (download == null && copyLink == null) {
        Logger.printInfo { "ReelMenuIntegration: DOWNLOAD/COPY_LINK enum values not found" }
        return
    }

    methodsOf(::reelOptionsListBuilderMethods).forEach { listBuilder ->
        listBuilder.hookMethod {
            after { param ->
                val options = param.result as? MutableList<Any> ?: return@after
                if (FeatureFlags.enableReelDownload && download != null && download !in options) {
                    options.add(download)
                }
                if (FeatureFlags.copyMediaLink && copyLink != null && copyLink !in options) {
                    options.add(copyLink)
                }
            }
        }
    }
}

/**
 * Picks the reel options-builder out of the classes referencing the menu controller.
 *
 * The builder is `void(Media, ButtonAdder)`, and the adder is recognised by the method it
 * exposes for adding a row — matching that shape rather than a name is what survives
 * obfuscation. An overload that takes a plausible second parameter but no such adder is kept
 * as a fallback, since a wrong builder still beats no menu entry at all.
 */
private fun selectReelOptionsBuilder(anchors: List<Method>): Method? {
    var fallback: Method? = null
    for (controller in anchors.map { it.declaringClass }.distinct()) {
        for (candidate in runCatching { controller.declaredMethods }.getOrNull().orEmpty()) {
            if (candidate.returnType != Void.TYPE) continue
            val params = candidate.parameterTypes
            if (params.size < 2) continue
            if (params[0].name != "com.instagram.feed.media.Media") continue
            if (params[1].isPrimitive || params[1] == String::class.java) continue

            if (fallback == null) fallback = candidate
            if (ReelDownloadHook.hasButtonAdderMethod(params[1])) return candidate
        }
    }
    return fallback
}

// ──────────────────────────────────────────────────────────────────────────────
// Entry points
// ──────────────────────────────────────────────────────────────────────────────

val ReelDownload: Patch = patch(
    name = "Download reels",
    description = "Adds a Download entry to the reel overflow menu. Reels whose video and " +
        "audio arrive as separate streams are merged before saving.",
) {
    dependsOn(MediaDownloadCore, ReelMenuIntegration)
    FeatureFlags.enableReelDownload = true
}

val PostDownload: Patch = patch(
    name = "Download posts",
    description = "Adds a Download entry to the three-dots menu on a post. Carousels open a " +
        "sheet to save the current slide or every slide at once.",
) {
    dependsOn(MediaDownloadCore, PostMenuIntegration)
    FeatureFlags.enablePostDownload = true
}

val StoryDownload: Patch = patch(
    name = "Download stories",
    description = "Adds a download button to the story viewer. A story with music offers the " +
        "video and the still photo separately, since the photo has no soundtrack to strip.",
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.enableStoryDownload = true
    StoryDownloadHook.init(classLoader)

    // Your own story and someone else's are built by different methods, so both are hooked.
    methodsOf(::storyOptionBuilderMethods)
        .filter { StoryDownloadHook.buildsOptionLabels(it) }
        .forEach { it.hookMethod { after { param -> StoryDownloadHook.onStoryOptionsBuilt(param) } } }

    // Dispatchers receive the tapped label, so the zero-argument matches are not ours.
    methodsOf(::storyOptionClickMethods)
        .filter { it.parameterCount > 0 }
        .forEach { it.hookMethod { before { param -> StoryDownloadHook.onStoryOptionClick(param) } } }
}

val ProfilePictureDownload: Patch = patch(
    name = "Download profile pictures",
    description = "Adds a Download entry when a profile picture is opened, saving the " +
        "full-resolution image rather than the cropped thumbnail.",
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.enableProfileDownload = true
    ProfilePicDownloadHook.install()
}

val SaveInstants: Patch = patch(
    name = "Save instants",
    description = "Long-press a received instant (quicksnap) in a direct message to save it.",
    use = false,
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.saveInstants = true
    InstantSaveHook().install(classLoader)
}

val UploadInstants: Patch = patch(
    name = "Upload instants from gallery",
    description = "Adds a chip to the instant camera that sends a picture from the gallery " +
        "instead of one taken right then.",
    use = false,
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.uploadInstants = true
    InstantUploadHook().install(classLoader)
}

val CopyMediaLink: Patch = patch(
    name = "Copy media link",
    description = "Adds a Copy link entry beside Download in the post and reel menus, which " +
        "copies the direct CDN URL of the media.",
    use = false,
) {
    dependsOn(MediaDownloadCore, PostMenuIntegration, ReelMenuIntegration)
    FeatureFlags.copyMediaLink = true
}

// ──────────────────────────────────────────────────────────────────────────────
// Destination options
//
// NexAlloy's settings screen offers a switch per patch, so these two options are patches of
// their own rather than fields on a download settings screen. They change where the shared
// engine writes, so they affect every entry point above.
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
