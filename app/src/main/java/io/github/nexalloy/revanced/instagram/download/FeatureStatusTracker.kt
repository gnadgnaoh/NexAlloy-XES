package io.github.nexalloy.revanced.instagram.download

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Records which hooks actually resolved their target, so a failure to find an obfuscated
 * method is visible in the log instead of failing silently. InstaEclipse surfaced this in
 * its own UI; NexAlloy has no equivalent screen, so the state is only kept in memory and
 * logged once per hook.
 */
object FeatureStatusTracker {

    private val HOOKED: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap())

    fun setHooked(feature: String) {
        if (HOOKED.put(feature, true) == null) {
            ModuleLog.line("(NA|DL) hooked: $feature")
        }
    }

    /** Kept for call-site compatibility; the string resource is only used by InstaEclipse's UI. */
    @Suppress("UNUSED_PARAMETER")
    fun setEnabled(feature: String, labelRes: Int) {
        setHooked(feature)
    }

    fun isHooked(feature: String): Boolean = HOOKED[feature] == true
}
