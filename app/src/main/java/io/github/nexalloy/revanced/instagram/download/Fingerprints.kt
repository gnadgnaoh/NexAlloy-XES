package io.github.nexalloy.revanced.instagram.download

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

/**
 * DexKit lookups for the Instagram download patches.
 *
 * Every lookup here is resolved through [io.github.nexalloy.PatchExecutor], so its result is
 * cached with the rest of the module's descriptors and the search only runs again after
 * Instagram updates.
 *
 * Most of these return a *list* rather than a single method. Two reasons: Instagram often
 * builds the same menu from more than one method (your own story and someone else's go
 * through different dispatchers), and a list comes back empty instead of throwing when a
 * build no longer has the target — several of these are genuinely optional. Where the
 * original InstaEclipse code narrowed a list by reflecting over loaded classes, that
 * filtering stays in the patch body; only the query is cached.
 */

// ──────────────────────────────────────────────────────────────────────────────
// Media model — shared by every entry point
// ──────────────────────────────────────────────────────────────────────────────

/**
 * `getUrl()` on every class implementing `VideoVersionIntf`.
 *
 * Hooking these captures CDN URLs passively, so the download does not depend on knowing
 * the obfuscated name of the method that returns the video-versions list.
 */
val videoVersionGetUrlMethods = findMethodListDirect {
    findClass {
        matcher { addInterface("com.instagram.model.mediasize.VideoVersionIntf") }
    }.flatMap { implementor ->
        findMethod {
            matcher {
                declaredClass(implementor.name)
                name = "getUrl"
                returnType = "java.lang.String"
                paramCount = 0
            }
        }
    }
}

/**
 * The concrete media dictionary (`LiveTreeMediaDict` before it was obfuscated).
 *
 * Anchored on a Pando field name rather than the class name. The class is picked out of the
 * matches by two structural traits that survive renaming: it holds a field of its own type
 * (the Pando backing node), and it is a class rather than the interface it implements.
 */
val mediaDictClass = findClassDirect {
    findClass {
        matcher { usingStrings("video_to_carousel_cut_info") }
    }.first { candidate ->
        !Modifier.isInterface(candidate.modifiers) &&
            candidate.fields.any { it.typeName == candidate.name }
    }
}

/** Zero-arg getters reading the Pando `video_versions` field. */
val videoVersionsGetterMethods = findMethodListDirect {
    findMethod {
        matcher {
            paramCount = 0
            usingEqStrings("video_versions")
        }
    }
}

/**
 * The carousel-children accessor: one child media per slide.
 *
 * Instagram 447 collapsed the separate dictionary model into `Media` itself, which left the
 * dictionary-harvested candidates empty and carousels collapsing to a single URL. This
 * accessor is anchored on the stable Pando string instead, and is absent on older builds.
 */
val carouselMediaGetterMethods = findMethodListDirect {
    findMethod {
        matcher {
            paramCount = 0
            returnType = "java.util.List"
            usingEqStrings("carousel_media")
        }
    }
}

/**
 * `Media`'s own is-this-a-video check.
 *
 * Found indirectly: the analytics method that logs `is_video` calls it, so the wrapper is
 * located by its logging strings and the boolean getter is taken from what it invokes.
 */
val isVideoMethods = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("asl_session_id", "is_video", "is_carousel")
        }
    }.flatMap { wrapper ->
        wrapper.invokes.filter { invoked ->
            invoked.paramCount == 0 &&
                invoked.returnTypeName == "boolean" &&
                invoked.declaredClassName == "com.instagram.feed.media.Media"
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Author resolution
// ──────────────────────────────────────────────────────────────────────────────

/** Anchor for the `User` model class, via its validation message. */
val userClassAnchorMethods = findMethodListDirect {
    findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }
}

/**
 * `User`'s username getter, found by the GraphQL field id `"username".hashCode()`.
 *
 * That id matches two zero-arg String getters: the real one, and a display-name getter that
 * reads `username` first and falls back to `full_name`. Picking the wrong one files downloads
 * under the display name whenever it happens to look like a handle, so any candidate that
 * also reads the `full_name` id is dropped — the real getter never touches it.
 */
val userUsernameGetterMethods = findMethodListDirect {
    val userClassName = findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }.firstOrNull()?.declaredClassName ?: "com.instagram.user.model.User"

    val readsFullName = findMethod {
        matcher {
            declaredClass(userClassName)
            returnType = "java.lang.String"
            paramCount = 0
            usingNumbers(-265713450, "full_name".hashCode())
        }
    }.map { it.descriptor }.toSet()

    findMethod {
        matcher {
            declaredClass(userClassName)
            returnType = "java.lang.String"
            paramCount = 0
            usingNumbers(-265713450)
        }
    }.sortedBy { it.descriptor in readsFullName }
}

/**
 * The post author getter that Instagram 446+ moved straight onto `Media`.
 *
 * Identified by the field-id literal `"user".hashCode()`, which picks the generic Pando
 * `user` field rather than the owner / group-creator / reshare-author getters sitting
 * beside it. Absent on older builds, which keep using the dictionary path.
 */
val mediaAuthorGetterMethods = findMethodListDirect {
    val userClassName = findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }.firstOrNull()?.declaredClassName ?: "com.instagram.user.model.User"

    findMethod {
        matcher {
            declaredClass("com.instagram.feed.media.Media")
            paramCount = 0
            returnType = userClassName
            usingNumbers(3599307)
        }
    }
}

