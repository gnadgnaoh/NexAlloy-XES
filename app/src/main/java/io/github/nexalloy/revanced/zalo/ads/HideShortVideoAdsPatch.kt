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
    val resolved = mutableListOf<Pair<String, String>>()
    val unresolved = mutableListOf<String>()

    fun resolve(label: String, classNames: () -> List<String>) {
        runCatching(classNames)
            .onSuccess { names -> names.forEach { resolved += label to it } }
            .onFailure { unresolved += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    resolve("outstream") { listOf(::outstreamAdsLayoutFingerprint.dexMethod.className) }
    resolve("adsTemplate") { listOf(::adsTemplateLayoutFingerprint.dexMethod.className) }
    resolve("adsNative") { listOf(::adsNativeLayoutFingerprint.dexMethod.className) }
    resolve("advertisingItem") { ::advertisingItemFingerprints.dexMethodList.map { it.className } }

    val hookFailures = mutableListOf<String>()
    var hooked = 0

    for ((label, className) in resolved.distinctBy { it.second }) {
        try {
            val cls = classLoader.loadClass(className)

            if (cls.isInterface) {
                Logger.printDebug { "[Zalo] $label -> $className (interface, skipped)" }
                continue
            }

            val constructors = cls.declaredConstructors
            check(constructors.isNotEmpty()) { "no declared constructor" }

            constructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hookMethod {
                    after { param -> (param.thisObject as? View)?.keepHiddenAsAd() }
                }
            }

            hooked++
            Logger.printInfo { "[Zalo] $label -> $className (${constructors.size} ctor)" }
        } catch (t: Throwable) {
            hookFailures += "$label -> $className (${t.javaClass.simpleName}: ${t.message})"
        }
    }

    if (unresolved.isNotEmpty() || hookFailures.isNotEmpty()) {
        Logger.printInfo {
            "[Zalo] Zalo Video ads, degraded: unresolved=[${unresolved.joinToString("; ")}] " +
                    "hookFailed=[${hookFailures.joinToString("; ")}]"
        }
    }

    check(hooked > 0) {
        "Zalo Video: no ad target could be hooked. " +
                "unresolved=[${unresolved.joinToString("; ")}] " +
                "hookFailed=[${hookFailures.joinToString("; ")}]"
    }
}
