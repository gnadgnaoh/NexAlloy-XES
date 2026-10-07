package io.github.nexalloy.revanced.instagram.ghost

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.accessFlags
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private fun MethodData.isStringPool(): Boolean =
    Modifier.isStatic(modifiers) && returnTypeName == "java.lang.String" &&
        paramTypeNames == listOf("int")

val screenshotFingerprint = findMethodDirect {
    val classNames = findClass {
        matcher { usingStrings("ScreenshotNotificationManager") }
    }.map { it.name }

    classNames.firstNotNullOf { className ->
        findMethod {
            matcher {
                declaredClass(className)
                returnType = "void"
                paramTypes("long")
            }
        }.firstOrNull()
    }
}

val visualSeenSendFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("direct_v2/visual_threads/%s/item_seen/", "raven_media")
            returnType = "void"
            paramCount = 3
        }
    }.filterNot { it.isStringPool() }.single()
}

val visualReplayedSendFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("direct_v2/visual_threads/%s/item_replayed/")
            returnType = "void"
            paramCount = 3
        }
    }.filterNot { it.isStringPool() }.single()
}

val permanentMediaSeenSendFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("direct_v2/visual_threads/%s/visual_items/%s/seen/")
            returnType = "void"
            paramCount = 3
        }
    }.filterNot { it.isStringPool() }.single()
}

val storySeenFingerprint = findMethodDirect {
    val classNames = findClass {
        matcher { usingStrings("pending_reel_seen_states_") }
    }.map { it.name }

    classNames.firstNotNullOf { className ->
        findMethod {
            matcher {
                declaredClass(className)
                returnType = "void"
                paramCount = 1
                accessFlags(AccessFlags.FINAL)
            }
        }.firstOrNull()
    }
}

val seenStateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("mark_thread_seen-")
            returnType = "void"
        }
    }.filterNot { it.isStringPool() }.single()
}

val seenMarkerClassFingerprint = findClassDirect {
    findMethod {
        matcher {
            usingEqStrings("thread_item_seen")
            returnType = "java.lang.String"
            paramCount = 0
        }
    }.single().declaredClass ?: error("seen marker class not found")
}

val seenMarkerSendFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("IGDirectItemSeenMutation", "xig_direct_item_seen")
            returnType = "void"
            paramCount = 3
        }
    }.filterNot { it.isStringPool() }.single()
}

val threadSeenMarkerGetterFingerprint = findMethodDirect {
    val marker = seenMarkerClassFingerprint().name
    findMethod {
        matcher {
            paramTypes("java.lang.String")
            returnType = marker
        }
    }.filterNot { Modifier.isAbstract(it.modifiers) || Modifier.isStatic(it.modifiers) }.single()
}

val dmBadgeRecomputeFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("user_session_init", "event_chat_invite_acknowledged")
            paramTypes("java.lang.String", "boolean")
        }
    }.filterNot { it.isStringPool() }.single()
}

val badgeDotFinalizerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "invoke"
            paramTypes("java.lang.Object")
            addInvoke {
                declaredClass(BADGE_ANIMATOR)
                paramTypes("android.widget.TextView", null, "java.lang.Float", "boolean")
                returnType = "void"
            }
        }
    }.single()
}

internal const val BADGE_ANIMATOR = "com.instagram.mainactivity.maintab.swipeabletabs.ui.badging.BadgeAnimator"

val typingStatusFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("indicate_activity")
            returnType = "void"
        }
    }.filterNot { it.isStringPool() }.single()
}

val ephemeralMediaJsonParserFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("url_expire_at_secs", "view_mode", "seen_count", "tap_models")
            returnType = "void"
            paramCount = 2 // needed: unsafeParseFromJson uses the same strings
        }
    }.single()
}

val ephemeralVanishLocalDeleteFingerprint = findMethodDirect {
    val seenEphemeralDataClass = findMethod {
        matcher {
            usingEqStrings("ig_thread_igid", "viewed_item_ranges", "viewed_timestamp_ms")
            returnType = "void"
            paramCount = 3
        }
    }.single().paramTypeNames[1]

    findMethod {
        matcher {
            paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")
            returnType = "void"
            addInvoke {
                declaredClass(seenEphemeralDataClass)
                name = "<init>"
            }
        }
    }.single()
}

val ephemeralServerPingFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("mark_ephemeral_item_ranges_viewed")
            returnType = "void" // needed: 5 users of the string
        }
    }.single()
}

val ephemeralExpiryParserFingerprintList = findMethodListDirect {
    findMethod {
        matcher {
            usingStrings("message_expiration_timestamp_ms")
        }
    }.filterNot { it.isStringPool() }
}

val permanentViewModeFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "unsafeParseFromJson"
            usingStrings("view_mode", "tap_models", "url_expire_at_secs", "seen_count")
        }
    }.single()
}

val replayUpdateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(
                "direct_v2/visual_threads/%s/item_replayed/"
            )
            returnType = "void"
        }
    }.first()
}

val replayParseFromJsonFingerprintList = findMethodListDirect {
    findMethod {
        matcher {
            usingStrings("seen_count", "tap_models")
        }
    }
}

val replaySyncFingerprint = findMethodDirect {
    findMethod {
        matcher {
            paramTypes("com.instagram.common.session.UserSession", null, null)
            returnType = "void"
            modifiers(AccessFlags.SYNCHRONIZED.modifier)
        }
    }.first()
}
