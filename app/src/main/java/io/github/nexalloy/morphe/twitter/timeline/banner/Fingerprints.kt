package io.github.nexalloy.morphe.twitter.timeline.banner

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.twitter.utils.X_URT_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.dataClassToString

/**
 * data class UrtShowInstructionsState(
 *     showInstructions: List, isEligibleToShowPill: Boolean, hasUnreadTweets: Boolean,
 *     isReadyToShow: Boolean, lastPillShownAt: Long, isPillCurrentlyVisible: Boolean,
 * )
 * 12.30.0-prod.01: Lcom/x/urt/instructions/v;
 */
internal object ShowInstructionsStateToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("UrtShowInstructionsState", X_URT_PACKAGE) },
)
