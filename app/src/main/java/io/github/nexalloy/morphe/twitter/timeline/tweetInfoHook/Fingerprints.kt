package io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook

import io.github.nexalloy.morphe.Fingerprint

internal object CanonicalPostToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("CanonicalPost(id="),
)

internal object CanonicalPostConstructorFingerprint : Fingerprint(
    classFingerprint = CanonicalPostToStringFingerprint,
    name = "<init>",
    custom = { paramCount = 42 },
)

internal object AvailablePostToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("AvailablePost(entryId="),
)

internal object AvailablePostConstructorFingerprint : Fingerprint(
    classFingerprint = AvailablePostToStringFingerprint,
    name = "<init>",
    custom = { paramCount = 65 },
)
