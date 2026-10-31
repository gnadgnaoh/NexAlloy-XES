package io.github.nexalloy.v4n1x.morphe.soundcloud

import io.github.nexalloy.v4n1x.morphe.soundcloud.analytics.DisableAnalytics
import io.github.nexalloy.v4n1x.morphe.soundcloud.consent.DisableConsent
import io.github.nexalloy.v4n1x.morphe.soundcloud.premium.EnablePremium

val SoundCloudPatches = arrayOf(
    EnablePremium,
    DisableAnalytics,
    DisableConsent,
)
