package io.github.nexalloy.revanced.instagram.download

import java.util.Collections
import java.util.LinkedHashMap

object FeatureStatusTracker {

    private val HOOKED: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap())

    fun setHooked(feature: String) {
        if (HOOKED.put(feature, true) == null) {
            ModuleLog.line("(NA|DL) hooked: $feature")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setEnabled(feature: String, labelRes: Int) {
        setHooked(feature)
    }

    fun isHooked(feature: String): Boolean = HOOKED[feature] == true
}
