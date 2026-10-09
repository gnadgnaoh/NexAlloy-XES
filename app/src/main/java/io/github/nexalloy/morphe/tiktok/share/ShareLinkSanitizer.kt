package io.github.nexalloy.morphe.tiktok.share

import java.util.Locale

internal object ShareLinkSanitizer {

    private val TRACKING_KEYS = setOf(
        "_r", "_t", "_d", "u_code", "preview_pb", "language", "timestamp", "user_id", "sec_user_id",
        "sec_uid", "source", "ugbiz_name", "checksum", "is_from_webapp", "is_copy_url", "web_id",
        "tt_from", "enter_from", "enter_method", "social_share_type", "iid", "did", "device_id",
        "aid", "app", "app_name", "region", "carrier_region",
    )

    private val TRACKING_PREFIXES = listOf("utm_", "share_", "sp_", "sender_", "ug_", "ec_share")

    fun isTracking(key: String): Boolean {
        val k = key.lowercase(Locale.ROOT)
        return k in TRACKING_KEYS || TRACKING_PREFIXES.any { k.startsWith(it) }
    }

    fun isTikTokHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        return h == "tiktok.com" || h.endsWith(".tiktok.com")
    }

    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') { end = i; break }
        }
        var authority = url.substring(start, end)
        authority = authority.substringAfterLast('@')
        if (authority.startsWith("[")) return null
        return authority.substringBefore(':').ifEmpty { null }
    }

    fun sanitize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        val host = hostOf(url) ?: return url
        if (!isTikTokHost(host)) return url

        val fragmentAt = url.indexOf('#')
        val beforeFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val queryAt = beforeFragment.indexOf('?')
        if (queryAt < 0) return url

        val base = beforeFragment.substring(0, queryAt)
        val kept = beforeFragment.substring(queryAt + 1)
            .split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }
}
