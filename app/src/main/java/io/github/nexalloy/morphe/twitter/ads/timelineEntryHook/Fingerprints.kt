package io.github.nexalloy.morphe.twitter.ads.timelineEntryHook

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint

internal object DbTimelineEntryToItemFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    strings = listOf("entryId", "landingUrl", "promotedMetadata", "title"),
)

internal object PromotedMetadataToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("TimelinePromotedMetadata(impressionId="),
)

internal object ClientEventInfoToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("ClientEventInfo(component="),
)
