package io.github.nexalloy.morphe.twitter.timeline.banner

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.twitter.utils.X_URT_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.dataClassToString

internal object ShowInstructionsStateToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("UrtShowInstructionsState", X_URT_PACKAGE) },
)
