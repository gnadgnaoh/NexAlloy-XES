package io.github.nexalloy.revanced.threads.tracking

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.util.Locale

val SanitizeTrackingLinks = patch(
    name = "Sanitize tracking links",
    description = "Removes tracking tags from Threads links you copy or share, " +
        "and turns short share links into the post's own link.",
) {
    val code = runCatching { ::mediaCodeFingerprint.method }.getOrNull()
    val user = runCatching { ::mediaUserFingerprint.method }.getOrNull()
    val username = runCatching { ::userNameFingerprint.method }.getOrNull()

    val mediaClass = classLoader.loadClass(MEDIA)
    runCatching {
        ::permalinkFetchesFingerprint.dexMethodList.forEach { m ->
            m.hookMethod {
                before { param ->
                    val media = param.args.firstOrNull { mediaClass.isInstance(it) } ?: return@before
                    LinkCleaner.remember(media)
                }
            }
        }
    }

    ::permalinkResponseParserFingerprint.hookMethod {
        after { param ->
            val response = param.result ?: return@after
            LinkCleaner.stringFields(response.javaClass).forEach { field ->
                val url = field.get(response) as? String ?: return@forEach
                var clean = LinkCleaner.clean(url)
                if (LinkCleaner.isShortLink(clean)) {
                    val media = LinkCleaner.recentMedia()
                    val postCode = runCatching { code?.invoke(media) as? String }.getOrNull()
                    val author = runCatching { username?.invoke(user?.invoke(media)) as? String }.getOrNull()
                    clean = LinkCleaner.ownLink(clean, author, postCode)
                }
                if (clean != url) field.set(response, clean)
            }
        }
    }

    ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java).hookMethod {
        before { param ->
            val clip = param.args[0] as? ClipData ?: return@before
            if (clip.itemCount == 0) return@before
            val text = clip.getItemAt(0)?.text?.toString() ?: return@before
            if (!text.contains("threads.com/") && !text.contains("threads.net/")) return@before
            val clean = LinkCleaner.cleanText(text)
            if (clean != text) param.args[0] = ClipData.newPlainText(clip.description?.label ?: "URL", clean)
        }
    }
}

internal object LinkCleaner {
    private val everyHost = setOf("fbclid", "igsh", "igshid", "igsi")
    private val metaOnly = setOf("xmt", "slof")
    private val metaHosts = listOf("threads.com", "threads.net", "instagram.com")
    private val shortHosts = setOf("threads.com", "www.threads.com", "threads.net", "www.threads.net")
    private val shortPath = Regex("/share/[A-Za-z0-9_-]+/?")
    private val usernamePattern = Regex("[A-Za-z0-9._]{1,30}")
    private val codePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val urlInText = Regex("https?://[^\\s]+")
    private const val RECENT_MS = 20_000L

    @Volatile
    private var last: Pair<WeakReference<Any>, Long>? = null

    fun remember(media: Any) {
        last = WeakReference(media) to SystemClock.elapsedRealtime()
    }

    fun recentMedia(): Any? {
        val (ref, at) = last ?: return null
        return if (SystemClock.elapsedRealtime() - at in 0..RECENT_MS) ref.get() else null
    }

    private val fields = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    fun stringFields(type: Class<*>): List<Field> = fields.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()
    }

    fun cleanText(text: String) = urlInText.replace(text) { clean(it.value) }

    fun clean(url: String): String = runCatching {
        val colon = url.indexOf(':')
        if (colon <= 0) return url
        val scheme = url.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return url
        val fragment = url.indexOf('#')
        val end = if (fragment < 0) url.length else fragment
        val query = url.indexOf('?')
        if (query < 0 || query > end) return url
        val meta = isMetaHost(host(url, colon + 1, query))
        var removed = false
        val kept = url.substring(query + 1, end).split("&").filter { pair ->
            val key = keyOf(pair)
            val tracking = key in everyHost || (meta && key in metaOnly)
            if (tracking) removed = true
            !tracking
        }
        if (!removed) return url
        val rest = kept.joinToString("&")
        url.substring(0, if (rest.isEmpty()) query else query + 1) + rest + url.substring(end)
    }.getOrDefault(url)

    fun isShortLink(url: String): Boolean = runCatching {
        val colon = url.indexOf(':')
        if (colon <= 0 || !url.startsWith("//", colon + 1)) return false
        val scheme = url.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val path = url.indexOf('/', colon + 3)
        if (path < 0) return false
        if (url.substring(colon + 3, path).lowercase(Locale.ROOT) !in shortHosts) return false
        shortPath.matches(url.substring(path, pathEnd(url, path)))
    }.getOrDefault(false)

    fun ownLink(url: String, author: String?, code: String?): String {
        if (author == null || code == null || !usernamePattern.matches(author) || !codePattern.matches(code)) return url
        val colon = url.indexOf(':')
        val path = url.indexOf('/', colon + 3)
        return clean(url.substring(0, path) + "/@" + author + "/post/" + code + url.substring(pathEnd(url, path)))
    }

    private fun pathEnd(url: String, path: Int): Int {
        var end = url.length
        val query = url.indexOf('?', path)
        val fragment = url.indexOf('#', path)
        if (query >= 0) end = query
        if (fragment in 0 until end) end = fragment
        return end
    }

    private fun keyOf(pair: String): String {
        val key = pair.substringBefore('=')
        if ('%' !in key) return key
        return runCatching { URLDecoder.decode(key.replace("+", "%2B"), "UTF-8") }.getOrDefault(key)
    }

    private fun host(url: String, from: Int, query: Int): String? {
        if (!url.startsWith("//", from)) return null
        val start = from + 2
        var stop = query
        val slash = url.indexOf('/', start)
        if (slash in 0 until stop) stop = slash
        val authority = url.substring(start, stop)
        return authority.substring(authority.lastIndexOf('@') + 1).substringBefore(':').lowercase(Locale.ROOT)
    }

    private fun isMetaHost(host: String?): Boolean =
        host != null && metaHosts.any { host == it || host.endsWith(".$it") }
}
