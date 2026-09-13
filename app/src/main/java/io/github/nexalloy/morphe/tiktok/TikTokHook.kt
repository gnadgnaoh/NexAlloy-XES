package io.github.nexalloy.morphe.tiktok

import io.github.nexalloy.Patch
import io.github.nexalloy.morphe.tiktok.ads.HidePromotedMusicVideos
import io.github.nexalloy.morphe.tiktok.ads.RemoveFeedAds
import io.github.nexalloy.morphe.tiktok.captcha.HideCaptchaPopups
import io.github.nexalloy.morphe.tiktok.login.DisableLoginRequirement
import io.github.nexalloy.morphe.tiktok.login.FixGoogleLogin
import io.github.nexalloy.morphe.tiktok.screencapture.DisableScreenCaptureDetection
import io.github.nexalloy.morphe.tiktok.watermark.RemoveDownloadWatermark

const val TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
const val TIKTOK_ASIA_PACKAGE_NAME = "com.ss.android.ugc.trill"

val TikTokPatches = arrayOf<Patch>(
    // Ads
    RemoveFeedAds,
    HidePromotedMusicVideos,

    // Privacy
    DisableScreenCaptureDetection,

    // Account
    DisableLoginRequirement,
    FixGoogleLogin,

    // Interruptions
    HideCaptchaPopups,

    // Downloads
    RemoveDownloadWatermark,
)
