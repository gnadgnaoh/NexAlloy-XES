package io.github.nexalloy.revanced.facebook.link

import android.net.Uri
import java.net.URLDecoder
import java.util.Locale

internal object LinkShim {

    private val INTERNAL_HOSTS = listOf("facebook.com", "fb.com", "messenger.com", "meta.com")
    private val SHORT_LINK_HOSTS = listOf("fb.watch", "fbwat.ch", "fb.me", "fb.gg", "fb.audio", "m.me")

    private val WARNING_PATHS = listOf("/flx/warn/", "/fblynx/warn/", "/si/linkclick/warn/")

    private val PATH_SHIM = Regex("^/l/([a-zA-Z0-9_.-]*)(?:;|/)(.*)$")

    private const val MAX_SHIMS = 8

    private val EVERY_HOST = setOf(
        "fbclid", "mibextid", "fb_action_ids", "fb_action_types", "fb_ref", "fb_source",
        "action_object_map", "action_type_map", "action_ref_map",
    )

    private val FACEBOOK_ONLY = setOf(
        "sfnsn", "extid", "rdid", "share_url", "__tn__", "__so__", "__rv__", "_ft_", "refsrc", "fref",
        "refid", "pnref", "ls_ref", "hc_ref", "hc_location", "dti", "acontext", "action_history",
        "video_source", "comment_tracking", "m_entstream_source", "idorvanity", "paipv", "wtsid",
        "_rdr", "_rdc", "rdr", "rdc", "referral_story_type", "gd_impression_id", "external_ref",
        "store_visit_source",
    )

    private val FACEBOOK_PAIRS = setOf("ref=share", "s=yWDuG2", "fs=e")
    private val FACEBOOK_NUMBERED = Regex("__(?:cft|xts)__\\[\\d+]")
    private val FACEBOOK_HOSTS = listOf("facebook.com", "fb.com", "fb.watch", "fb.me")

    private val URL_IN_TEXT = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun isWebUrl(uri: Uri) = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

    private fun isOn(host: String?, domains: List<String>): Boolean {
        val lower = host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        return domains.any { lower == it || lower.endsWith(".$it") }
    }

    fun staysInApp(uri: Uri) = isOn(uri.host, INTERNAL_HOSTS) || isOn(uri.host, SHORT_LINK_HOSTS)

    fun unwrap(uri: Uri): Uri {
        var target = uri
        repeat(MAX_SHIMS) { target = shimDestination(target) ?: return target }
        return target
    }

    private fun shimDestination(uri: Uri): Uri? {
        if (uri.isOpaque) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path ?: return null
        val subdomain = host.endsWith(".facebook.com")
        val facebook = subdomain || host == "facebook.com"
        val messenger = host == "messenger.com" || host.endsWith(".messenger.com")

        var wrapped = when {
            path == "/l.php" -> if (facebook || messenger || host == "fb.me") uri.getQueryParameter("u") else null
            facebook && path.startsWith("/si/ajax/l/") -> uri.getQueryParameter("u")
            facebook && path.startsWith("/l/") -> PATH_SHIM.matchEntire(path)?.groupValues?.get(2)
            subdomain && uri.scheme.equals("https", true) && WARNING_PATHS.any { path.startsWith(it, true) } ->
                uri.getQueryParameter("u")
            else -> null
        } ?: return null
        if (facebook && path.startsWith("/l/") && Uri.parse(wrapped).scheme == null) wrapped = "http://$wrapped"
        val target = Uri.parse(wrapped)
        return target.takeIf { isWebUrl(it) && !it.host.isNullOrEmpty() }
    }

    private fun lowerCased(uri: Uri): Uri {
        val normalized = uri.normalizeScheme()
        val authority = normalized.encodedAuthority ?: return normalized
        val at = authority.lastIndexOf('@')
        val lowered = authority.substring(0, at + 1) + authority.substring(at + 1).lowercase(Locale.ROOT)
        return if (lowered == authority) normalized else normalized.buildUpon().encodedAuthority(lowered).build()
    }

    fun destination(uri: Uri): Uri = lowerCased(Uri.parse(clean(unwrap(uri).toString())))

    fun cleanText(text: String): String {
        if (!text.contains("http", ignoreCase = true)) return text
        return URL_IN_TEXT.replace(text) { clean(it.value) }
    }

    fun clean(url: String): String = runCatching { cleaned(url) }.getOrDefault(url)

    private fun cleaned(url: String): String {
        val colon = url.indexOf(':')
        if (colon <= 0) return url
        val scheme = url.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return url

        val fragment = url.indexOf('#')
        val end = if (fragment < 0) url.length else fragment
        val query = url.indexOf('?')
        if (query < 0 || query > end) return url

        val facebook = isOn(hostOf(url, colon + 1, query), FACEBOOK_HOSTS)
        val pairs = url.substring(query + 1, end).split("&")
        val kept = pairs.filterNot { pair ->
            val key = keyOf(pair)
            key in EVERY_HOST ||
                (facebook && (key in FACEBOOK_ONLY || FACEBOOK_NUMBERED.matches(key) || pair in FACEBOOK_PAIRS))
        }
        if (kept.size == pairs.size) return url

        val rest = kept.joinToString("&")
        return url.substring(0, if (rest.isEmpty()) query else query + 1) + rest + url.substring(end)
    }

    private fun keyOf(pair: String): String {
        val key = pair.substringBefore('=')
        if ('%' !in key) return key
        return runCatching { URLDecoder.decode(key.replace("+", "%2B"), "UTF-8") }.getOrDefault(key)
    }

    private fun hostOf(url: String, from: Int, query: Int): String? {
        if (!url.startsWith("//", from)) return null
        val start = from + 2
        val slash = url.indexOf('/', start)
        val stop = if (slash in 0 until query) slash else query
        val host = url.substring(start, stop).substringAfterLast('@')
        return if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
    }
}
