package io.github.nexalloy.morphe.youtube.misc.whitelist

import app.morphe.extension.youtube.settings.preference.ChannelWhitelistPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.NonInteractivePreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.ad.HideAds
import io.github.nexalloy.morphe.youtube.layout.flyout.flyoutPatch
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.morphe.youtube.video.speed.remember.RememberPlaybackSpeed
import io.github.nexalloy.morphe.youtube.video.speed.settingsMenuVideoSpeedGroup
import io.github.nexalloy.patch

/* unused */
val channelWhitelistPatch = patch(
    name = "Channel whitelist",
    description = "Adds options to allow whitelisting specific channels to show ads or override playback speeds."
) {
    dependsOn(
        flyoutPatch,
        HideAds,
        RememberPlaybackSpeed
    )

    PreferenceScreen.ADS.addPreferences(
        NonInteractivePreference(
            key = "morphe_ads_channel_whitelist",
            tag = ChannelWhitelistPreference::class.java,
            selectable = true
        )
    )

    settingsMenuVideoSpeedGroup.add(
        NonInteractivePreference(
            key = "morphe_playback_speed_channel_whitelist",
            tag = ChannelWhitelistPreference::class.java,
            selectable = true
        )
    )

    PreferenceScreen.FEED.addPreferences(
        SwitchPreference("morphe_ads_channel_whitelist_flyout_menu", summary = true),
        SwitchPreference("morphe_playback_speed_channel_whitelist_flyout_menu", summary = true)
    )
}
