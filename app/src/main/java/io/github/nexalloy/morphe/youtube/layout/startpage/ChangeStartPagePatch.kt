package io.github.nexalloy.morphe.youtube.layout.startpage

import android.content.Intent
import app.morphe.extension.shared.settings.preference.SortedListPreference
import app.morphe.extension.youtube.patches.ChangeStartPagePatch
import io.github.nexalloy.morphe.shared.misc.settings.preference.ListPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook


val changeStartPagePatch = patch(
    name = "Change start page",
    description = "Adds an option to set which page the app opens in instead of the homepage.",
) {
    PreferenceScreen.GENERAL.addPreferences(
        noTitleUnsortedPreferenceCategory(
            ListPreference(
                key = "morphe_change_start_page",
                tag = SortedListPreference::class.java
            )
        )
    )

    // Hook browseId.
    BrowseIdFingerprint.hookMethod(scopedHook(::browserIdProtoBuilder.member){
        before {
            it.args[0] = ChangeStartPagePatch.overrideBrowseId(it.args[0] as String)
        }
    })

    // There is no browserId assigned to Shorts and Search.
    // Just hook the Intent action.
    IntentActionFingerprint.hookMethod {
        before {
            ChangeStartPagePatch.overrideIntentAction(it.args[0] as Intent)
        }
    }

}