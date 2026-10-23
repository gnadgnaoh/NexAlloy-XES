package io.github.nexalloy.revanced.instagram.download

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val MEDIA_CLASS = "com.instagram.feed.media.Media"
internal const val MEDIA_EXT_CLASS = "com.instagram.feed.media.MediaExtKt"
internal const val USER_CLASS = "com.instagram.user.model.User"
internal const val REEL_ITEM_CLASS = "com.instagram.model.reels.ReelItem"
internal const val VIDEO_URL_CLASS = "com.instagram.model.mediasize.VideoUrlImpl"
internal const val IMAGE_INFO_CLASS = "com.instagram.model.mediasize.ImageInfo"
internal const val EXTENDED_IMAGE_URL_CLASS = "com.instagram.model.mediasize.ExtendedImageUrl"
internal const val IMAGE_URL_CLASS = "com.instagram.common.typedurl.ImageUrl"
internal const val MEDIA_OPTION_CLASS = "com.instagram.feed.media.mediaoption.MediaOption\$Option"
internal const val QUICKSNAP_VM_CLASS = "com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel"
private const val QUICKSNAP_VIEWER_PACKAGE = "com.instagram.quicksnap.viewer.compose"

private const val CONTEXT = "android.content.Context"
private const val STRING = "java.lang.String"
private const val CHAR_SEQUENCE = "java.lang.CharSequence"
private const val DISMISS_LISTENER = "android.content.DialogInterface\$OnDismissListener"
private const val BITMAP = "android.graphics.Bitmap"
private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

private fun DexKitBridge.alwaysMatches(): List<MethodData> =
    findMethod {
        matcher {
            declaredClass(MEDIA_CLASS)
            name = "<init>"
        }
    }

internal fun isAnchor(declaringClassName: String, isConstructor: Boolean): Boolean =
    isConstructor && declaringClassName == MEDIA_CLASS

private fun DexKitBridge.classNamed(name: String): ClassData? =
    findClass { matcher { className(name) } }.firstOrNull()

private fun DexKitBridge.userClassName(): String =
    findMethod { matcher { usingStrings("username_missing_during_update") } }
        .firstOrNull()?.declaredClassName ?: USER_CLASS

val videoUrlConstructorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            usingStrings(listOf("VideoUrl object with null url"), StringMatchType.Contains)
        }
    }.single()
}

val videoVersionsGetterMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramCount = 0
            returnType = "java.util.List"
            usingEqStrings("video_versions")
        }
    }
}

val carouselMediaGetterMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramCount = 0
            returnType = "java.util.List"
            usingEqStrings("carousel_media")
        }
    }
}

val isVideoMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            returnType = "void"
            usingStrings("asl_session_id", "is_video", "is_carousel")
        }
    }.mapNotNull { wrapper ->
        var lastMediaFlag: MethodData? = null
        var picked: MethodData? = null
        for (insn in wrapper.instructions) {
            val invoked = insn.methodRef
            if (invoked != null && invoked.paramCount == 0 && invoked.returnTypeName == "boolean" &&
                invoked.declaredClassName == MEDIA_CLASS
            ) {
                lastMediaFlag = invoked
            } else if (insn.string == "is_video") {
                picked = lastMediaFlag
                break
            }
        }
        picked
    }.distinctBy { it.descriptor }
}

val mediaImageInfoGetterMethods = findMethodListDirect {
    val static = findMethod {
        matcher {
            paramTypes(MEDIA_CLASS)
            returnType = IMAGE_INFO_CLASS
        }
    }
    val instance = findMethod {
        matcher {
            declaredClass(MEDIA_CLASS)
            paramCount = 0
            returnType = IMAGE_INFO_CLASS
        }
    }
    alwaysMatches() + (static + instance).distinctBy { it.descriptor }
}

val mediaScreenImageMethods = findMethodListDirect {
    alwaysMatches() + listOf(EXTENDED_IMAGE_URL_CLASS, STRING).flatMap { ret ->
        findMethod {
            matcher {
                declaredClass(MEDIA_EXT_CLASS)
                paramTypes(CONTEXT, MEDIA_CLASS)
                returnType = ret
            }
        }
    }
}

val userClassAnchorMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }
}

