package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.SearchResultUnitInspector
import io.github.nexalloy.revanced.facebook.hookSearchAdComponentRender
import io.github.nexalloy.revanced.facebook.hookSearchAdsLoadedState
import io.github.nexalloy.revanced.facebook.hookSearchResultUnitList

/**
 * Sponsored results on the search results page.
 *
 * A fourth surface, independent of the news feed, the profile timeline and Reels: search
 * runs its own query, builds its own item list from its own connection and draws it with
 * its own components. Nothing in [HideFacebookAds] is reachable from it, which is why a
 * search for any term still opened with a "Sponsored" post above the real results while
 * every feed-side filter was working correctly.
 *
 * Detection is Facebook's own labelling. Each search result carries a type enum, and the
 * advertisement kinds are named as such — SEARCH_ADS, TOP_POSITION_SEARCH_ADS,
 * MARKETPLACE_SEARCH_ADS and a handful more, sitting among roughly 570 organic kinds. A
 * unit whose type is in that set is an advertisement; a unit whose type is not is left
 * exactly as it arrived. As on the profile timeline, that asymmetry is the point: a
 * missed ad is the worst case, never an empty page.
 *
 * Three layers, in order of how early they act:
 *
 *   list  - every result the page shows is built by one method; ad-typed units are
 *           dropped there, so no slot is ever reserved for one
 *   state - the top-position ad arrives from a separate query that bypasses that list;
 *           its response payload is emptied, which is the same thing the page sees
 *           whenever the server has no ad to sell
 *   render- the components that exist only to draw a search ad refuse to draw, covering
 *           any surface a future build routes around the first two
 */
val HideSearchAds = patch(
    name = "Hide search ads",
    description = "Removes sponsored results from Facebook search, identified by the result's own advertisement type. Organic results are left untouched.",
) {
    // ── Readiness gate ────────────────────────────────────────────────────────
    //
    // Search ships in Superpack-compressed secondary dex like the feed does, so at
    // Application.onCreate a DexKit scan can legitimately see none of it. Every hook
    // below is wrapped in runCatching, which would otherwise let this patch report
    // success while having hooked nothing. The unit type enum exists on every build that
    // has search at all, so failing to resolve it means "not ready yet": throwing here
    // makes KatanaDexGate re-run the patch once more dex is installed, and re-running is
    // safe because each hook installer deduplicates by method.
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
