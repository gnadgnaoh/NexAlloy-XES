package io.github.nexalloy.revanced.instagram.download

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

// Class names that are not obfuscated (verified on Instagram 451).
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

/*
 * Every list fingerprint starts with Media.<init>. DexKit's cache treats an empty list as
 * "not resolved yet" and would rescan on every launch, so the anchor keeps the cached list
 * non-empty. Consumers drop it with [isAnchor].
 */
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

// region Media model

/** `VideoUrlImpl(VideoUrlImpl, Long, String url, String, int, int, int)`; throws on a null url. */
val videoUrlConstructorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            usingStrings(listOf("VideoUrl object with null url"), StringMatchType.Contains)
        }
    }.single()
}

/** `Media.video_versions` getter: `List<VideoUrlImpl>`. */
val videoVersionsGetterMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramCount = 0
            returnType = "java.util.List"
            usingEqStrings("video_versions")
        }
    }
}

/** `Media.carousel_media` getter: `List<Media>`. */
val carouselMediaGetterMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramCount = 0
            returnType = "java.util.List"
            usingEqStrings("carousel_media")
        }
    }
}

/** The boolean Media getter logged right before the `is_video` key in an analytics event. */
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

/**
 * `ImageInfo` (image_versions2) of a Media. The static MediaExtKt accessor comes first because
 * it resolves the live Pando tree; the Media instance getters are fallbacks.
 */
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

/**
 * Screen-sized image of a Media: `MediaExtKt.X(Context, Media)`. Returns an ExtendedImageUrl on
 * 451, a String on older builds; both shapes are accepted.
 */
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

/** `User.getUsername()`: reads key hash -265713450; the full_name reader is sorted last. */
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

/** `Media.getUser()` (owner): key hash 3599307 ("user"). */
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

// endregion

// region Post / reel overflow menus

/** `static void addRow(ButtonType, Option, Creator, CharSequence, ArrayList, boolean)`. */
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

/**
 * Option dispatchers `void X(MediaOption.Option)`: MediaOptionsOverflowHelper (feed posts) and
 * ClipsOrganicMoreOptionsHelper (reels). The surface is told apart at runtime.
 */
val postOptionClickMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            returnType = "void"
            paramTypes(MEDIA_OPTION_CLASS)
        }
    }
}

/** Post menu allow-list `List<Option> (boolean)`. */
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

/** Reel overflow option list `ArrayList<Option> (…, Media)`. */
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

// endregion

// region Story overflow menu

private const val STORY_OPTIONS_ANCHOR = "[INTERNAL] Pause Playback"

/**
 * The class that builds the story overflow menu (redex name "ReelOptionsOverflowHelper").
 * Since 451 the debug labels live in a private `void (List)` helper, so the label builders no
 * longer reference the anchor string themselves; we anchor on the class instead.
 */
private fun DexKitBridge.storyOptionsOwners(): List<ClassData> =
    findMethod { matcher { usingStrings(STORY_OPTIONS_ANCHOR) } }
        .mapNotNull { it.declaredClass }
        .distinctBy { it.name }

/** CharSequence[] / ArrayList builders of menu labels inside the owner class. */
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

/**
 * Click dispatchers: `static void X(OnDismissListener, …, CharSequence label)`.
 * Plus the legacy match (void methods referencing the anchor string) for older builds.
 */
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

/**
 * Label → icon mapper of the story "About this story" sheet: `static int X(Helper, CharSequence)`.
 * It returns 0 for labels it does not know, which is why our row had no icon.
 */
val storyOptionIconMethods = findMethodListDirect {
    alwaysMatches() + storyOptionsOwners().flatMap { owner ->
        owner.methods.filter { m ->
            Modifier.isStatic(m.modifiers) && m.returnTypeName == "int" &&
                m.paramTypeNames == listOf(owner.name, CHAR_SEQUENCE)
        }
    }
}

// endregion

// region Instants (QuickSnap)

/**
 * Constructors of the viewer-screen Compose lambdas that receive the instant being shown.
 * The lambda index (`…$16$1$8$1`, `…$16$1$11$1`, …) shifts every release, so we match on shape:
 * a constructor taking a model that holds a Media field.
 */
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

/** Constructors of the tap / long-press gesture lambdas (they hold Function1 callbacks). */
val instantLongPressConstructors = findMethodListDirect {
    alwaysMatches() + findClass { searchPackages(QUICKSNAP_VIEWER_PACKAGE) }
        .filter { it.name.contains("handleTapAndLongPress") }
        .filter { c -> c.fields.any { it.typeName == FUNCTION1 } }
        .flatMap { c -> c.methods.filter { it.isConstructor } }
}

/** Capture → upload suspend functions: `static Object X(Context, Bitmap, …)` on the camera VM. */
val instantCaptureMethods = findMethodListDirect {
    val vm = classNamed(QUICKSNAP_VM_CLASS) ?: return@findMethodListDirect alwaysMatches()
    alwaysMatches() + vm.methods.filter { m ->
        val p = m.paramTypeNames
        Modifier.isStatic(m.modifiers) && p.size >= 3 &&
            p[0] == CONTEXT && p[1] == BITMAP && m.returnTypeName == "java.lang.Object"
    }
}

// endregion
