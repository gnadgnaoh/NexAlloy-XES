package io.github.nexalloy.revanced.zalo

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.zalo.ads.DisableSponsoredPlacements
import io.github.nexalloy.revanced.zalo.ads.FilterFeedAds
import io.github.nexalloy.revanced.zalo.ads.HideFeedZInstantAds
import io.github.nexalloy.revanced.zalo.ads.HideShortVideoAds
import io.github.nexalloy.revanced.zalo.ads.HideStoryAds
import io.github.nexalloy.revanced.zalo.ads.SkipFeedAdsBinding
import io.github.nexalloy.revanced.zalo.adtima.DisableAdtimaAdRequests
import io.github.nexalloy.revanced.zalo.adtima.DisableAdtimaOfflineAdsAndTracking
import io.github.nexalloy.revanced.zalo.adtima.DisableAdtimaVideoAdRequests
import io.github.nexalloy.revanced.zalo.adtima.OptOutAdtimaAdTracking
import io.github.nexalloy.revanced.zalo.adtima.RemoveAdtimaGoogleNetworks
import io.github.nexalloy.revanced.zalo.calls.AutoRecordCalls
import io.github.nexalloy.revanced.zalo.media.KeepExpiredMediaAccessible
import io.github.nexalloy.revanced.zalo.media.PreferOriginalPhotoQuality
import io.github.nexalloy.revanced.zalo.privacy.BlockSeenStatus
import io.github.nexalloy.revanced.zalo.privacy.BlockTypingStatus
import io.github.nexalloy.revanced.zalo.telemetry.DisableCrashlytics
import io.github.nexalloy.revanced.zalo.tracking.DisableAdsTracking
import io.github.nexalloy.revanced.zalo.backup.EnableDrivePhotoBackup

val ZaloPatches = arrayOf<Patch>(
    HideFeedZInstantAds,
    SkipFeedAdsBinding,
    FilterFeedAds,
    HideStoryAds,
    HideShortVideoAds,
    DisableSponsoredPlacements,
    DisableAdtimaAdRequests,
    DisableAdtimaVideoAdRequests,
    DisableAdsTracking,
    DisableAdtimaOfflineAdsAndTracking,
    OptOutAdtimaAdTracking,
    RemoveAdtimaGoogleNetworks,
    DisableCrashlytics,
    AutoRecordCalls,
    BlockSeenStatus,
    BlockTypingStatus,
    PreferOriginalPhotoQuality,
    KeepExpiredMediaAccessible,
    EnableDrivePhotoBackup,
)
