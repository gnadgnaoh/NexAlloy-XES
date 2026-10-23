package io.github.nexalloy.revanced.messenger

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.messenger.ads.HideAdBannersInBusinessChats
import io.github.nexalloy.revanced.messenger.ads.HideChatPromotions
import io.github.nexalloy.revanced.messenger.ads.HideInboxPromotions
import io.github.nexalloy.revanced.messenger.media.SaveAnyStory
import io.github.nexalloy.revanced.messenger.media.SendPhotosAtOriginalQuality
import io.github.nexalloy.revanced.messenger.media.SendVideosWithoutReencoding
import io.github.nexalloy.revanced.messenger.privacy.AllowScreenshots
import io.github.nexalloy.revanced.messenger.privacy.HideReadReceipts
import io.github.nexalloy.revanced.messenger.privacy.HideTypingIndicator
import io.github.nexalloy.revanced.messenger.privacy.StopAdAttributionUploads
import io.github.nexalloy.revanced.messenger.privacy.StopAnalyticsUploads
import io.github.nexalloy.revanced.messenger.privacy.StopInboxAndAdLinkLogging
import io.github.nexalloy.revanced.messenger.privacy.ViewStoriesAnonymously

val MessengerPatches = arrayOf<Patch>(
    HideTypingIndicator,
    HideReadReceipts,
    AllowScreenshots,
    ViewStoriesAnonymously,
    StopAnalyticsUploads,
    StopAdAttributionUploads,
    StopInboxAndAdLinkLogging,
    SaveAnyStory,
    SendPhotosAtOriginalQuality,
    SendVideosWithoutReencoding,
    HideInboxPromotions,
    HideChatPromotions,
    HideAdBannersInBusinessChats,
)
