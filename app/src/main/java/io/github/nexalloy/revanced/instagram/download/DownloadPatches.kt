package io.github.nexalloy.revanced.instagram.download

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodListFunc
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Member
import java.lang.reflect.Method
import kotlin.reflect.KProperty0

private fun PatchExecutor.methodsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Method> =
    fingerprint.dexMethodList
        .filterNot { isAnchor(it.declaredClassName, it.isConstructor) || it.isConstructor }
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }

private fun PatchExecutor.constructorsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Member> =
    fingerprint.dexMethodList
        .filter { it.isConstructor && !isAnchor(it.declaredClassName, true) }
        .mapNotNull { runCatching { it.toConstructor() }.getOrNull() }

/** Binds the media model (URLs, author, carousel) shared by every download patch. */
val MediaDownloadCore = patch {
    MediaResolver.bindClasses(classLoader)
    MediaResolver.bindVideoVersionsGetters(methodsOf(::videoVersionsGetterMethods))
    MediaResolver.bindCarouselGetter(methodsOf(::carouselMediaGetterMethods))
    MediaResolver.bindIsVideoMethod(methodsOf(::isVideoMethods).firstOrNull())
    MediaResolver.bindImageInfoGetters(methodsOf(::mediaImageInfoGetterMethods))
    MediaResolver.bindScreenImageGetters(methodsOf(::mediaScreenImageMethods))
    MediaResolver.bindUserClass(methodsOf(::userClassAnchorMethods).firstOrNull()?.declaringClass)
    methodsOf(::userUsernameGetterMethods).firstOrNull()?.let {
        it.isAccessible = true
        UserUtils.userUsernameGetter = it
    }
    MediaResolver.bindAuthorGetter(methodsOf(::mediaAuthorGetterMethods).firstOrNull())

    // The url field of VideoUrlImpl is found by constructing a probe instance; no hook needed.
    runCatching {
        val ctor = ::videoUrlConstructorFingerprint.constructor
        val urlArg = VideoUrlProbe.urlArgIndex(ctor)
        MediaResolver.bindVideoUrlModel(ctor.declaringClass, VideoUrlProbe.urlField(ctor, urlArg))
    }.onFailure { Logger.printInfo { "MediaDownloadCore: VideoUrl model not bound: $it" } }

    Logger.printInfo { "MediaDownloadCore: ${MediaResolver.describe()}" }
}

/** Download / Copy link in the post and reel overflow menus (shared by both). */
val MediaOptionsIntegration = patch {
    dependsOn(MediaDownloadCore)
    if (!MediaOptionsHook.loadOptionEnum(classLoader)) {
        Logger.printInfo { "MediaOptionsIntegration: MediaOption enum not usable" }
        return@patch
    }

    // Feed post menu: an extra row once per menu, and the allow-list that filters rows.
    MediaOptionsHook.selectAddRowMethod(methodsOf(::postMenuCreatorVoidMethods))?.hookMethod {
        before { MediaOptionsHook.beforeAddRow(it) }
        after { MediaOptionsHook.afterAddRow(it) }
    }
    methodsOf(::postMenuAllowlistMethods).firstOrNull()?.hookMethod {
        after { MediaOptionsHook.onAllowlistBuilt(it) }
    }

    // Reel menu: the option list.
    methodsOf(::reelOptionsListBuilderMethods).forEach { builder ->
        builder.hookMethod { after { MediaOptionsHook.onReelOptionsBuilt(it) } }
    }

    // Both menus dispatch through `void X(Option)`.
    MediaOptionsHook.selectDispatchers(methodsOf(::postOptionClickMethods)).forEach { dispatcher ->
        dispatcher.hookMethod { before { MediaOptionsHook.onOptionSelected(it) } }
    }
}

val ReelDownload: Patch = patch(
    name = "Download reels",
    description = "Adds a Download entry to the reel overflow menu.",
) {
    dependsOn(MediaDownloadCore, MediaOptionsIntegration)
    FeatureFlags.enableReelDownload = true
}

val PostDownload: Patch = patch(
    name = "Download posts",
    description = "Adds a Download entry to the three-dots menu on a post. Carousels open a " +
        "sheet to save the current slide or every slide at once.",
) {
    dependsOn(MediaDownloadCore, MediaOptionsIntegration)
    FeatureFlags.enablePostDownload = true
}

val StoryDownload: Patch = patch(
    name = "Download stories",
    description = "Adds a download button to the story viewer. A story with music offers the " +
        "video and the still photo separately, since the photo has no soundtrack to strip.",
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.enableStoryDownload = true

    methodsOf(::storyOptionBuilderMethods)
        .filter { StoryDownloadHook.buildsOptionLabels(it) }
        .forEach { it.hookMethod { after { param -> StoryDownloadHook.onStoryOptionsBuilt(param) } } }

    methodsOf(::storyOptionClickMethods)
        .filter { it.parameterCount > 0 }
        .forEach { it.hookMethod { before { param -> StoryDownloadHook.onStoryOptionClick(param) } } }

    // Same download arrow as the post / reel menus.
    StoryDownloadHook.resolveDownloadIcon(classLoader)
    methodsOf(::storyOptionIconMethods).forEach {
        it.hookMethod { after { param -> StoryDownloadHook.onIconResolved(param) } }
    }
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
    InstantSaveHook.hookViewer(constructorsOf(::instantViewerConstructors))
    InstantSaveHook.hookLongPress(constructorsOf(::instantLongPressConstructors), classLoader)
}

val UploadInstants: Patch = patch(
    name = "Upload instants from gallery",
    description = "Adds a chip to the instant camera that sends a picture from the gallery " +
        "instead of one taken right then.",
    use = false,
) {
    dependsOn(MediaDownloadCore)
    FeatureFlags.uploadInstants = true
    InstantUploadHook().install(classLoader, methodsOf(::instantCaptureMethods))
}

val CopyMediaLink: Patch = patch(
    name = "Copy media link",
    description = "Adds a Copy link entry beside Download in the post and reel menus, which " +
        "copies the direct CDN URL of the media.",
    use = false,
) {
    dependsOn(MediaDownloadCore, MediaOptionsIntegration)
    FeatureFlags.copyMediaLink = true
}

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
