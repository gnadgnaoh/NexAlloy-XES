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
    val extensionGates = ::videoViewerExtensionGateMethodsFingerprint.dexMethodList
    check(extensionGates.isNotEmpty()) { "Facebook video permalink dex is not visible yet - deferring patch" }

    extensionGates.forEach { dm -> hookVideoViewerExtensionGate(dm.toMethod()) }
    ::adBreakFetchKickoffMethodsFingerprint.dexMethodList.forEach { dm -> hookAdRequestNoOp(dm.toMethod()) }
    ::wasLiveAdBreakControlRenderMethodsFingerprint.dexMethodList.forEach { dm -> hookNullAdResult(dm.toMethod()) }
    ::videoAdBlockMethodsFingerprint.dexMethodList.forEach { dm -> hookBlockNull(dm.toMethod()) }
    ::tapToFullscreenAdFetchFingerprint.dexMethodList.forEach { dm -> hookForceBoolean(dm.toMethod(), false) }
}
