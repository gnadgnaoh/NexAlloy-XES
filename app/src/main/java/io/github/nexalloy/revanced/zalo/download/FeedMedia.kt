package io.github.nexalloy.revanced.zalo.download

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

internal class FeedMedia(val id: String, val urls: List<String>, val missing: Int, val streams: Int, val videos: Int) {
    companion object {
        private const val ORIGIN = "origin"
        private const val THUMB = "thumb"
        private const val HLS = "hls"
        private const val MORE = "more_photo"
        private const val VIDEO = "video"

        fun of(feedJson: Method, feed: Any): FeedMedia? {
            val json = runCatching { feedJson.invoke(feed) as? JSONObject }.getOrNull() ?: return null
            val content = json.optJSONObject("content") ?: return null
            val urls = LinkedHashSet<String>()
            var streams = 0
            var videos = 0
            fun walk(node: Any?, key: String?) {
                when (node) {
                    is JSONObject -> {
                        val origin = link(node.optString(ORIGIN))
                        val hls = node.optString(HLS).isNotBlank()
                        when {
                            origin != null -> if (urls.add(origin) && key == VIDEO) videos++
                            hls -> streams++
                            node.has(ORIGIN) -> link(node.optString(THUMB))?.let { urls.add(it) }
                        }
                        node.keys().forEach { walk(node.opt(it), it) }
                    }
                    is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), key)
                }
            }
            walk(content, null)
            val id = "feed_" + json.optString("fid").filter { it.isLetterOrDigit() }.ifEmpty { System.currentTimeMillis().toString() }
            return FeedMedia(id, urls.toList(), content.optInt(MORE).coerceAtLeast(0), streams, videos)
        }

        private fun link(value: String): String? = value.takeIf { it.startsWith("http") && !it.contains(".m3u8") }
    }
}
