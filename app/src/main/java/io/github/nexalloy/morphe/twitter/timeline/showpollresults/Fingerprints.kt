package io.github.nexalloy.morphe.twitter.timeline.showpollresults

import io.github.nexalloy.morphe.Fingerprint

internal object LegacyCardToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("LegacyCard(cardPlatform="),
)

internal object LegacyCardBindingValuesFingerprint : Fingerprint(
    classFingerprint = LegacyCardToStringFingerprint,
    returnType = "Ljava/util/Map;",
    parameters = emptyList(),
)

internal object CardBooleanValueToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("BooleanValue(value="),
)
