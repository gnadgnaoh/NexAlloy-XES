package io.github.nexalloy.revanced.zalo.ads

import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.keepHiddenAsAd

val HideStoryAds = patch(
    name = "Hide story ads",
    description = "Hides sponsored items in the story viewer. Verify the story tray still " +
            "advances before relying on this.",
    use = false,
) {
    ::storyAdsBindFingerprint.hookMethod {
        after { param -> (param.thisObject as? View)?.keepHiddenAsAd() }
    }

    val cls = classLoader.loadClass(::storyAdsBindFingerprint.dexMethod.className)
    val constructors = cls.declaredConstructors
    check(constructors.isNotEmpty()) { "${cls.name} declares no constructor" }

    constructors.forEach { constructor ->
        constructor.isAccessible = true
        constructor.hookMethod {
            after { param -> (param.thisObject as? View)?.keepHiddenAsAd() }
        }
    }
}
