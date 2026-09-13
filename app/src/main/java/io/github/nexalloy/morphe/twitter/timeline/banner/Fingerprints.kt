package io.github.nexalloy.morphe.twitter.timeline.banner

import io.github.nexalloy.morphe.Fingerprint

internal object ShowInstructionsStateToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("UrtShowInstructionsState(showInstructions="),
)

internal object ShowInstructionsStateConstructorFingerprint : Fingerprint(
    classFingerprint = ShowInstructionsStateToStringFingerprint,
    name = "<init>",
    custom = { paramCount = 6 },
)
