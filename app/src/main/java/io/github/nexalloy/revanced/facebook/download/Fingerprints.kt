package io.github.nexalloy.revanced.facebook.download

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val VIDEO_DATA_SOURCE = "com.facebook.video.engine.api.VideoDataSource"
internal const val VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams"
internal const val PLAYER_ORIGIN = "com.facebook.video.common.playerorigin.PlayerOrigin"
internal const val GRAPHQL_STORY = "com.facebook.graphql.model.GraphQLStory"
internal const val GRAPHQL_STORY_ATTACHMENT = "com.facebook.graphql.model.GraphQLStoryAttachment"
internal const val TREE_JNI = "com.facebook.graphservice.tree.TreeJNI"
internal const val STORY_CARD = "com.facebook.stories.model.StoryCard"
private const val GRAPHQL_MEDIA = "com.facebook.graphql.model.GraphQLMedia"
private const val IMMUTABLE_LIST = "com.google.common.collect.ImmutableList"
private const val STORY_VIEWER_MORE_MENU =
    "com.facebook.stories.viewer.ui.buckets.regular.topbar.menu.StoryViewerMoreButtonCallback"
private const val URI = "android.net.Uri"

internal val CAN_VIEWER_DOWNLOAD = "can_viewer_download".hashCode()

val videoSourceHdUriFieldFingerprint = findFieldDirect {
    reportedField(VIDEO_DATA_SOURCE, "abrManifestContent", "videoHdUri", URI)
}

val videoSourceSdUriFieldFingerprint = findFieldDirect {
    reportedField(VIDEO_DATA_SOURCE, "abrManifestContent", "videoUri", URI)
}

val videoSourceManifestFieldFingerprint = findFieldDirect {
    reportedField(VIDEO_DATA_SOURCE, "abrManifestContent", "abrManifestContent", "java.lang.String")
}

val videoPlayerParamsVideoIdFieldFingerprint = findFieldDirect {
    reportedField(VIDEO_PLAYER_PARAMS, "videoId", "videoId", "java.lang.String")
}

val videoFeedStoryMenuFingerprint = findMethodDirect {
    findMethod {
        matcher { returnType = "void"; usingEqStrings("VideoFeedStoryMenuHelper", "DOWNLOAD_VIDEO") }
    }.single { m ->
        val p = m.paramTypeNames
        p.size >= 3 && p[0] == "android.view.Menu" && p[1] == "android.view.View" && p.last() == PLAYER_ORIGIN
    }
}

val postMenuImplementationsFingerprint = findMethodListDirect {
    val menu = videoFeedStoryMenuFingerprint()
    findMethod {
        matcher { name = menu.name; returnType = "void"; paramTypes(menu.paramTypeNames) }
    }.filter { it.isMethod && !Modifier.isAbstract(it.modifiers) }
}

val postMenuItemFieldFingerprint = findFieldDirect {
    val props = videoFeedStoryMenuFingerprint().paramTypeNames[2]
    val fields = getClassData(props)!!.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object" }
    fields.singleOrNull() ?: error("Expected 1 Object field on $props, found ${fields.size}")
}

private fun DexKitBridge.graphQlGetter(owner: String, returnType: String, field: String): MethodData {
    val getters = findMethod {
        matcher { declaredClass(owner); this.returnType = returnType; paramCount = 0; usingNumbers(field.hashCode()) }
    }.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
    return getters.singleOrNull() ?: error("Expected 1 getter of $owner reading \"$field\", found ${getters.size}")
}

val storyAttachmentsGetterFingerprint = findMethodDirect { graphQlGetter(GRAPHQL_STORY, IMMUTABLE_LIST, "attachments") }
val attachedStoryGetterFingerprint = findMethodDirect { graphQlGetter(GRAPHQL_STORY, GRAPHQL_STORY, "attached_story") }
val attachmentMediaGetterFingerprint = findMethodDirect { graphQlGetter(GRAPHQL_STORY_ATTACHMENT, GRAPHQL_MEDIA, "media") }

val photoSaveGateMethodsFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            usingNumbers(CAN_VIEWER_DOWNLOAD)
            usingEqStrings("Video")
            addInvoke { declaredClass(TREE_JNI); name = "getBooleanValue" }
        }
    }.filter { method ->
        val items = DexStream.of(method)?.items ?: return@filter false
        items.indices.any { at ->
            if ((items[at] as? DexStream.Item.Num)?.int != CAN_VIEWER_DOWNLOAD) return@any false
            val readAt = (at + 1 until items.size).firstOrNull { items[it] is DexStream.Item.Call } ?: return@any false
            val read = (items[readAt] as DexStream.Item.Call).callee
            read.name == "getBooleanValue" && read.className == TREE_JNI &&
                (readAt + 1 until minOf(items.size, readAt + 12)).any { (items[it] as? DexStream.Item.Str)?.value == "Video" }
        }
    }
}

