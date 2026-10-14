package io.github.nexalloy.revanced.zalo.ads

import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.collapseAsAd

val HideFeedZInstantAds = patch(
    name = "Hide feed ads",
    description = "Collapses sponsored zinstant items in the Nhật ký feed to zero height.",
) {
    ::feedAdsLayoutHeightFingerprint.hookMethod {
        before { param -> param.result = 0 }
    }

    val className = ::feedAdsBindFingerprint.dexMethod.className
    classLoader.loadClass(className)
        .getDeclaredMethod("onAttachedToWindow")
        .hookMethod {
            after { param -> (param.thisObject as? View)?.collapseAsAd() }
        }
}
