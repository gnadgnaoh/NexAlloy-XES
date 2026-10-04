package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookAdRequestNoOp
import io.github.nexalloy.revanced.facebook.hookForceBoolean
import io.github.nexalloy.revanced.facebook.hookBlockNull
import io.github.nexalloy.revanced.facebook.hookNullAdResult
import io.github.nexalloy.revanced.facebook.hookVideoViewerExtensionGate

val HideInstreamAdBreaks = patch(
    name = "Hide in-stream ad breaks",
    description = "Removes mid-roll ad breaks and the docked sponsored card from videos and live replays.",
) {
    val extensionGates = runCatching {
        ::videoViewerExtensionGateMethodsFingerprint.dexMethodList
    }.getOrElse {
        error("Facebook video permalink dex is not visible yet - deferring patch")
    }

    extensionGates.forEach { dm ->
        runCatching { hookVideoViewerExtensionGate(dm.toMethod()) }
    }

    runCatching { ::adBreakFetchKickoffMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdRequestNoOp(dm.toMethod()) } }

    runCatching { ::wasLiveAdBreakControlRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookNullAdResult(dm.toMethod()) } }

    runCatching { ::videoAdBlockMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }

    runCatching { ::tapToFullscreenAdFetchFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookForceBoolean(dm.toMethod(), false) } }
}
