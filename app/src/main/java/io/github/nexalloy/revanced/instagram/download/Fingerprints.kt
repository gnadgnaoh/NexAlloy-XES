package io.github.nexalloy.revanced.instagram.download

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val MEDIA_CLASS = "com.instagram.feed.media.Media"

private fun DexKitBridge.alwaysMatches(): List<MethodData> =
    findMethod {
        matcher {
            declaredClass(MEDIA_CLASS)
            name = "<init>"
        }
    }

internal fun isAnchor(declaringClassName: String, isConstructor: Boolean): Boolean =
    isConstructor && declaringClassName == MEDIA_CLASS

val videoVersionGetUrlMethods = findMethodListDirect {
    alwaysMatches() + findClass {
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

val videoVersionsGetterMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramCount = 0
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

val userClassAnchorMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }
}

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

    alwaysMatches() + findMethod {
        matcher {
            declaredClass(userClassName)
            returnType = "java.lang.String"
            paramCount = 0
            usingNumbers(-265713450)
        }
    }.sortedBy { it.descriptor in readsFullName }
}

val mediaAuthorGetterMethods = findMethodListDirect {
    val userClassName = findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }.firstOrNull()?.declaredClassName ?: "com.instagram.user.model.User"

    alwaysMatches() + findMethod {
        matcher {
            declaredClass(MEDIA_CLASS)
            paramCount = 0
            returnType = userClassName
            usingNumbers(3599307)
        }
    }
}

val dictUserGetterMethods = findMethodListDirect {
    val anchor = alwaysMatches()

    val dictClassName = findClass {
        matcher { usingStrings("video_to_carousel_cut_info") }
    }.firstOrNull { candidate ->
        !Modifier.isInterface(candidate.modifiers) &&
            candidate.fields.any { it.typeName == candidate.name }
    }?.name ?: return@findMethodListDirect anchor

    val userClassName = findMethod {
        matcher { usingStrings("username_missing_during_update") }
    }.firstOrNull()?.declaredClassName ?: "com.instagram.user.model.User"

    anchor + findMethod {
        matcher {
            declaredClass(dictClassName)
            paramCount = 0
            returnType = userClassName
            usingEqStrings("user")
        }
    }
}

val reelOptionsControllerMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher { usingStrings("ClipsOrganicMediaItemViewMoreOptionsController") }
    }
}

val reelOptionsListBuilderMethods = findMethodListDirect {
    val option = "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"
    alwaysMatches() + findMethod {
        matcher {
            returnType = "java.util.ArrayList"
            addUsingField("$option->PLAYBACK_CONTROLS:$option")
            addUsingField("$option->UNSAVE:$option")
        }
    }
}

val reelDownloadEligibleGateMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramTypes("com.instagram.common.session.UserSession", MEDIA_CLASS)
            returnType = "boolean"
            usingNumbers(36313978552585585L)
        }
    }
}

val reelDownloadRestrictedGateMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            paramTypes("com.instagram.common.session.UserSession", "boolean")
            returnType = "boolean"
            usingNumbers(36313978552847731L)
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
            paramTypes("com.instagram.feed.media.mediaoption.MediaOption\$Option")
        }
    }
}

val postMenuAllowlistMethods = findMethodListDirect {
    val option = "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"
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

val storyOptionBuilderMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher { usingStrings("[INTERNAL] Pause Playback") }
    }
}

val storyOptionClickMethods = findMethodListDirect {
    alwaysMatches() + findMethod {
        matcher {
            returnType = "void"
            usingStrings("[INTERNAL] Pause Playback")
        }
    }
}

val videoUrlConstructorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            usingStrings(listOf("VideoUrl object with null url"), StringMatchType.Contains)
        }
    }.single()
}