val userUsernameGetterMethods = findMethodListDirect {
    val userClassName = userClassName()
    val readsFullName = findMethod {
        matcher {
            declaredClass(userClassName)
            returnType = STRING
            paramCount = 0
            usingNumbers(-265713450, "full_name".hashCode())
        }
    }.map { it.descriptor }.toSet()

    alwaysMatches() + findMethod {
        matcher {
            declaredClass(userClassName)
            returnType = STRING
            paramCount = 0
            usingNumbers(-265713450)
        }
    }.sortedBy { it.descriptor in readsFullName }
}

val mediaAuthorGetterMethods = findMethodListDirect {
    val userClassName = userClassName()
    alwaysMatches() + findMethod {
        matcher {
            declaredClass(MEDIA_CLASS)
            paramCount = 0
            returnType = userClassName
            usingNumbers(3599307)
        }
    }
}

val postMenuCreatorVoidMethods = findMethodListDirect {
    val anchor = alwaysMatches()
    val creator = findClass {
        matcher { usingStrings("MediaOptionsOverflowMenuCreator") }
    }.firstOrNull()?.name ?: return@findMethodListDirect anchor

    anchor + findMethod {
        matcher {
            declaredClass(creator)
            returnType = "void"
        }
    }
}

val postOptionClickMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            returnType = "void"
            paramTypes(MEDIA_OPTION_CLASS)
        }
    }
}

val postMenuAllowlistMethods = findMethodListDirect {
    val option = "L${MEDIA_OPTION_CLASS.replace('.', '/')};"
    alwaysMatches() + findMethod {
        matcher {
            paramTypes("boolean")
            returnType = "java.util.List"
            addUsingField("$option->REPORT:$option")
            addUsingField("$option->HIDE_OPTIONS:$option")
            addUsingField("$option->GEN_AI_INFO:$option")
        }
    }
}

val reelOptionsListBuilderMethods = findMethodListDirect {
    val option = "L${MEDIA_OPTION_CLASS.replace('.', '/')};"
    alwaysMatches() + findMethod {
        matcher {
            returnType = "java.util.ArrayList"
            addUsingField("$option->PLAYBACK_CONTROLS:$option")
            addUsingField("$option->UNSAVE:$option")
        }
    }
}

private const val STORY_OPTIONS_ANCHOR = "[INTERNAL] Pause Playback"

private fun DexKitBridge.storyOptionsOwners(): List<ClassData> =
    findMethod { matcher { usingStrings(STORY_OPTIONS_ANCHOR) } }
        .mapNotNull { it.declaredClass }
        .distinctBy { it.name }

val storyOptionBuilderMethods = findMethodListDirect {
    alwaysMatches() + storyOptionsOwners().flatMap { owner ->
        owner.methods.filter { m ->
            if (m.isConstructor || m.isStaticInitializer) return@filter false
            when (m.returnTypeName) {
                "$CHAR_SEQUENCE[]" -> true
                "java.util.ArrayList" ->
                    Modifier.isStatic(m.modifiers) && m.paramTypeNames.firstOrNull() == owner.name
                else -> false
            }
        }
    }.distinctBy { it.descriptor }
}

val storyOptionClickMethods = findMethodListDirect {
    val byShape = storyOptionsOwners().flatMap { owner ->
        owner.methods.filter { m ->
            val p = m.paramTypeNames
            m.returnTypeName == "void" && Modifier.isStatic(m.modifiers) && p.size >= 2 &&
                p.first() == DISMISS_LISTENER && p.last() == CHAR_SEQUENCE
        }
    }
    val legacy = findMethod {
        matcher {
            returnType = "void"
            usingStrings(STORY_OPTIONS_ANCHOR)
        }
    }.filter { m -> m.paramTypeNames.any { it == CHAR_SEQUENCE } }
    alwaysMatches() + (byShape + legacy).distinctBy { it.descriptor }
}

val storyOptionIconMethods = findMethodListDirect {
    alwaysMatches() + storyOptionsOwners().flatMap { owner ->
        owner.methods.filter { m ->
            Modifier.isStatic(m.modifiers) && m.returnTypeName == "int" &&
                m.paramTypeNames == listOf(owner.name, CHAR_SEQUENCE)
        }
    }
}

