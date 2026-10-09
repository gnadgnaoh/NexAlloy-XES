package io.github.nexalloy.morphe.youtube.layout.shortsnoresume

import app.morphe.extension.youtube.patches.DisableShortsResumingOnStartupPatch
import io.github.nexalloy.morphe.music.misc.playservice.versionCheckPatch
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.insertLiteralOverride
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_03_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_30_or_greater
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook

val DisableShortsResumingOnStartup = patch(
    name = "Disable Shorts resuming on startup",
    description = "Adds an option to disable Shorts from resuming on app startup when Shorts were last being watched.",
) {
    dependsOn(versionCheckPatch)

    PreferenceScreen.SHORTS.addPreferences(
        SwitchPreference("morphe_disable_shorts_resuming_on_startup"),
    )

    if (is_21_03_or_greater) {
        (if (is_21_30_or_greater) UserWasInShortsEvaluateFingerprint
        else UserWasInShortsEvaluateLegacyFingerprint).hookMethod(
            scopedHook(
                UserWasInShortsEvaluateAnchorFingerprint.method
            ) {
                after {
                    it.result =
                        DisableShortsResumingOnStartupPatch.disableShortsResumingOnStartup(it.result as Boolean)
                }
            })
    } else {
        // TODO
    }

    insertLiteralOverride(
        45358360L,
        DisableShortsResumingOnStartupPatch::disableShortsResumingOnStartup
    )
}
