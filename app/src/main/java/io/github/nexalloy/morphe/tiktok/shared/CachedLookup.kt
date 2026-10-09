package io.github.nexalloy.morphe.tiktok.shared

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod

private const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

private fun DexKitBridge.notFoundMarker(): List<MethodData> = runCatching {
    findMethod {
        matcher {
            declaredClass = AWEME_CLASS
            name = "isAd"
            paramCount = 0
        }
    }.ifEmpty {
        findMethod {
            matcher {
                declaredClass = AWEME_CLASS
                paramCount = 0
            }
        }
    }.take(1)
}.getOrDefault(emptyList())

internal fun DexKitBridge.cacheable(find: DexKitBridge.() -> List<MethodData>): List<MethodData> =
    runCatching { find() }.getOrDefault(emptyList()).ifEmpty { notFoundMarker() }

internal fun List<DexMethod>.realMatches(): List<DexMethod> =
    if (size == 1 && single().let { it.className == AWEME_CLASS && it.paramTypeNames.isEmpty() }) emptyList() else this
