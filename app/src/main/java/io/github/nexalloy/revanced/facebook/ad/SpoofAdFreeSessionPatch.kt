package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookForceBoolean

val SpoofAdFreeSession = patch(
    name = "Spoof ad-free session",
    description = "Makes Facebook believe an ad-free session is active, so the server stops sending sponsored content (including sponsored Stories tray circles).",
) {
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }
    val getters = runCatching { ::adFreeSessionGettersFingerprint.dexMethodList }.getOrNull().orEmpty()
    getters.forEach { dm -> runCatching { hookForceBoolean(dm.toMethod(), true) } }
}
