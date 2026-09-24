package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookBlockNull

val HideReelsShopping = patch(
    name = "Hide Reels shopping cards",
    description = "Removes the \"Shop now\" product cards overlaying promotional reels.",
) {
    runCatching { ::sponsoredPoolAddMethodFingerprint.method }.getOrElse {
        error("Facebook feed dex is not visible yet - deferring patch")
    }
    val renders = runCatching { ::reelsShoppingRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
    renders.forEach { dm -> runCatching { hookBlockNull(dm.toMethod()) } }
}
