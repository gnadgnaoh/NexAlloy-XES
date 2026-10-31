package io.github.nexalloy.revanced.shared.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
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
        track.codecs.startsWith("avc1") || track.codecs.startsWith("avc3") -> 4
        (track.codecs.startsWith("hvc1") || track.codecs.startsWith("hev1")) && hardware("video/hevc") -> 3
        track.codecs.startsWith("av01.0.") && track.codecs.contains(".08") && hardware("video/av01") -> 2
        track.codecs.startsWith("vp09.00.") && track.mime == "video/mp4" && hardware("video/x-vnd.on2.vp9") -> 1
        else -> 0
    }

    private val hardwareCache = HashMap<String, Boolean>()

    private fun hardware(mime: String): Boolean = synchronized(hardwareCache) {
        hardwareCache.getOrPut(mime) {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                        (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
                }
            }.getOrDefault(false)
        }
    }

    fun bestVideo(tracks: List<Track>) = tracks.filter { it.isVideo && family(it) > 0 }
        .maxWithOrNull(compareBy<Track>({ it.shortSide }, { family(it) }, { it.bandwidth }))

    fun bestAudio(tracks: List<Track>) = tracks.filter { it.isAudio && it.codecs.startsWith("mp4a") }
        .maxWithOrNull(compareBy<Track>({
            when (it.codecs.removePrefix("mp4a.40.").toIntOrNull()) { 2 -> 2; 5, 29 -> 1; else -> 0 }
        }, { it.bandwidth }))

    fun hasSound(tracks: List<Track>) = tracks.any { it.isAudio }

    fun mux(inputs: List<File>, out: File) = copy(inputs.map { it to null }, out, 0L, Long.MAX_VALUE)

    fun muxSoundFrom(video: File, sound: File, out: File) =
        copy(listOf(video to null, sound to "audio/"), out, 0L, Long.MAX_VALUE)

    fun extractAudio(input: File, out: File) = copy(listOf(input to "audio/"), out, 0L, Long.MAX_VALUE)

    fun isXheAac(track: Track) = track.codecs == "mp4a.40.42"

    fun cut(input: File, out: File, fromUs: Long, toUs: Long) = copy(listOf(input to null), out, fromUs, toUs)

    private fun copy(inputs: List<Pair<File, String?>>, out: File, fromUs: Long, toUs: Long) {
        if (fromUs == 0L && toUs == Long.MAX_VALUE && needsOwnWriter(inputs.first().first)) {
            Mp4Join.join(inputs.first().first, inputs.getOrNull(1)?.first, out)
            return verify(out, inputs.size)
        }
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            val picked = ArrayList<Int>()
            inputs.forEach { (file, kind) ->
                extractors += MediaExtractor().apply { setDataSource(file.path) }.also {
                    check(it.trackCount > 0) { "empty track" }
                    val index = if (kind == null) 0 else (0 until it.trackCount)
                        .firstOrNull { i -> it.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }
                        ?: error("no $kind track in ${file.name}")
                    it.selectTrack(index)
                    picked += index
                    if (fromUs > 0) it.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            }
            val formats = extractors.mapIndexed { i, ex -> ex.getTrackFormat(picked[i]) }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }?.let { video ->
                if (video.containsKey(KEY_ROTATION)) muxer.setOrientationHint(video.getInteger(KEY_ROTATION))
            }
            val tracks = formats.map { muxer.addTrack(it) }
            muxer.start()
            val base = if (fromUs > 0) fromUs else extractors.minOf { it.sampleTime.takeIf { t -> t >= 0 } ?: Long.MAX_VALUE }
                .takeIf { it != Long.MAX_VALUE } ?: 0L
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val written = IntArray(extractors.size)
            val done = BooleanArray(extractors.size)
            while (true) {
                var next = -1
                var nextTime = Long.MAX_VALUE
                extractors.forEachIndexed { i, ex ->
                    if (done[i]) return@forEachIndexed
                    val t = ex.sampleTime
                    if (t < 0 || t >= toUs) { done[i] = true; return@forEachIndexed }
                    if (t < nextTime) { nextTime = t; next = i }
                }
                if (next < 0) break
                val ex = extractors[next]
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) { done[next] = true; continue }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, (ex.sampleTime - base).coerceAtLeast(0), flags)
                muxer.writeSampleData(tracks[next], buffer, info)
                written[next]++
                ex.advance()
            }
            muxer.stop()
            check(written.all { it > 0 }) { "a track wrote no samples" }
        } finally {
            extractors.forEach { runCatching { it.release() } }
            runCatching { muxer?.release() }
        }
        verify(out, inputs.size)
    }

    private fun needsOwnWriter(file: File): Boolean {
        val mime = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.path)
                (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                    .firstOrNull { it.startsWith("video/") }
            } finally { ex.release() }
        }.getOrNull() ?: return false
        return mime == MediaFormat.MIMETYPE_VIDEO_VP9 || (mime == MediaFormat.MIMETYPE_VIDEO_AV1 && Build.VERSION.SDK_INT < 34)
    }

    private fun verify(out: File, expected: Int) {
        val check = MediaExtractor()
        try {
            check.setDataSource(out.path)
            check(check.trackCount == expected) { "muxed file has ${check.trackCount} of $expected tracks" }
            for (i in 0 until check.trackCount) {
                val format = check.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    check(format.getLong(MediaFormat.KEY_DURATION) > 0) { "muxed track $i has no duration" }
                }
            }
        } finally {
            check.release()
        }
    }

    private const val KEY_ROTATION = "rotation-degrees"
}
