package io.github.nexalloy.revanced.zalo.adtima

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val DisableAdtimaOfflineAdsAndTracking = patch(
    name = "Disable Adtima offline ads and tracking",
    description = "Keeps Adtima's offline (pre-cached) ad window closed and stops its " +
        "active-view / impression tracking hits.",
) {
    ::adtimaOfflineWindowFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
    ::adtimaTrackingGateFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
}

val OptOutAdtimaAdTracking = patch(
    name = "Opt out of Adtima ad tracking",
    description = "Tells the bundled Adtima SDK that ad tracking is limited and skips its " +
        "Google advertising-ID lookup. Complements the Adtima request blocks.",
) {
    val isLat = ::adtimaLimitAdTrackingField.field.apply { isAccessible = true }
    runCatching { isLat.setInt(null, 1) }

    ::adtimaLimitAdTrackingTaskFingerprint.hookMethod {
        before { param ->
            runCatching { isLat.setInt(null, 1) }
            param.result = null
        }
    }
}

private val GOOGLE_AD_NETWORKS = listOf("admob", "dfp", "ima")

val RemoveAdtimaGoogleNetworks = patch(
    name = "Remove Google ad networks from Adtima",
    description = "Always drops AdMob, Google Ad Manager and IMA from Adtima's mediation " +
        "list, whatever the server config says.",
) {
    val networks = ::adtimaSupportNetworkField.field.apply { isAccessible = true }

    fun dropGoogleNetworks() {
        val map = runCatching { networks.get(null) as? MutableMap<*, *> }.getOrNull() ?: return
        GOOGLE_AD_NETWORKS.forEach { map.remove(it) }
    }

    ::adtimaUpdateSupportNetworkFingerprint.hookMethod {
        after { dropGoogleNetworks() }
    }
    dropGoogleNetworks()
    Logger.printDebug { "[Zalo] Adtima Google networks removed" }
}