/**
 * The author getter on the concrete dictionary class.
 *
 * Used only when walking the interface hierarchy finds nothing. That class carries several
 * zero-arg `User` getters (owner, group creator, reshared-story author, previous submitter),
 * so the one reading the generic Pando `user` field is selected rather than the first found.
 */
val dictUserGetterMethods = findMethodListDirect {
    val dictClassName = findClass {
        matcher { usingStrings("video_to_carousel_cut_info") }
    }.firstOrNull { candidate ->
        !Modifier.isInterface(candidate.modifiers) &&
            candidate.fields.any { it.typeName == candidate.name }
    }?.name ?: return@findMethodListDirect emptyList()

    val userClassName = findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }.firstOrNull()?.declaredClassName ?: "com.instagram.user.model.User"

    findMethod {
        matcher {
            declaredClass(dictClassName)
            paramCount = 0
            returnType = userClassName
            usingEqStrings("user")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Reels
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Methods referencing the reel overflow-menu controller.
 *
 * The string is referenced from several methods, so this returns all of them and the patch
 * picks the options-builder out of their declaring classes by its parameter shape.
 */
val reelOptionsControllerMethods = findMethodListDirect {
    findMethod {
        matcher { usingStrings("ClipsOrganicMediaItemViewMoreOptionsController") }
    }
}

/**
 * The builder for Instagram's simplified reel overflow menu.
 *
 * That menu's option list dropped DOWNLOAD entirely, unlike the older fuller menu. The
 * builder is matched on two option constants it references; appending to the ArrayList it
 * returns sends the entry through the same row builder every other option uses.
 */
val reelOptionsListBuilderMethods = findMethodListDirect {
    val option = "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"
    findMethod {
        matcher {
            returnType = "java.util.ArrayList"
            addUsingField("$option->PLAYBACK_CONTROLS:$option")
            addUsingField("$option->UNSAVE:$option")
        }
    }
}

/**
 * The "can this media be downloaded" gate.
 *
 * Instagram 437+ already has a working native download row wired to its own save flow, kept
 * behind this check and [reelDownloadRestrictedGateMethods]. Forcing both is far more robust
 * than rebuilding that row and its click handler. Each gate is matched by its MobileConfig
 * param id, which is a hardcoded literal.
 */
val reelDownloadEligibleGateMethods = findMethodListDirect {
    findMethod {
        matcher {
            paramTypes("com.instagram.common.session.UserSession", "com.instagram.feed.media.Media")
            returnType = "boolean"
            usingNumbers(36313978552585585L)
        }
    }
}

/** The "is the viewer restricted from downloading" gate. See [reelDownloadEligibleGateMethods]. */
val reelDownloadRestrictedGateMethods = findMethodListDirect {
    findMethod {
        matcher {
            paramTypes("com.instagram.common.session.UserSession", "boolean")
            returnType = "boolean"
            usingNumbers(36313978552847731L)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Posts
// ──────────────────────────────────────────────────────────────────────────────

/**
 * The void methods on `MediaOptionsOverflowMenuCreator`.
 *
 * The class is located first by string, then its methods are listed, because resolving a
 * `<clinit>` match directly crashes. The patch then picks the add-button method by looking
 * for the parameters it must take (the option enum and the list to add to).
 */
val postMenuCreatorVoidMethods = findMethodListDirect {
    val creator = findClass {
        matcher { usingStrings("MediaOptionsOverflowMenuCreator") }
    }.firstOrNull()?.name ?: return@findMethodListDirect emptyList()

    findMethod {
        matcher {
            declaredClass(creator)
            returnType = "void"
        }
    }
}

/**
 * Candidates for the menu's click dispatcher: anything taking the tapped option.
 *
 * Several methods match, including private analytics helpers that receive the option purely
 * for telemetry, so the patch narrows this further before hooking.
 */
val postOptionClickMethods = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("com.instagram.feed.media.mediaoption.MediaOption\$Option")
        }
    }
}

/**
 * The method returning the list of options the menu is allowed to show.
 *
 * Matched on three option constants it references. On builds that do not filter the menu
 * this resolves to nothing, which is harmless.
 */
val postMenuAllowlistMethods = findMethodListDirect {
    val option = "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"
    findMethod {
        matcher {
            paramTypes("boolean")
            returnType = "java.util.List"
            addUsingField("$option->REPORT:$option")
            addUsingField("$option->HIDE_OPTIONS:$option")
            addUsingField("$option->GEN_AI_INFO:$option")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Stories
// ──────────────────────────────────────────────────────────────────────────────

/**
 * The story option-list builders.
 *
 * Instagram builds this list with a different method for your own story (a static helper
 * offering Delete / Archive / Save video) than for someone else's (Report / Mute / AI info),
 * so both are needed — matching only the first one left Download missing from your own
 * stories. Anchored on a debug string that has stayed stable across versions.
 */
val storyOptionBuilderMethods = findMethodListDirect {
    findMethod {
        matcher { usingStrings("[INTERNAL] Pause Playback") }
    }
}

/**
 * The story option click dispatchers.
 *
 * Same anchor as [storyOptionBuilderMethods], narrowed to the void methods. The self-story
 * dispatcher is static and takes the outer class as a parameter, so statics are kept; the
 * patch checks the tapped label at runtime, which makes hooking the extra dispatchers
 * harmless.
 */
val storyOptionClickMethods = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("[INTERNAL] Pause Playback")
        }
    }
}
