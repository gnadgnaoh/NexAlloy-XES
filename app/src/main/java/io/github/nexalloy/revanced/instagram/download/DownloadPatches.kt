package io.github.nexalloy.revanced.instagram.download

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodListFunc
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method
import kotlin.reflect.KProperty0

private fun PatchExecutor.methodsOf(fingerprint: KProperty0<FindMethodListFunc>): List<Method> =
    fingerprint.dexMethodList
        .filterNot { isAnchor(it.declaredClassName, it.isConstructor) }
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }

val MediaDownloadCore = patch {
    FeedVideoDownloadHook().install(classLoader)
    FeedVideoDownloadHook.bindMediaModel(null, classLoader)
    FeedVideoDownloadHook.bindVideoVersionsGetters(methodsOf(::videoVersionsGetterMethods), classLoader)
    FeedVideoDownloadHook.bindCarouselGetter(methodsOf(::carouselMediaGetterMethods))
    FeedVideoDownloadHook.bindIsVideoMethod(methodsOf(::isVideoMethods).firstOrNull())
    FeedVideoDownloadHook.bindUserClass(
        methodsOf(::userClassAnchorMethods).firstOrNull()?.declaringClass
    )
    FeedVideoDownloadHook.bindUsernameGetter(methodsOf(::userUsernameGetterMethods).firstOrNull())
    FeedVideoDownloadHook.bindMediaAuthorGetter(methodsOf(::mediaAuthorGetterMethods).firstOrNull())
    FeedVideoDownloadHook.bindDictUserGetter(methodsOf(::dictUserGetterMethods))

    methodsOf(::videoVersionGetUrlMethods).forEach { getUrl ->
        getUrl.hookMethod { after { FeedVideoDownloadHook.onVideoUrlReturned(it) } }
    }

    runCatching {
        val ctor = ::videoUrlConstructorFingerprint.constructor
        val urlArg = VideoUrlProbe.urlArgIndex(ctor)
        FeedVideoDownloadHook.bindVideoUrlModel(ctor.declaringClass, VideoUrlProbe.urlField(ctor, urlArg))
        ctor.hookMethod { after { FeedVideoDownloadHook.onVideoUrlConstructed(it.args[urlArg]) } }
    }.onFailure { Logger.printInfo { "MediaDownloadCore: VideoUrlImpl model not bound: $it" } }
}

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

    methodsOf(::postMenuAllowlistMethods).firstOrNull()?.hookMethod {
        after { PostDownloadContextMenuHook.onAllowlistBuilt(it) }
    }
}

val ReelMenuIntegration = patch {
    dependsOn(MediaDownloadCore)
    
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

    methodsOf(::storyOptionBuilderMethods)
        .filter { StoryDownloadHook.buildsOptionLabels(it) }
        .forEach { it.hookMethod { after { param -> StoryDownloadHook.onStoryOptionsBuilt(param) } } }

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
