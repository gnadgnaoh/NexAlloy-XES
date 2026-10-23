package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.github.nexalloy.R
import io.github.nexalloy.revanced.shared.media.Dash

internal object MediaSaver {

    @JvmField
    val executor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "NexAlloy-IG-DL").apply { isDaemon = true }
    }

    @JvmField
    val mainHandler = Handler(Looper.getMainLooper())

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun toast(ctx: Context, text: String) {
        if (text.isEmpty()) return
        val show = { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else mainHandler.post { show() }
    }

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun buildFilename(username: String?, type: String, mediaId: String?, isVideo: Boolean, index: Int = -1): String {
        val u = if (!username.isNullOrEmpty()) username else "unknown"
        val id = if (!mediaId.isNullOrEmpty()) mediaId else System.currentTimeMillis().toString()
        val sb = StringBuilder(u).append('_').append(type).append('_').append(id)
        if (index >= 0) sb.append('_').append(index + 1)
        if (FeatureFlags.downloaderAddTimestamp) {
            sb.append('_').append(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        }
        return sb.append(if (isVideo) ".mp4" else ".jpg").toString()
    }

    class Job(
        @JvmField val item: MediaItem,
        @JvmField val username: String?,
        @JvmField val type: String,
        @JvmField val mediaId: String?,
        @JvmField val index: Int = -1,
    )

    fun saveAll(ctx: Context, jobs: List<Job>, startText: String, doneText: String) {
        if (jobs.isEmpty()) return
        toast(ctx, startText)
        val remaining = AtomicInteger(jobs.size)
        val failed = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        for (job in jobs) {
            executor.submit {
                try {
                    val fn = buildFilename(job.username, job.type, job.mediaId, job.item.isVideo, job.index)
                    downloadItem(ctx, job.item, fn, job.username)
                } catch (t: Throwable) {
                    failed.incrementAndGet()
                    lastError.set(t.message ?: t.javaClass.simpleName)
                    ModuleLog.line("(NA|DL) failed ${job.type}: $t")
                }
                if (remaining.decrementAndGet() == 0) {
                    val n = jobs.size
                    val f = failed.get()
                    val msg = when {
                        f == 0 -> doneText
                        n == 1 -> I18n.t(ctx, R.string.ig_toast_download_failed, lastError.get())
                        else -> I18n.t(ctx, R.string.ig_toast_items_partial_saved, n - f, n, f)
                    }
                    toast(ctx, msg)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun downloadItem(ctx: Context, item: MediaItem, filename: String, username: String?) {
        val manifest = item.dashManifest
        if (item.isVideo && manifest != null) {
            val tracks = Dash.parse(manifest)
            val video = Dash.bestVideo(tracks)
            val audio = Dash.bestAudio(tracks)
            val sharper = video != null && video.width + video.height > item.progressiveScore
            if (sharper && (audio != null || !Dash.hasSound(tracks))) {
                val saved = runCatching { saveDash(ctx, video!!, audio, filename, username, item.url) }
                    .onFailure { ModuleLog.line("(NA|DL) DASH failed, saving the mp4: $it") }.isSuccess
                if (saved) return
            }
        }
        downloadAndSave(ctx, item.url, filename, item.isVideo, username)
    }

    private fun saveDash(ctx: Context, video: Dash.Track, audio: Dash.Track?, filename: String, username: String?, progressive: String?) {
        val v = File.createTempFile("ie_dl_v_", ".mp4", ctx.cacheDir)
        val a = audio?.let { File.createTempFile("ie_dl_a_", ".mp4", ctx.cacheDir) }
        val out = File.createTempFile("ie_dl_", ".mp4", ctx.cacheDir)
        try {
            downloadToFile(video.url, v)
            if (audio != null && Dash.isXheAac(audio) && progressive != null) {
                val p = File.createTempFile("ie_dl_p_", ".mp4", ctx.cacheDir)
                try {
                    downloadToFile(progressive, p)
                    val ok = runCatching { Dash.muxSoundFrom(v, p, out) }
                        .onFailure { ModuleLog.line("(NA|DL) no plain sound for xHE-AAC reel: $it") }.isSuccess
                    if (ok) return saveFileToDestination(ctx, out, filename, true, username)
                } finally {
                    p.delete()
                }
            }
            a?.let { downloadToFile(audio.url, it) }
            Dash.mux(listOfNotNull(v, a), out)
            saveFileToDestination(ctx, out, filename, true, username)
        } finally {
            v.delete(); a?.delete(); out.delete()
        }
    }

    @Throws(Exception::class)
    fun saveAudio(ctx: Context, url: String, filename: String, username: String?, fromMs: Long = -1, lengthMs: Long = -1) {
        val raw = File.createTempFile("ie_au_", ".bin", ctx.cacheDir)
        val out = File.createTempFile("ie_au_", ".m4a", ctx.cacheDir)
        try {
            downloadToFile(url, raw)
            val cut = runCatching {
                if (fromMs >= 0 || lengthMs > 0) {
                    val from = fromMs.coerceAtLeast(0) * 1000
                    Dash.cut(raw, out, from, if (lengthMs > 0) from + lengthMs * 1000 else Long.MAX_VALUE)
                } else {
                    Dash.mux(listOf(raw), out)
                }
            }.onFailure { ModuleLog.line("(NA|DL) audio remux failed, saving whole file: $it") }.isSuccess
            val (ext, mime) = if (cut) ".m4a" to "audio/mp4" else audioType(raw)
            val name = filename.substringBeforeLast('.') + ext
            FileInputStream(if (cut) out else raw).use { input ->
                openOutputStream(ctx, name, mime, username).use { input.copyTo(it, 64 * 1024) }
            }
        } finally {
            raw.delete(); out.delete()
        }
    }

    private fun audioType(file: File): Pair<String, String> {
        val head = ByteArray(12)
        val n = FileInputStream(file).use { it.read(head) }
        val text = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1) else ""
        return when {
            text.length >= 8 && text.substring(4, 8) == "ftyp" -> ".m4a" to "audio/mp4"
            text.startsWith("OggS") -> ".ogg" to "audio/ogg"
            text.startsWith("ID3") || (n >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> ".mp3" to "audio/mpeg"
            text.startsWith("RIFF") -> ".wav" to "audio/wav"
            text.startsWith("#!AMR") -> ".amr" to "audio/amr"
            else -> ".m4a" to "audio/mp4"
        }
    }

    @Throws(Exception::class)
    fun downloadAndSave(ctx: Context, url: String, filename: String, isVideo: Boolean, username: String?) {
        val temp = File.createTempFile("ie_dl_", ".bin", ctx.cacheDir)
        try {
            val responseType = downloadToFile(url, temp)
            val detected = MediaTypeDetector.resolve(
                temp, responseType, if (isVideo) "video/mp4" else "image/jpeg", filename
            )
            ModuleLog.line(
                "(NA|DL) requested=" + (if (isVideo) "video" else "image") +
                    " response=" + responseType + " detected=" + detected.kind +
                    " file=" + detected.filename
            )
            saveFileToDestination(ctx, temp, detected.filename ?: filename, detected.isVideo(), username)
        } finally {
            temp.delete()
        }
    }

    @Throws(Exception::class)
    fun saveAnimated(ctx: Context, url: String, baseName: String, username: String?) {
        val temp = File.createTempFile("ie_dl_", ".bin", ctx.cacheDir)
        try {
            downloadToFile(url, temp)
            val head = ByteArray(12)
            val n = FileInputStream(temp).use { it.read(head) }
            val text = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1) else ""
            val (ext, mime) = when {
                text.startsWith("GIF8") -> ".gif" to "image/gif"
                text.startsWith("RIFF") && text.length >= 12 && text.substring(8, 12) == "WEBP" -> ".webp" to "image/webp"
                text.length >= 8 && text.substring(4, 8) == "ftyp" -> ".mp4" to "video/mp4"
                else -> ".jpg" to "image/jpeg"
            }
            FileInputStream(temp).use { input ->
                openOutputStream(ctx, baseName + ext, mime, username).use { out -> input.copyTo(out, 64 * 1024) }
            }
        } finally {
            temp.delete()
        }
    }

    @Throws(IOException::class)
    private fun downloadToFile(url: String, dest: File): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val contentType = conn.contentType
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024) }
            }
            return contentType
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    fun saveFileToDestination(ctx: Context, tempFile: File, filename: String, isVideo: Boolean, username: String?) {
        FileInputStream(tempFile).use { input ->
            openOutputStream(ctx, filename, if (isVideo) "video/mp4" else "image/jpeg", username).use { out -> input.copyTo(out, 64 * 1024) }
        }
    }

    @Throws(Exception::class)
    private fun openOutputStream(ctx: Context, filename: String, mimeType: String, username: String?): OutputStream {

        if (FeatureFlags.downloaderCustomPath.isNotEmpty()) {
            try {
                return openRawPathOutputStream(filename, username)
            } catch (e: Exception) {
                ModuleLog.line("(NA|DL) Raw path failed, falling back to MediaStore: " + e.message)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return openMediaStoreOutputStream(ctx, filename, mimeType, username)
        }

        @Suppress("DEPRECATION")
        var dir = File(Environment.getExternalStoragePublicDirectory(rootFor(mimeType)), FOLDER)
        if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) dir = File(dir, username)
        dir.mkdirs()
        return FileOutputStream(File(dir, filename))
    }

    @Throws(Exception::class)
    private fun openRawPathOutputStream(filename: String, username: String?): OutputStream {
        val rawPath = FeatureFlags.downloaderCustomPath
        if (rawPath.startsWith("content://")) throw Exception("Not a raw file path: $rawPath")
        var dir = File(rawPath)
        if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) dir = File(dir, username)
        if (!dir.exists() && !dir.mkdirs()) throw Exception("Cannot create dir: " + dir.absolutePath)
        return FileOutputStream(File(dir, filename))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("NewApi")
    @Throws(Exception::class)
    private fun openMediaStoreOutputStream(ctx: Context, filename: String, mimeType: String, username: String?): OutputStream {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, buildMediaStoreRelPath(username, mimeType))
        val itemUri = ctx.contentResolver.insert(collectionFor(mimeType), values)
            ?: throw Exception("MediaStore insert failed")
        return ctx.contentResolver.openOutputStream(itemUri)
            ?: throw Exception("MediaStore openOutputStream returned null")
    }

    private val MS_ROOTS = hashSetOf(
        "Download", "Downloads", "Pictures", "DCIM", "Movies", "Music",
        "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks"
    )

    private const val FOLDER = "Instagram"

    private fun rootFor(mimeType: String) = when {
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_PICTURES
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collectionFor(mimeType: String) = when {
        FeatureFlags.downloaderCustomPath.isNotEmpty() -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun buildMediaStoreRelPath(username: String?, mimeType: String): String {
        val customPath = FeatureFlags.downloaderCustomPath
        var base = "${rootFor(mimeType)}/$FOLDER"
        if (customPath.isNotEmpty() && !customPath.startsWith("content://")) {
            @Suppress("DEPRECATION")
            val extBase = Environment.getExternalStorageDirectory().absolutePath
            if (customPath.startsWith("$extBase/")) {
                val relative = customPath.substring(extBase.length + 1)
                val topLevel = relative.split("/")[0]
                base = if (MS_ROOTS.contains(topLevel)) relative else "Download/$relative"
            }
        }
        if (FeatureFlags.downloaderUsernameFolder && !username.isNullOrEmpty()) base += "/$username"
        return base
    }

    fun isCdnMediaUrl(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false
        if (url.contains("/t51.") && url.contains("-19/")) return false
        if (url.contains("t51.39750")) return false
        return true
    }

    fun looksLikeVideoUrl(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase(Locale.US)
        if (lower.contains("t50.")) return true
        if (lower.contains("/o1/") || lower.contains("%2fo1%2f")) return true
        return lower.contains(".mp4") || lower.contains("mime_type=video") ||
            lower.contains("mime%2ftype=video")
    }
}
