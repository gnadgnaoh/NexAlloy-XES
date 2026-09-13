package io.github.nexalloy.morphe.tiktok.screencapture

import io.github.nexalloy.morphe.Fingerprint
import org.luckypray.dexkit.query.enums.StringMatchType

internal object ClearModeDisplayAddedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("[onDisplayAdded]"),
    custom = { declaredClass("ClearModePanelComponent", StringMatchType.EndsWith) },
)

internal object ClearModeDisplayRemovedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("[onDisplayRemoved]"),
    custom = { declaredClass("ClearModePanelComponent", StringMatchType.EndsWith) },
)
