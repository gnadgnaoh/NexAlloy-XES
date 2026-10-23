package io.github.nexalloy.revanced.instagram.download

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodListFunc
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.profile.ProfileHeaderHook
import java.lang.reflect.Member
import java.lang.reflect.Method
import kotlin.reflect.KProperty0

private fun PatchExecutor.methodsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Method> =
    fingerprint.dexMethodList
        .filter { it.isMethod }
        .map { it.toMethod() }

private fun PatchExecutor.declaringClassOf(fingerprint: KProperty0<FindMethodListFunc>): Class<*>? =
    fingerprint.dexMethodList
        .filterNot { isAnchor(it.declaredClassName, it.isConstructor) }
        .sortedBy { !it.isMethod }
        .firstOrNull()
        ?.let { classLoader.loadClass(it.declaredClassName) }

private fun PatchExecutor.constructorsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Member> =
    fingerprint.dexMethodList
        .filter { it.isConstructor && !isAnchor(it.declaredClassName, true) }
        .map { it.toConstructor() }

val MediaDownloadCore = patch(name = "<MediaDownloadCore>") {
    MediaResolver.bindClasses(classLoader)
    MediaResolver.bindVideoVersionsGetters(methodsOf(::videoVersionsGetterMethods))
    MediaResolver.bindCarouselGetter(methodsOf(::carouselMediaGetterMethods))
    MediaResolver.bindIsVideoMethod(methodsOf(::isVideoMethods).firstOrNull())
    MediaResolver.bindImageInfoGetters(methodsOf(::mediaImageInfoGetterMethods))
    MediaResolver.bindScreenImageGetters(methodsOf(::mediaScreenImageMethods))
    MediaResolver.bindUserClass(declaringClassOf(::userClassAnchorMethods))
    methodsOf(::userUsernameGetterMethods).firstOrNull()?.let {
        it.isAccessible = true
        UserUtils.userUsernameGetter = it
    }
    MediaResolver.bindAuthorGetter(methodsOf(::mediaAuthorGetterMethods).firstOrNull())

    val ctor = ::videoUrlConstructorFingerprint.constructor
    val urlArg = VideoUrlProbe.urlArgIndex(ctor)
    MediaResolver.bindVideoUrlModel(ctor.declaringClass, VideoUrlProbe.urlField(ctor, urlArg))

    runCatching { MediaResolver.bindDashManifestGetter(::mediaDashManifestGetterFingerprint.method) }
        .onFailure { Logger.printInfo { "MediaDownloadCore: no DASH manifest getter: ${it.message}" } }
    runCatching {
        AudioSource.bind(
            classLoader,
            listOf(::mediaMusicMetadataGetterFingerprint.method, ::mediaClipsMetadataGetterFingerprint.method),
            ::musicTrackGetterFingerprint.method, ::musicConsumptionGetterFingerprint.method,
            ::trackUrlGetterFingerprint.method, ::musicStartGetterFingerprint.method, ::musicLengthGetterFingerprint.method,
        )
    }.onFailure { Logger.printInfo { "MediaDownloadCore: no music model getters: ${it.message}" } }

    Logger.printInfo { "MediaDownloadCore: ${MediaResolver.describe()}" }
}

val MediaOptionsIntegration = patch(name = "<MediaOptionsIntegration>") {
    dependsOn(MediaDownloadCore)
    check(MediaOptionsHook.loadOptionEnum(classLoader)) {
        "MediaOptionsIntegration: MediaOption enum has neither DOWNLOAD nor COPY_LINK"
    }

    (MediaOptionsHook.selectAddRowMethod(methodsOf(::postMenuCreatorVoidMethods))
        ?: error("post menu add-row method not found")).hookMethod {
        before { MediaOptionsHook.beforeAddRow(it) }
        after { MediaOptionsHook.afterAddRow(it) }
    }
    methodsOf(::postMenuAllowlistMethods).first().hookMethod {
        after { MediaOptionsHook.onAllowlistBuilt(it) }
    }

    runCatching { ::reelMenuRowAdderFingerprint.method }.getOrNull()?.let { adder ->
        MediaOptionsHook.bindReelRowAdder(adder)
        adder.hookMethod { after { MediaOptionsHook.afterReelRowAdded(it) } }
    }

    methodsOf(::reelOptionsListBuilderMethods).forEach { builder ->
        builder.hookMethod { after { MediaOptionsHook.onReelOptionsBuilt(it) } }
    }

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

    StoryDownloadHook.resolveDownloadIcon(classLoader)
    methodsOf(::storyOptionIconMethods).forEach {
        it.hookMethod { after { param -> StoryDownloadHook.onIconResolved(param) } }
    }
}

val ProfilePictureDownload: Patch = patch(
    name = "Download profile pictures",
    description = "Long-press an opened profile picture to view it full size, download it, or copy the " +
        "username or bio.",
) {
    dependsOn(MediaDownloadCore)
    runCatching { dependsOn(ProfileHeaderHook) }.onFailure { Logger.printInfo { "ProfilePictureDownload: no profile header: ${it.message}" } }
    FeatureFlags.enableProfileDownload = true
    ProfilePicDownloadHook.install(classLoader)
}

val SaveInstants: Patch = patch(
    name = "Save instants",
    description = "Long-press a received instant (quicksnap) in a direct message to save it.",
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
