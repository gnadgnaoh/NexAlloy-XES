package io.github.nexalloy.revanced.zalo.ads

import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.keepHiddenAsAd

val HideMessageZinstantAds = patch(
    name = "Hide message ads",
    description = "Hides the Zinstant ad row in the Messages (Tin nhắn) tab and skips " +
        "loading its content.",
) {
    val adView = classLoader.loadClass(ZINSTANT_AD_ITEM_VIEW_CLASS)

    adView.declaredConstructors.also {
        check(it.isNotEmpty()) { "${adView.name} declares no constructor" }
    }.forEach { constructor ->
        constructor.hookMethod {
            after { param -> (param.thisObject as? View)?.keepHiddenAsAd() }
        }
    }

    ::zinstantAdItemBindFingerprint.hookMethod {
        before { param ->
            (param.thisObject as? View)?.keepHiddenAsAd()
            param.result = null
        }
    }
}
