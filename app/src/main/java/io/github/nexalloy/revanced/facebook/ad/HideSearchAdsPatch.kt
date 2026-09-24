package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.SearchResultUnitInspector
import io.github.nexalloy.revanced.facebook.hookSearchAdComponentRender
import io.github.nexalloy.revanced.facebook.hookSearchAdsLoadedState
import io.github.nexalloy.revanced.facebook.hookSearchResultUnitList

val HideSearchAds = patch(
    name = "Hide search ads",
    description = "Removes sponsored results from Facebook search, identified by the result's own advertisement type. Organic results are left untouched.",
) {
    val unitTypeEnum = runCatching { ::searchResultUnitTypeEnumFingerprint.clazz }.getOrElse {
        error("Facebook search dex is not visible yet - deferring patch")
    }

    val inspector = SearchResultUnitInspector(unitTypeEnum)

    // ── 1. Results list ───────────────────────────────────────────────────────

    runCatching {
        hookSearchResultUnitList(::searchResultUnitListMethodFingerprint.method, inspector)
    }

    // ── 2. Top-position ads query result ──────────────────────────────────────

    runCatching {
        hookSearchAdsLoadedState(::searchAdsLoadedStateConstructorFingerprint.member)
    }

    // ── 3. Ad-only component renders ──────────────────────────────────────────

    ::searchAdComponentRenderMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookSearchAdComponentRender(dm.toMethod()) }
    }
}
