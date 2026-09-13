package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostEphemeralKeep = patch(
    name = "Ghost ephemeral keep",
    description = "Prevents vanish/ephemeral (disappearing) messages from being deleted locally.",
) {
    ::ephemeralVanishLocalDeleteFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: ephemeral vanish-local-delete blocked" }
            param.result = null
        }
    }

    ::ephemeralServerPingFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: ephemeral server-ping blocked" }
            param.result = null
        }
    }

    ::ephemeralExpiryParserFingerprintList.dexMethodList.forEach { dexMethod ->
        dexMethod.hookMethod {
            after { param ->
                clearExpiryTimestamp(param.thisObject)
                val result = param.result
                if (result != null && result !== param.thisObject) clearExpiryTimestamp(result)
            }
        }
    }
}

private fun clearExpiryTimestamp(obj: Any?) {
    obj ?: return
    val now = System.currentTimeMillis()
    val year2100 = 4_102_444_800_000L
    try {
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Long::class.javaPrimitiveType) continue
            f.isAccessible = true
            val value = f.getLong(obj)
            if (value in (now + 1) until year2100) {
                f.setLong(obj, 0L)
                Logger.printDebug { "Ghost: zeroed expiry field ${f.name} = $value" }
            }
        }
    } catch (_: Throwable) {}
}
