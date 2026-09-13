package io.github.nexalloy.morphe.twitter.link.unshorten

import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import io.github.nexalloy.morphe.twitter.utils.Constants
import java.util.concurrent.ConcurrentHashMap

private val expansions = ConcurrentHashMap<String, String>()

private fun cacheKey(url: String): String =
    url.removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        .lowercase()

internal fun isShortLink(url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val key = cacheKey(url)
    return key == Constants.SHORT_LINK_HOST || key.startsWith("${Constants.SHORT_LINK_HOST}/")
}

internal fun rememberExpansion(shortUrl: String?, expanded: String?) {
    if (shortUrl.isNullOrEmpty() || expanded.isNullOrEmpty()) return
    if (shortUrl == expanded) return
    if (!isShortLink(shortUrl)) return
    expansions[cacheKey(shortUrl)] = expanded
}

internal fun expandShortLinkOrNull(url: String?): String? {
    if (url.isNullOrEmpty()) return null
    return expansions[cacheKey(url)]
}

internal fun withScheme(url: String): String {
    val lower = url.lowercase()
    return if (lower.startsWith("https://") || lower.startsWith("http://")) url else "https://$url"
}

/**
 * Replaces the display url and the t.co url of a UrlEntity constructor call
 * with the expanded url, and remembers the expansion so that later navigation
 * calls carrying only the t.co url can be rewritten too.
 */
internal fun unshortenArgs(
    param: MethodHookParam,
    displayIdx: Int,
    expandedIdx: Int,
    urlIdx: Int,
) {
    val expanded = param.args.getOrNull(expandedIdx) as? String ?: return
    if (expanded.isEmpty()) return

    rememberExpansion(param.args.getOrNull(urlIdx) as? String, expanded)

    param.args[displayIdx] = expanded
    param.args[urlIdx] = expanded
}

internal fun unshortenArgAt(param: MethodHookParam, index: Int) {
    val url = param.args.getOrNull(index) as? String ?: return
    if (!isShortLink(url)) return

    val target = withScheme(expandShortLinkOrNull(url) ?: return)
    if (target != url) param.args[index] = target
}
