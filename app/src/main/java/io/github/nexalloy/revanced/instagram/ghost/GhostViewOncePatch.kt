package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

private val VIEW_ONCE_SEEN_MARKERS = listOf("visual_item_seen", "send_visual_item_seen_marker")

val GhostViewOnce = patch(
    name = "Ghost view once",
    description = "Prevents view-once seen notifications from being sent.",
) {
    ::viewOnceFingerprint.hookMethod {
        before { param ->
            val request = param.args[2] ?: return@before

            for (method in request.javaClass.declaredMethods) {
                if (method.parameterCount != 0 || method.returnType != String::class.java) continue
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(request) as? String
                }.getOrNull() ?: continue

                if (VIEW_ONCE_SEEN_MARKERS.any { value.contains(it) }) {
                    Logger.printDebug { "Ghost: view-once seen marker suppressed" }
                    param.result = null
                    break
                }
            }
        }
    }
}
