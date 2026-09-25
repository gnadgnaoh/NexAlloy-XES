package io.github.nexalloy.morphe.reddit

import io.github.nexalloy.morphe.reddit.ad.HideAds
import io.github.nexalloy.morphe.reddit.misc.guest.startAsGuestPatch
import io.github.nexalloy.morphe.reddit.misc.privacy.SanitizeSharingLinks

val RedditPatches = arrayOf(
    HideAds,
    SanitizeSharingLinks,
    startAsGuestPatch,
)