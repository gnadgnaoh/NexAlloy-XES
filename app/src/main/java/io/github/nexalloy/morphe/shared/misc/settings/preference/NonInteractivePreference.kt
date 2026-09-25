@file:Suppress("DEPRECATION", "DiscouragedApi")

package io.github.nexalloy.morphe.shared.misc.settings.preference

import android.content.Context
import android.preference.Preference
import android.preference.PreferenceManager


/**
 * A non-interactive preference.
 *
 * Typically used to present static text, but also used for custom extension code that responds to taps.
 *
 * @param key The preference key.
 * @param summaryKey The preference summary key.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param tag The tag or full class name of the preference.
 * @param selectable If the preference is selectable and responds to tap events.
 */
@Suppress("MemberVisibilityCanBePrivate")
class NonInteractivePreference(
    key: String,
    titleKey: String = "${key}_title",
    summaryKey: String? = "${key}_summary",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null,
    tag: Class<out Preference> = Preference::class.java,
    val selectable: Boolean = false,
    val dependency: String? = null,
) : BasePreference(key, titleKey, summaryKey, icon, iconBold, layout, tag) {

    override fun build(ctx: Context, prefMgr: PreferenceManager): Preference {
        return super.build(ctx, prefMgr).apply {
            isSelectable = selectable
            this@NonInteractivePreference.dependency?.let { dependency = it }
        }
    }
}
