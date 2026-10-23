package io.github.nexalloy.revanced.threads.privacy

import android.app.Activity
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val DisableScreenshotDetection = patch(
    name = "Disable screenshot detection",
    description = "Threads isn't told when you take a screenshot, and it stops watching your photos for new screenshots.",
) {
    ::screenshotLibraryObserverFingerprint.hookMethod { before { it.result = null } }
    ::screenshotFolderReportFingerprint.hookMethod { before { it.result = null } }
    Activity::class.java.declaredMethods
        .filter { it.name == "registerScreenCaptureCallback" || it.name == "unregisterScreenCaptureCallback" }
        .forEach { m -> m.hookMethod { before { it.result = null } } }
}
