package io.github.nexalloy.revanced.zalo.ads

import android.view.View
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.keepHiddenAsAd

val HideShortVideoAds = patch(
    name = "Hide Zalo Video ads",
    description = "Hides ad containers, native ad layouts and outstream ads in Zalo Video.",
) {
    val targets = listOf(
        "outstream" to ::outstreamAdsLayoutFingerprint.dexMethod.className,
        "adsTemplate" to ::adsTemplateLayoutFingerprint.dexMethod.className,
        "adsNative" to ::adsNativeLayoutFingerprint.dexMethod.className,
    ) + ::advertisingItemFingerprints.dexMethodList.map { "advertisingItem" to it.className }

    var hooked = 0

    for ((label, className) in targets.distinctBy { it.second }) {
        val cls = classLoader.loadClass(className)

        if (cls.isInterface) {
            Logger.printDebug { "[Zalo] $label -> $className (interface, skipped)" }
            continue
        }

        val constructors = cls.declaredConstructors
        check(constructors.isNotEmpty()) { "$label -> $className: no declared constructor" }

        constructors.forEach { constructor ->
            constructor.isAccessible = true
            constructor.hookMethod {
                after { param -> (param.thisObject as? View)?.keepHiddenAsAd() }
            }
        }

        hooked++
        Logger.printInfo { "[Zalo] $label -> $className (${constructors.size} ctor)" }
    }

    check(hooked > 0) { "Zalo Video: no ad target could be hooked" }
}
