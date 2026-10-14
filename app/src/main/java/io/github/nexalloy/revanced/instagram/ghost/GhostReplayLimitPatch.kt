package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostReplayLimit = patch(
    name = "Ghost replay limit",
    description = "Allows unlimited replays of replayable (view-twice) DM media.",
) {
    ::replayUpdateFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: replay-update blocked" }
            param.result = null
        }
    }

    ::replayParseFromJsonFingerprintList.dexMethodList.forEach { dexMethod ->
        dexMethod.hookMethod {
            after { param ->
                zeroReplayCountFields(param.thisObject)
                val result = param.result
                if (result != null && result !== param.thisObject) zeroReplayCountFields(result)
            }
        }
    }

    ::replaySyncFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: replay-sync blocked" }
            param.result = null
        }
    }
}

private fun zeroReplayCountFields(obj: Any?) {
    obj ?: return
    try {
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType) continue
            f.isAccessible = true
            val value = f.getInt(obj)
            if (value in 1..10) {
                f.setInt(obj, 0)
                Logger.printDebug { "Ghost: zeroed replay-count field ${f.name} = $value" }
            }
        }
    } catch (_: Throwable) {}
}
