package io.github.nexalloy.morphe.tiktok.externalbrowser

import java.net.URLDecoder
import java.util.Locale

internal object LinkTarget {

    private val LINK_SAFETY_HOSTS = setOf(
        "www.tiktok.com", "www.tiktoklinksafety.us", "www.tiktoklinksafety.eu", "www.tiktoklinksafety.com",
    )

    private val NON_WEB_SCHEMES = setOf(
        "mailto", "tel", "sms", "smsto", "mms", "geo", "javascript", "data", "file", "content",
        "market", "intent", "about", "blob",
    )

    private val OPAQUE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+\\-]*:(?!\\d+(?:[/?#]|$)).*", RegexOption.DOT_MATCHES_ALL)

    fun resolve(source: String?): String? {
        var value = source?.trim()
        repeat(4) {
            val current = value?.takeIf { it.isNotEmpty() } ?: return null
            val schemeEnd = current.indexOf("://")
            val hasHierarchicalScheme =
                schemeEnd > 0 && current.substring(0, schemeEnd).all { it.isLetterOrDigit() || it in "+-." }
            if (!hasHierarchicalScheme &&
                (OPAQUE_SCHEME.matches(current) || current.substringBefore(':').lowercase(Locale.ROOT) in NON_WEB_SCHEMES)
            ) return null
            if (!hasHierarchicalScheme) {
                value = "https://$current"
                return@repeat
            }
            val scheme = current.substring(0, schemeEnd).lowercase(Locale.ROOT)
            if (scheme == "aweme") {
                value = queryParameter(current, "url")
                return@repeat
            }
            if (scheme != "http" && scheme != "https") return null
            val authority = authorityOf(current)
            val host = authority.substringAfterLast('@').substringBefore(':')
            if (host.isBlank()) return null
            if (isLinkSafetyRedirect(scheme, authority, host, pathOf(current))) {
                val target = queryParameter(current, "target")
                if (target != null) {
                    value = target
                    return@repeat
                }
            }
            return current
        }
        return null
    }

    private fun isLinkSafetyRedirect(scheme: String, authority: String, host: String, path: String): Boolean {
        if (scheme != "https" || '@' in authority) return false
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && port != "443") return false
        return path.startsWith("/link/") && host.lowercase(Locale.ROOT) in LINK_SAFETY_HOSTS
    }

    private fun authorityOf(url: String): String {
        val start = url.indexOf("://") + 3
        val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
        return url.substring(start, end)
    }

    private fun pathOf(url: String): String {
        val start = url.indexOf("://") + 3
        val pathStart = url.indexOfAny(charArrayOf('/', '?', '#'), start)
        if (pathStart < 0 || url[pathStart] != '/') return ""
        val end = url.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) url.length else it }
        return url.substring(pathStart, end)
    }

    fun queryParameter(url: String, name: String): String? {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key != name) continue
            val raw = pair.substringAfter('=', "")
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        }
        return null
    }
}
