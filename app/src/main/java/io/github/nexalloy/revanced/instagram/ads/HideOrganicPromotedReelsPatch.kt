package io.github.nexalloy.revanced.instagram.ads

import io.github.nexalloy.patch

val HidePaidPartnershipReels = patch(
    name = "Hide paid partnership Reels",
    description = "Hides reels labeled Paid partnership in the Reels tab. These are the creator's own (organic) " +
        "reels, not ads. Reels you open yourself still show.",
    use = false,
) {
    val paid = ::mediaPaidPartnershipGetterFingerprint.method
    dependsOn(ReelsPageFilterHook)
    addReelsRule("paid partnership") { media -> paid.invoke(media) == true }
}

private val BOOST_RAN = setOf("boosted", "finished")

val HideBoostedReels = patch(
    name = "Hide boosted Reels",
    description = "Hides reels their creator paid to boost in the Reels tab. These are the creator's own (organic) " +
        "reels, not ads. Reels you open yourself still show.",
    use = false,
) {
    val boosted = ::mediaBoostedStatusGetterFingerprint.method
    dependsOn(ReelsPageFilterHook)
    addReelsRule("boosted") { media -> (boosted.invoke(media) as? String)?.lowercase() in BOOST_RAN }
}
