package io.github.nexalloy.revanced.threads.tracking

import android.content.ClipData
import android.content.ClipboardManager
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private val TRACKING_QUERY = Regex("\\?xmt=.*")

val SanitizeTrackingLinks = patch(
    name = "Sanitize tracking links",
    description = "Removes tracking parameters from Threads links when copying.",
) {
    ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java).hookMethod {
        before { param ->
            val clipData = param.args[0] as? ClipData ?: return@before
            if (clipData.itemCount == 0) return@before

            val text = clipData.getItemAt(0)?.text?.toString() ?: return@before
            if (!text.contains("threads.com/") && !text.contains("threads.net/")) return@before

            Logger.printDebug { "Sanitize tracking link: $text" }
            param.args[0] = ClipData.newPlainText("URL", text.replace(TRACKING_QUERY, ""))
        }
    }
}
