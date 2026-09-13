package io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint

internal object FeatureSwitchValueFingerprint : Fingerprint(
    definingClass = "Lcom/x/featureswitches/FeatureSwitchesRepositoryImpl;",
    name = "getFeatureSwitchValue",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Z"),
)
