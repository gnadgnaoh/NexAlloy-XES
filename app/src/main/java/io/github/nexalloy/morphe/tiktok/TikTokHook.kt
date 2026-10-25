package io.github.nexalloy.morphe.tiktok

import io.github.nexalloy.Patch
import io.github.nexalloy.morphe.tiktok.ads.HidePromotedMusicVideos
import io.github.nexalloy.morphe.tiktok.ads.HidePaidPartnerships
import io.github.nexalloy.morphe.tiktok.ads.HideShopAds
import io.github.nexalloy.morphe.tiktok.ads.SkipSplashAds
import io.github.nexalloy.morphe.tiktok.ads.RemoveFeedAds
import io.github.nexalloy.morphe.tiktok.captcha.HideCaptchaPopups
import io.github.nexalloy.morphe.tiktok.downloads.AllowRestrictedDownloads
import io.github.nexalloy.morphe.tiktok.externalbrowser.OpenLinksInBrowser
import io.github.nexalloy.morphe.tiktok.ghostmode.GhostMode
import io.github.nexalloy.morphe.tiktok.activestatus.HideActiveStatus
import io.github.nexalloy.morphe.tiktok.login.DisableLoginRequirement
import io.github.nexalloy.morphe.tiktok.login.FixGoogleLogin
import io.github.nexalloy.morphe.tiktok.screencapture.AllowScreenshots
import io.github.nexalloy.morphe.tiktok.screencapture.DisableScreenCaptureDetection
import io.github.nexalloy.morphe.tiktok.share.SanitizeShareLinks
import io.github.nexalloy.morphe.tiktok.telemetry.DisableTelemetry
import io.github.nexalloy.morphe.tiktok.watermark.RemoveDownloadWatermark
import io.github.nexalloy.morphe.tiktok.interaction.AlwaysUploadInHd
import io.github.nexalloy.morphe.tiktok.interaction.CopyBioAndIds
import io.github.nexalloy.morphe.tiktok.interaction.CopyCommentsWithoutUsername
import io.github.nexalloy.morphe.tiktok.interaction.ShowExactCounts
import io.github.nexalloy.morphe.tiktok.interaction.ShowFollowStatus
import io.github.nexalloy.morphe.tiktok.translation.DontAutoTranslateVideos
import io.github.nexalloy.morphe.tiktok.downloads.DownloadBestQuality
import io.github.nexalloy.morphe.tiktok.downloads.SaveProfilePictures
import io.github.nexalloy.morphe.tiktok.downloads.SaveCommentPhotos
import io.github.nexalloy.morphe.tiktok.downloads.ShareSheetSaves
import io.github.nexalloy.morphe.tiktok.translation.TranslateComments

const val TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
const val TIKTOK_ASIA_PACKAGE_NAME = "com.ss.android.ugc.trill"

val TikTokPatches = arrayOf<Patch>(
    RemoveFeedAds,
    SkipSplashAds,
    HideShopAds,
    HidePaidPartnerships,
    HidePromotedMusicVideos,

    DisableScreenCaptureDetection,
    AllowScreenshots,
    DisableTelemetry,
    GhostMode,
    HideActiveStatus,
    SanitizeShareLinks,
    OpenLinksInBrowser,

    DisableLoginRequirement,
    FixGoogleLogin,

    HideCaptchaPopups,

    RemoveDownloadWatermark,
    AllowRestrictedDownloads,

    CopyCommentsWithoutUsername,
    CopyBioAndIds,
    ShowExactCounts,
    ShowFollowStatus,
    AlwaysUploadInHd,
    TranslateComments,
    DontAutoTranslateVideos,
    DownloadBestQuality,
    ShareSheetSaves,
    SaveProfilePictures,
    SaveCommentPhotos,
)
