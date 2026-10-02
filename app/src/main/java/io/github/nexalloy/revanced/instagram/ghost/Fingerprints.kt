package io.github.nexalloy.revanced.instagram.ghost

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.accessFlags
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/*
 * Rules used below (IG 449 checks in comments):
 * - String anchors come first; a shape (return type / params) is kept only where it is what
 *   tells the target apart, otherwise it is just one more thing an update can break.
 * - `.single()` instead of `.first()`: these hooks block or rewrite the method, so a second
 *   candidate must fail loudly instead of silently hooking the wrong one.
 * - Instagram has "string pool" methods (`static String A00(int)`, one giant switch over
 *   thousands of literals) that match almost any usingStrings query; they are never targets.
 */
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

val viewOnceFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("visual_item_seen")
            returnType = "void"
            paramCount = 3
        }
    }.first()
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

/** The only user of "mark_thread_seen-" (449: the static/arity filters were redundant). */
val seenStateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("mark_thread_seen-")
            returnType = "void"
        }
    }.filterNot { it.isStringPool() }.single()
}

val typingStatusFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("is_typing_indicator_enabled", "indicate_activity")
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
    findMethod {
        matcher {
            usingStrings("igThreadIgid")
            paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean") // needed: 3 users of the string
            returnType = "void"
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

/** Parsers of "message_expiration_timestamp_ms" (string pools excluded). */
val ephemeralExpiryParserFingerprintList = findMethodListDirect {
    findMethod {
        matcher {
            usingStrings("message_expiration_timestamp_ms")
        }
    }.filterNot { it.isStringPool() }
}

/**
 * The ephemeral media JSON entry point, `unsafeParseFromJson(reader)` (a kept interface name).
 * 449: matching the strings alone also hit the string pool `X/0000.A00(int)`, which the old
 * `.first { returnType != void }` picked, so the hook never saw a media object.
 */
val permanentViewModeFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "unsafeParseFromJson"
            usingStrings("view_mode", "tap_models")
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
