package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostPermanentView = patch(
    name = "Ghost permanent view",
    description = "Makes unexpired view-once and replayable media permanently accessible.",
) {
    ::permanentViewModeFingerprint.hookMethod {
        after { param ->
            val result = param.result ?: return@after
            makeEphemeralPermanent(result)
        }
    }

    ::ephemeralMediaJsonParserFingerprint.hookMethod {
        after { param ->
            param.result?.let { makeEphemeralPermanent(it) }
            param.args.forEach { arg -> if (arg != null) makeEphemeralPermanent(arg) }
        }
    }
}

private val EPHEMERAL_VIEW_MODES = setOf("once", "replayable", "allow_replay")
private const val PERMANENT = "permanent"

internal fun makeEphemeralPermanent(obj: Any) {
    try {
        val fields = obj.javaClass.declaredFields

        val nowSec = System.currentTimeMillis() / 1000L
        val year2100Sec = 4_102_444_800L
        var expireAt: Long? = null

        for (f in fields) {
            if (f.type != Long::class.javaPrimitiveType && f.type != Long::class.javaObjectType) continue
            f.isAccessible = true
            val v = if (f.type == Long::class.javaPrimitiveType) f.getLong(obj)
                else (f.get(obj) as? Long) ?: continue
            if (v in (nowSec + 1) until year2100Sec) {
                expireAt = v
                break
            }
        }

        if (expireAt == null) return

        if (nowSec > expireAt) return

        for (f in fields) {
            if (f.type != String::class.java) continue
            f.isAccessible = true
            val v = f.get(obj) as? String ?: continue
            if (v in EPHEMERAL_VIEW_MODES) {
                f.set(obj, PERMANENT)
                Logger.printDebug { "[Ghost] view_mode '$v' → 'permanent' (expireAt=$expireAt)" }
            }
        }
    } catch (e: Throwable) {
        Logger.printException({ "GhostPermanentView failed" }, e)
    }
}
