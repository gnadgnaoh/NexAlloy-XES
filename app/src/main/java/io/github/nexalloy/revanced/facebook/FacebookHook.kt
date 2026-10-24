package io.github.nexalloy.revanced.facebook

import io.github.nexalloy.revanced.facebook.ad.AggressiveAdBlocking
import io.github.nexalloy.revanced.facebook.ad.BlockAdPrefetch
import io.github.nexalloy.revanced.facebook.ad.BlockAdTelemetry
import io.github.nexalloy.revanced.facebook.ad.DisableAudienceNetwork
import io.github.nexalloy.revanced.facebook.ad.HideAffiliateLinks
import io.github.nexalloy.revanced.facebook.ad.HideFacebookAds
import io.github.nexalloy.revanced.facebook.ad.HideInstreamAdBreaks
import io.github.nexalloy.revanced.facebook.ad.HideProfileTimelineAds
import io.github.nexalloy.revanced.facebook.ad.HideSearchAds
import io.github.nexalloy.revanced.facebook.ad.SpoofAdFreeSession
import io.github.nexalloy.revanced.facebook.download.DownloadPhoto
import io.github.nexalloy.revanced.facebook.download.DownloadReel
import io.github.nexalloy.revanced.facebook.download.DownloadStory
import io.github.nexalloy.revanced.facebook.download.DownloadVideo
import io.github.nexalloy.revanced.facebook.link.CleanLinks
import io.github.nexalloy.revanced.facebook.link.OpenLinksInBrowser
import io.github.nexalloy.revanced.facebook.link.SanitizeShareLinks
import io.github.nexalloy.revanced.facebook.privacy.AllowScreenCapture
import io.github.nexalloy.revanced.facebook.privacy.BlockScreenCaptureDetection
import io.github.nexalloy.revanced.facebook.privacy.HideCommentTyping
import io.github.nexalloy.revanced.facebook.privacy.HideActiveStatus
import io.github.nexalloy.revanced.facebook.privacy.HideSeenStory
import io.github.nexalloy.revanced.facebook.privacy.HoldAnalyticsUploads
import io.github.nexalloy.revanced.facebook.stories.UploadStoriesInHd

val FacebookPatches = arrayOf(
    HideFacebookAds,
    HideAffiliateLinks,
    HideInstreamAdBreaks,
    HideProfileTimelineAds,
    HideSearchAds,
    SpoofAdFreeSession,
    AggressiveAdBlocking,
    BlockAdPrefetch,
    BlockAdTelemetry,
    DisableAudienceNetwork,
    HideSeenStory,
    AllowScreenCapture,
    BlockScreenCaptureDetection,
    HoldAnalyticsUploads,
    HideCommentTyping,
    HideActiveStatus,
    CleanLinks,
    OpenLinksInBrowser,
    SanitizeShareLinks,
    UploadStoriesInHd,
    DownloadPhoto,
    DownloadReel,
    DownloadStory,
    DownloadVideo,
)
