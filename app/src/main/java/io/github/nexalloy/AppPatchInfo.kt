package io.github.nexalloy

import io.github.nexalloy.hoodles.morphe.alltrails.AllTrailsPatches
import io.github.nexalloy.hoodles.morphe.protonvpn.ProtonVpnPatches
import io.github.nexalloy.v4n1x.morphe.soundcloud.SoundCloudPatches
import io.github.nexalloy.morphe.google.GoogleDiscoverPatches
import io.github.nexalloy.morphe.music.YTMusicPatches
import io.github.nexalloy.morphe.reddit.RedditPatches
import io.github.nexalloy.morphe.youtube.YouTubePatches
import io.github.nexalloy.revanced.googlephotos.GooglePhotosPatches
import io.github.nexalloy.revanced.instagram.InstagramPatches
import io.github.nexalloy.revanced.threads.ThreadsPatches
import io.github.nexalloy.revanced.facebook.FacebookPatches
import io.github.nexalloy.revanced.photomath.PhotomathPatches
import io.github.nexalloy.revanced.strava.StravaPatches
import io.github.nexalloy.revanced.zalo.ZaloPatches 
import io.github.nexalloy.mrxsin.gmail.GMAIL_PACKAGE_NAME
import io.github.nexalloy.mrxsin.gmail.GmailPatches
import io.github.nexalloy.morphe.twitter.TwitterPatches
import io.github.nexalloy.morphe.tiktok.TIKTOK_ASIA_PACKAGE_NAME
import io.github.nexalloy.morphe.tiktok.TIKTOK_PACKAGE_NAME
import io.github.nexalloy.morphe.tiktok.TikTokPatches
import io.github.nexalloy.morphe.twitter.utils.Constants.PACKAGE_NAME as TWITTER_PACKAGE_NAME
import io.github.nexalloy.v4n1x.morphe.soundcloud.shared.Constants.PACKAGE_NAME as SOUNDCLOUD_PACKAGE_NAME

enum class DexSource { APK_PATH, CLASS_LOADER }

class AppPatchInfo(
    val appName: String,
    val packageName: String,
    val patches: Array<Patch>,
    val dexSource: DexSource = DexSource.APK_PATH,
)

val appPatchConfigurations = listOf(
    AppPatchInfo("Proton VPN", "ch.protonvpn.android", ProtonVpnPatches),
    AppPatchInfo("Zalo", "com.zing.zalo", ZaloPatches),
    AppPatchInfo("YouTube", "com.google.android.youtube", YouTubePatches),
    AppPatchInfo("YT Music", "com.google.android.apps.youtube.music", YTMusicPatches),
    AppPatchInfo("Reddit", "com.reddit.frontpage", RedditPatches),
    AppPatchInfo("Google Photos", "com.google.android.apps.photos", GooglePhotosPatches),
    AppPatchInfo("Photomath", "com.microblink.photomath", PhotomathPatches),
    AppPatchInfo("Instagram", "com.instagram.android", InstagramPatches),
    AppPatchInfo("Threads", "com.instagram.barcelona", ThreadsPatches),
    AppPatchInfo("Strava", "com.strava", StravaPatches),
    AppPatchInfo("AllTrails", "com.alltrails.alltrails", AllTrailsPatches),
    AppPatchInfo("SoundCloud", SOUNDCLOUD_PACKAGE_NAME, SoundCloudPatches),
    AppPatchInfo("Facebook", "com.facebook.katana", FacebookPatches, DexSource.CLASS_LOADER),
    AppPatchInfo("Google (Discover)", "com.google.android.googlequicksearchbox", GoogleDiscoverPatches),
    AppPatchInfo("Twitter/X", TWITTER_PACKAGE_NAME, TwitterPatches),
    AppPatchInfo("Gmail", GMAIL_PACKAGE_NAME, GmailPatches),
    AppPatchInfo("TikTok", TIKTOK_PACKAGE_NAME, TikTokPatches),
    AppPatchInfo("TikTok (Asia)", TIKTOK_ASIA_PACKAGE_NAME, TikTokPatches),
)

val patchesByPackage = appPatchConfigurations.associate { it.packageName to it.patches }
val dexSourceByPackage = appPatchConfigurations.associate { it.packageName to it.dexSource }
