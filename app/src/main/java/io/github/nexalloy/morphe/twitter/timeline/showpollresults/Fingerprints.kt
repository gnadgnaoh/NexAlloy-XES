package io.github.nexalloy.morphe.twitter.timeline.showpollresults

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.twitter.utils.X_MODELS_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.dataClassToString

internal object LegacyCardToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("LegacyCard", X_MODELS_PACKAGE) },
)

internal object LegacyCardBindingValuesFingerprint : Fingerprint(
    classFingerprint = LegacyCardToStringFingerprint,
    returnType = "Ljava/util/Map;",
    parameters = emptyList(),
)

internal object CardBooleanValueToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("BooleanValue", X_MODELS_PACKAGE) },
)