val newSavePhotoActionFingerprint = findMethodDirect {
    findMethod {
        matcher { name = "newSavePhotoAction"; returnType = "android.view.MenuItem\$OnMenuItemClickListener"; paramCount = 1 }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val saveEncodedImageFingerprint = findMethodDirect {
    findMethod { matcher { name = "subscribeToEncodedImage"; returnType = "void" } }.single { !Modifier.isStatic(it.modifiers) }
}

private class CdnResize(val modifier: FieldData, val resize: MethodData, val options: MethodData, val scale: FieldData, val tier: FieldData)

private fun DexKitBridge.cdnResize(): CdnResize {
    val save = saveEncodedImageFingerprint()
    val items = DexStream.of(save)?.items ?: error("Could not read ${save.descriptor}")
    val resizes = items.withIndex().filter { (_, item) ->
        val call = item as? DexStream.Item.Call ?: return@filter false
        val p = call.callee.paramTypeNames
        call.isInterface && call.callee.returnTypeName == URI && p.size == 2 && p[0] == URI
    }
    val (resizeAt, resizeItem) = resizes.singleOrNull() ?: error("Expected 1 address modifier call, found ${resizes.size}")
    val resize = (resizeItem as DexStream.Item.Call).callee
    val modifier = (resizeAt - 1 downTo 0).map { items[it] }.firstOrNull { it is DexStream.Item.Field && it.isStaticRead }
        .let { it as? DexStream.Item.Field }?.field?.takeIf { it.typeName == resize.className }
        ?: error("The address modifier isn't read from a static field right before the call")
    val optionsType = resize.paramTypeNames[1]
    val inits = items.withIndex().filter { (at, item) ->
        val call = (item as? DexStream.Item.Call)?.callee ?: return@filter false
        val p = call.paramTypeNames
        at < resizeAt && call.isConstructor && call.className == optionsType && p.size == 4 && p[2] == "int" && p[3] == "int"
    }
    val (initAt, initItem) = inits.singleOrNull() ?: error("Expected 1 $optionsType constructor, found ${inits.size}")
    val options = (initItem as DexStream.Item.Call).callee
    val reads = (initAt - 1 downTo 0).map { items[it] }.filterIsInstance<DexStream.Item.Field>().filter { it.isStaticRead }.take(2)
    val tier = reads.getOrNull(0)?.field?.takeIf { it.typeName == options.paramTypeNames[1] } ?: error("options tier not found")
    val scale = reads.getOrNull(1)?.field?.takeIf { it.typeName == options.paramTypeNames[0] } ?: error("options scale not found")
    return CdnResize(modifier, resize, options, scale, tier)
}

val cdnModifierFieldFingerprint = findFieldDirect { cdnResize().modifier }
val cdnResizeMethodFingerprint = findMethodDirect { cdnResize().resize }
val cdnOptionsConstructorFingerprint = findMethodDirect { cdnResize().options }
val cdnScaleFieldFingerprint = findFieldDirect { cdnResize().scale }
val cdnTierFieldFingerprint = findFieldDirect { cdnResize().tier }

private const val SAVE_STORY_ATTEMPTED = "save_story_attempted"

val storySaveActionClassFingerprint = findClassDirect {
    val menu = getClassData(STORY_VIEWER_MORE_MENU) ?: error("No $STORY_VIEWER_MORE_MENU in this build")
    val candidates = menu.typesCreated().mapNotNull { getClassData(it) }
    val tables = findMethod {
        matcher { usingEqStrings(SAVE_STORY_ATTEMPTED); returnType = "java.lang.String"; paramTypes("int") }
    }.filter { Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
    fun actionsWhere(test: (MethodData) -> Boolean) = candidates.filter { cls ->
        cls.methods.any { m -> m.isMethod && m.returnTypeName == "void" && m.paramTypeNames.size == 1 && test(m) }
    }
    val actions = actionsWhere { SAVE_STORY_ATTEMPTED in it.usingStrings }
        .ifEmpty { actionsWhere { m -> m.invokes.any { it.descriptor in tables } } }
    actions.singleOrNull() ?: error("Expected 1 save-story action, found ${actions.size}")
}

val storySaveTapFingerprint = findMethodDirect {
    val taps = storySaveActionClassFingerprint().methods.filter { it.isMethod && it.returnTypeName == "void" && it.paramTypeNames.size == 1 }
    taps.singleOrNull() ?: error("Expected 1 tap handler on the save-story action, found ${taps.size}")
}

val storyMenuBuilderFingerprint = findMethodDirect {
    val action = storySaveActionClassFingerprint().name
    val builders = getClassData(STORY_VIEWER_MORE_MENU)!!.methods.filter { m -> m.invokes.any { it.isConstructor && it.className == action } }
    builders.singleOrNull() ?: error("Expected 1 story menu builder, found ${builders.size}")
}

val storySaveCapabilityFingerprint = findMethodDirect {
    val stream = DexStream.of(storyMenuBuilderFingerprint()) ?: error("Could not read the story menu builder")
    val asks = stream.calls().filter { call ->
        val callee = call.callee
        call.isVirtual && callee.returnTypeName == "boolean" && callee.paramTypeNames.isEmpty() &&
            !callee.className.startsWith("java.") && !callee.className.startsWith("android.")
    }.map { it.callee }.distinctBy { it.descriptor }
    asks.singleOrNull() ?: error("Expected 1 save capability check, found ${asks.size}")
}

val storySaveCapabilityCacheFieldFingerprint = findFieldDirect {
    val owner = storySaveCapabilityFingerprint().className
    val stream = DexStream.of(storyMenuBuilderFingerprint()) ?: error("Could not read the story menu builder")
    stream.items.filterIsInstance<DexStream.Item.Field>().map { it.field }
        .first { it.className == owner && it.typeName == "java.lang.Boolean" }
}

val storyMoreMenuFingerprint = findMethodDirect {
    val builder = storyMenuBuilderFingerprint().descriptor
    val menus = getClassData(STORY_VIEWER_MORE_MENU)!!.methods.filter { m ->
        Modifier.isStatic(m.modifiers) && m.paramTypeNames.contains(STORY_VIEWER_MORE_MENU) && m.invokes.any { it.descriptor == builder }
    }
    menus.singleOrNull() ?: error("Expected 1 story more menu, found ${menus.size}")
}

val storyMenuRowClassFingerprint = findClassDirect {
    val action = storySaveActionClassFingerprint().name
    val rows = storyMenuBuilderFingerprint().invokes.filter { it.isConstructor && it.className != action && !it.className.startsWith("java.") }
        .map { it.className }.distinct()
    getClassData(rows.singleOrNull() ?: error("Expected 1 story menu row class, found $rows"))!!
}

private const val CLEAR_MODE_ROW = "fds_control_clear_mode"
private const val OWN_DOWNLOAD_ROW = "fds_control_download_video"

val reelMoreSheetBuilderFingerprint = findMethodDirect {
    val builders = findMethod { matcher { usingEqStrings(CLEAR_MODE_ROW); returnType = "void" } }.filter { Modifier.isStatic(it.modifiers) }
    builders.singleOrNull() ?: error("Expected 1 builder of \"$CLEAR_MODE_ROW\", found ${builders.size}")
}

val reelPlayerStateLookupFingerprint = findMethodDirect {
    val builder = reelMoreSheetBuilderFingerprint()
    val parameters = builder.paramTypeNames.toSet()
    val lookups = builder.invokes.filter { call ->
        call.paramTypeNames == listOf(PLAYER_ORIGIN, "java.lang.String") && call.className in parameters &&
            getClassData(call.returnTypeName)?.fields?.any { it.typeName == VIDEO_PLAYER_PARAMS } == true
    }.distinctBy { it.descriptor }
    lookups.singleOrNull() ?: error("Expected 1 player state lookup, found ${lookups.size}")
}

val reelRowConstructorFingerprint = findMethodDirect {
    val stream = DexStream.of(reelMoreSheetBuilderFingerprint()) ?: error("Could not read the More sheet builder")
    val idAt = stream.indexOfString(CLEAR_MODE_ROW)
    check(idAt >= 0) { "The More sheet builder no longer names \"$CLEAR_MODE_ROW\"" }
    stream.items.drop(idAt).filterIsInstance<DexStream.Item.Call>().map { it.callee }
        .firstOrNull { it.isConstructor && "java.lang.CharSequence" in it.paramTypeNames }
        ?: error("No row is built after \"$CLEAR_MODE_ROW\"")
}

val reelIconWrapFingerprint = findMethodDirect {
    val rowParameters = reelRowConstructorFingerprint().paramTypeNames.toSet()
    val wraps = reelMoreSheetBuilderFingerprint().invokes.filter { call ->
        Modifier.isStatic(call.modifiers) && call.returnTypeName in rowParameters && call.paramTypeNames.size == 1 &&
            getClassData(call.paramTypeNames.single())?.superClass?.name == "java.lang.Enum"
    }.distinctBy { it.descriptor }
    wraps.singleOrNull() ?: error("Expected 1 icon wrapper, found ${wraps.size}")
}

val reelDownloadIconFieldFingerprint = findFieldDirect {
    val iconEnum = reelIconWrapFingerprint().paramTypeNames.single()
    findMethod { matcher { usingEqStrings(OWN_DOWNLOAD_ROW) } }.firstNotNullOfOrNull { method ->
        DexStream.of(method)?.items?.filterIsInstance<DexStream.Item.Field>()
            ?.firstOrNull { it.isStaticRead && it.field.typeName == iconEnum }?.field
    } ?: error("\"$OWN_DOWNLOAD_ROW\" draws no icon of $iconEnum")
}
