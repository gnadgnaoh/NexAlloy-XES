package io.github.nexalloy.revanced.messenger

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.messenger.privacy.AllowScreenshots
import io.github.nexalloy.revanced.messenger.privacy.HideReadReceipts
import io.github.nexalloy.revanced.messenger.privacy.HideTypingIndicator
import io.github.nexalloy.revanced.messenger.privacy.StopAnalyticsUploads
import io.github.nexalloy.revanced.messenger.privacy.ViewStoriesAnonymously

val MessengerPatches = arrayOf<Patch>(
    HideTypingIndicator,
    HideReadReceipts,
    AllowScreenshots,
    ViewStoriesAnonymously,
    StopAnalyticsUploads,
)
