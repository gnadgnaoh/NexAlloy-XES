package io.github.nexalloy.morphe.tiktok.shared

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod

/*
 * Why the TikTok fingerprints go through [cacheable].
 *
 * Several hook points legitimately resolve to nothing on a given install: a feature split whose
 * base.apk does not contain the class, a filter TikTok has not copied into that build yet, a log
 * string that was renamed. DexKit does cache such a result, but it caches it as an *empty* list,
 * which SharedPrefCache stores as "" and reads back as "nothing cached"
 * (`takeIf(String::isNotBlank)`). Single-method fingerprints are worse: the default
 * CacheFailurePolicy.NONE never records a miss at all.
 *
 * So every unresolved hook point re-ran its query on every cold start - and because the DexKit
 * bridge is created lazily on the first cache miss, a single one of them re-opened and re-parsed
 * all of TikTok's dex files. That is what kept startup at ~10s even right after a force close.
 *
 * Fixing this in SharedPrefCache would change caching for every app the module patches, so it is
 * handled here instead, for TikTok only: "found nothing" is recorded as a one-entry list holding a
 * marker method, which caches like any ordinary result. The next launch is then a
 * SharedPreferences read, the bridge is never created, and [realMatches] drops the marker again
 * before anything is hooked.
 *
 * The helpers started out private to ads/Fingerprints.kt; they live here since the privacy,
 * download and link patches need the same behaviour.
 */

private const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

/**
 * A no-argument method declared on the Aweme Gson model, preferring `isAd()`. No TikTok fingerprint
 * resolves to a no-argument method of Aweme (they all require parameters, or pin the declaring
 * class elsewhere), so the marker can always be told apart from a real match.
 */
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

/** Runs [find] and turns an empty or failed result into something the cache can store. */
internal fun DexKitBridge.cacheable(find: DexKitBridge.() -> List<MethodData>): List<MethodData> =
    runCatching { find() }.getOrDefault(emptyList()).ifEmpty { notFoundMarker() }

/**
 * Drops the not-found marker from a resolved result, leaving only real matches. [cacheable] only
 * ever stores the marker on its own, so a longer list is a real result and is kept whole, even if
 * one of its entries happens to be a no-argument Aweme method (a caller list can contain one).
 */
internal fun List<DexMethod>.realMatches(): List<DexMethod> =
    if (size == 1 && single().let { it.className == AWEME_CLASS && it.paramTypeNames.isEmpty() }) emptyList() else this
