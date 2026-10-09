package io.github.nexalloy.morphe.youtube.layout.dearrow

import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch
import app.morphe.extension.youtube.settings.preference.DeArrowAboutPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.ListPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.NonInteractivePreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.TextPreference
import io.github.nexalloy.morphe.youtube.misc.imageurlhook.addImageUrlErrorCallbackHook
import io.github.nexalloy.morphe.youtube.misc.imageurlhook.addImageUrlHook
import io.github.nexalloy.morphe.youtube.misc.imageurlhook.addImageUrlSuccessCallbackHook
import io.github.nexalloy.morphe.youtube.misc.imageurlhook.cronetImageUrlHookPatch
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch

val deArrowPatch = patch(
    name = "DeArrow",
    description = "Adds options to replace video thumbnails and titles using the DeArrow API, " +
            "or replace video thumbnails with image captures from the video.",
) {
    dependsOn(
        cronetImageUrlHookPatch,
        // Thumbnails that fail to load are loaded again by mounting the Litho views again.
//        lithoRelayoutPatch,
        // Titles are replaced by the same hooks that restore the original titles.
//        restoreOriginalTitlesPatch,
    )
    val entries = "morphe_dearrow_thumbnail_options_entries"
    val values = "morphe_dearrow_thumbnail_options_entry_values"
    PreferenceScreen.DEARROW.addPreferences(
//        SwitchPreference("morphe_dearrow_titles", summary = true),
//        SwitchPreference("morphe_dearrow_titles_icon", summary = true),
        ListPreference(
            key = "morphe_dearrow_thumbnail_home",
            entriesKey = entries,
            entryValuesKey = values
        ),
        ListPreference(
            key = "morphe_dearrow_thumbnail_subscription",
            entriesKey = entries,
            entryValuesKey = values
        ),
        ListPreference(
            key = "morphe_dearrow_thumbnail_library",
            entriesKey = entries,
            entryValuesKey = values
        ),
        ListPreference(
            key = "morphe_dearrow_thumbnail_player",
            entriesKey = entries,
            entryValuesKey = values
        ),
        ListPreference(
            key = "morphe_dearrow_thumbnail_search",
            entriesKey = entries,
            entryValuesKey = values
        ),
        NonInteractivePreference(
            "morphe_dearrow_about",
            // Custom about preference with link to the DeArrow website.
            tag = DeArrowAboutPreference::class.java,
            selectable = true,
        ),
        SwitchPreference("morphe_dearrow_connection_toast", summary = true),
        TextPreference("morphe_dearrow_api_url"),
        NonInteractivePreference("morphe_dearrow_thumbnail_stills_about"),
        ListPreference("morphe_dearrow_thumbnail_stills_time"),
    )

    addImageUrlHook(DeArrowPatch::overrideImageURL)
    addImageUrlSuccessCallbackHook(DeArrowPatch::handleCronetSuccess)
    addImageUrlErrorCallbackHook(DeArrowPatch::handleCronetFailure)
}