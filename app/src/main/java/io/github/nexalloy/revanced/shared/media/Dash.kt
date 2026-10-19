package io.github.nexalloy.revanced.shared.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

internal object Dash {
    class Track(val mime: String, val codecs: String, val width: Int, val height: Int, val bandwidth: Long, val url: String) {
        val isVideo get() = mime.startsWith("video/")
        val isAudio get() = mime.startsWith("audio/")
        val shortSide get() = minOf(width, height)
    }

    private const val MAX_CHARS = 256 * 1024
    private val ADAPTATION_SET = Regex("<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", RegexOption.DOT_MATCHES_ALL)
    private val REPRESENTATION = Regex("<Representation\\b([^>]*?)(?:/>|>(.*?)</Representation>)", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("([\\w:]+)\\s*=\\s*\"([^\"]*)\"")
    private val BASE_URL = Regex("<BaseURL[^>]*>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)

    fun withinLimits(manifest: String) = manifest.length <= MAX_CHARS && manifest.contains("<MPD")

    fun parse(manifest: String): List<Track> = if (!withinLimits(manifest)) emptyList() else runCatching {
        ADAPTATION_SET.findAll(manifest).flatMap { set ->
            val setAttrs = set.groupValues[1]
            REPRESENTATION.findAll(set.groupValues[2]).mapNotNull { track(setAttrs, it.groupValues[1], it.groupValues[2]) }
        }.toList()
    }.getOrDefault(emptyList())

    private fun track(setAttrs: String, attrs: String, body: String): Track? {
        val url = BASE_URL.find(body)?.groupValues?.get(1)?.trim()?.let(::unescape) ?: return null
        if (!url.startsWith("https://", true)) return null
        val mime = (attr(attrs, "mimeType") ?: attr(setAttrs, "mimeType") ?: attr(setAttrs, "contentType")?.let { "$it/mp4" })
            ?.lowercase(Locale.ROOT) ?: return null
        val codecs = (attr(attrs, "codecs") ?: attr(setAttrs, "codecs") ?: "").lowercase(Locale.ROOT)
        fun num(name: String) = attr(attrs, name)?.trim()?.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong()) ?: 0
        return Track(mime, codecs, num("width").toInt(), num("height").toInt(), num("bandwidth"), url)
    }

    private fun attr(attrs: String, name: String) =
        ATTRIBUTE.findAll(attrs).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)?.let(::unescape)?.takeIf { it.isNotBlank() }

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun family(track: Track) = when {
        track.codecs.startsWith("avc1") || track.codecs.startsWith("avc3") -> 3
        track.codecs.startsWith("hvc1") || track.codecs.startsWith("hev1") -> 2
        track.codecs.startsWith("av01") && Build.VERSION.SDK_INT >= 34 -> 1
        else -> 0
    }

    fun bestVideo(tracks: List<Track>) = tracks.filter { it.isVideo && family(it) > 0 }
        .maxWithOrNull(compareBy<Track>({ it.shortSide }, { family(it) }, { it.bandwidth }))

    fun bestAudio(tracks: List<Track>) = tracks.filter { it.isAudio && it.codecs.startsWith("mp4a") }
        .maxWithOrNull(compareBy<Track>({
            when (it.codecs.removePrefix("mp4a.40.").toIntOrNull()) { 2 -> 2; 5, 29 -> 1; else -> 0 }
        }, { it.bandwidth }))

    fun hasSound(tracks: List<Track>) = tracks.any { it.isAudio }

    fun mux(inputs: List<File>, out: File) = copy(inputs, out, 0L, Long.MAX_VALUE)

    fun cut(input: File, out: File, fromUs: Long, toUs: Long) = copy(listOf(input), out, fromUs, toUs)

    private fun copy(inputs: List<File>, out: File, fromUs: Long, toUs: Long) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = inputs.map { file ->
            MediaExtractor().apply { setDataSource(file.path) }.also {
                check(it.trackCount > 0) { "empty track" }
                it.selectTrack(0)
                if (fromUs > 0) it.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        }
        try {
            val tracks = extractors.map { muxer.addTrack(it.getTrackFormat(0)) }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, ex ->
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0 || ex.sampleTime >= toUs) break
                    info.set(0, size, (ex.sampleTime - fromUs).coerceAtLeast(0), ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(tracks[i], buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            extractors.forEach { it.release() }
            runCatching { muxer.release() }
        }
    }
}
