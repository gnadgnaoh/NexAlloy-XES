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
    val unitTypeEnum = ::searchResultUnitTypeEnumFingerprint.clazz

    val inspector = SearchResultUnitInspector(unitTypeEnum)

    hookSearchResultUnitList(::searchResultUnitListMethodFingerprint.method, inspector)
    hookSearchAdsLoadedState(::searchAdsLoadedStateConstructorFingerprint.member)

    ::searchAdComponentRenderMethodsFingerprint.dexMethodList.forEach { dm ->
        hookSearchAdComponentRender(dm.toMethod())
    }
}
