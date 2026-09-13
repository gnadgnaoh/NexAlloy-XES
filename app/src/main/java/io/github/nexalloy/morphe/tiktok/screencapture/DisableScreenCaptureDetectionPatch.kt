package io.github.nexalloy.morphe.tiktok.screencapture

import android.app.Activity
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val TAG = "[TikTok screen capture]"

val DisableScreenCaptureDetection = patch(
    name = "Disable screen capture detection",
    description = "Prevents TikTok from reacting to screenshots and screen recordings.",
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    // Android 14+; absent on older systems, where there is nothing to block.
    optional("screenCaptureCallback") {
        val methods = Activity::class.java.declaredMethods.filter {
            it.name == "registerScreenCaptureCallback" || it.name == "unregisterScreenCaptureCallback"
        }
        check(methods.isNotEmpty()) { "Activity has no screen capture callback API" }
        methods.forEach { method ->
            method.hookMethod {
                before { param -> param.result = null }
            }
        }
    }

    optional("clearModeDisplayListener") {
        listOf(ClearModeDisplayAddedFingerprint, ClearModeDisplayRemovedFingerprint).forEach { fingerprint ->
            fingerprint.hookMethod {
                before { param -> param.result = null }
            }
        }
    }

    check(installed.isNotEmpty()) { "no screen capture detection could be disabled: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