val instantViewerConstructors = findMethodListDirect {
    val holdsMedia = HashMap<String, Boolean>()
    fun DexKitBridge.typeHoldsMedia(type: String): Boolean = holdsMedia.getOrPut(type) {
        if (type == MEDIA_CLASS) return@getOrPut true
        if (type.startsWith("java.") || type.startsWith("android") || type.startsWith("kotlin")) {
            return@getOrPut false
        }
        classNamed(type)?.fields?.any { it.typeName == MEDIA_CLASS } == true
    }

    alwaysMatches() + findClass { searchPackages(QUICKSNAP_VIEWER_PACKAGE) }
        .filter { it.name.contains("QuickSnapMediaViewerScreen\$") }
        .flatMap { lambda ->
            lambda.methods.filter { ctor ->
                ctor.isConstructor && ctor.paramTypeNames.any { typeHoldsMedia(it) }
            }
        }
}

val instantLongPressConstructors = findMethodListDirect {
    alwaysMatches() + findClass { searchPackages(QUICKSNAP_VIEWER_PACKAGE) }
        .filter { it.name.contains("handleTapAndLongPress") }
        .filter { c -> c.fields.any { it.typeName == FUNCTION1 } }
        .flatMap { c -> c.methods.filter { it.isConstructor } }
}

val instantCaptureMethods = findMethodListDirect {
    val vm = classNamed(QUICKSNAP_VM_CLASS) ?: return@findMethodListDirect alwaysMatches()
    alwaysMatches() + vm.methods.filter { m ->
        val p = m.paramTypeNames
        Modifier.isStatic(m.modifiers) && p.size >= 3 &&
            p[0] == CONTEXT && p[1] == BITMAP && m.returnTypeName == "java.lang.Object"
    }
}

private const val PANDO_MUSIC_INFO = "com.instagram.api.schemas.ImmutablePandoMusicInfo"
private const val TRACK_DATA = "com.instagram.api.schemas.TrackData"
private const val PANDO_TRACK_DATA = "com.instagram.api.schemas.ImmutablePandoTrackData"
private const val MUSIC_CONSUMPTION = "com.instagram.music.common.model.MusicConsumptionModel"
private const val PANDO_MUSIC_CONSUMPTION = "com.instagram.music.common.model.ImmutablePandoMusicConsumptionModel"

private fun DexKitBridge.pandoGetter(type: String, field: String, returns: String?): MethodData =
    findMethod {
        matcher {
            declaredClass(type)
            paramCount = 0
            if (returns != null) returnType = returns
            usingNumbers(field.hashCode())
        }
    }.single { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }

val mediaDashManifestGetterFingerprint = findMethodDirect { pandoGetter(MEDIA_CLASS, "video_dash_manifest", STRING) }

val mediaMusicMetadataGetterFingerprint = findMethodDirect { pandoGetter(MEDIA_CLASS, "music_metadata", null) }
val mediaClipsMetadataGetterFingerprint = findMethodDirect { pandoGetter(MEDIA_CLASS, "clips_metadata", null) }

val musicTrackGetterFingerprint = findMethodDirect { pandoGetter(PANDO_MUSIC_INFO, "music_asset_info", TRACK_DATA) }
val musicConsumptionGetterFingerprint = findMethodDirect { pandoGetter(PANDO_MUSIC_INFO, "music_consumption_info", MUSIC_CONSUMPTION) }
val trackUrlGetterFingerprint = findMethodDirect { pandoGetter(PANDO_TRACK_DATA, "progressive_download_url", STRING) }
val musicStartGetterFingerprint = findMethodDirect { pandoGetter(PANDO_MUSIC_CONSUMPTION, "audio_asset_start_time_in_ms", "java.lang.Integer") }
val musicLengthGetterFingerprint = findMethodDirect { pandoGetter(PANDO_MUSIC_CONSUMPTION, "overlap_duration_in_ms", "java.lang.Integer") }

val reelMenuRowAdderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings(listOf("ClipsOrganicMoreOptionsHelper_addBottomSheetRowItem"), StringMatchType.Contains)
        }
    }.single { m ->
        val p = m.paramTypeNames
        !Modifier.isStatic(m.modifiers) && p.size == 6 && p[0] == CONTEXT && p[1] == MEDIA_OPTION_CLASS &&
            p[4] == "java.lang.Integer" && p[5] == STRING
    }
}

