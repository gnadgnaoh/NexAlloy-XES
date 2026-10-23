package io.github.nexalloy.revanced.facebook.download

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.StringRes
import app.morphe.extension.shared.Logger
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.R
import io.github.nexalloy.revanced.shared.media.Dash
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object MediaSaver {

    private const val FOLDER = "Facebook"
    private const val MAX_IN_FLIGHT = 3
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "NexAlloy-FB-DL").apply { isDaemon = true } }
    private val inFlight = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var module: Context? = null

    fun t(ctx: Context, @StringRes id: Int, vararg args: Any?): String = runCatching {
        val res = module ?: ctx.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).also { module = it }
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }.getOrDefault("")

    fun toast(ctx: Context, text: String) {
        if (text.isEmpty()) return
        main.post { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }
    }

    fun saveVideo(ctx: Context, id: String?, manifest: String?, urls: List<String>): Boolean {
        val files = urls.filter { Urls.isHttp(it) && Urls.isFacebookCdn(it) && !it.contains("<MPD") }.distinct()
        val tracks = manifest?.let { Dash.parse(it) }.orEmpty()
        val video = Dash.bestVideo(tracks)
        val audio = Dash.bestAudio(tracks)
        val dash = video != null && (audio != null || !Dash.hasSound(tracks))
        if (!dash && files.isEmpty()) return false
        val file = files.firstOrNull { Urls.isVideo(it) } ?: files.firstOrNull()
        return start(ctx, R.string.fb_dl_saved_video) {
            val name = fileName(id, "mp4")
            if (dash) {
                val saved = runCatching { saveDash(ctx, video!!, audio, name, file) }.onFailure {
                    if (file == null) throw it
                    Logger.printInfo { "Facebook: DASH save failed, saving the file instead: ${it.message}" }
                }.isSuccess
                if (saved) return@start
            }
            saveFile(ctx, file!!, name, video = true)
        }
    }

    fun savePhoto(ctx: Context, id: String?, url: String?): Boolean {
        if (!Urls.isHttp(url) || !Urls.isFacebookCdn(url!!)) return false
        return start(ctx, R.string.fb_dl_saved_photo) { saveFile(ctx, url, fileName(id, "jpg"), video = false) }
    }

    private fun start(ctx: Context, @StringRes done: Int, work: () -> Unit): Boolean {
        val app = ctx.applicationContext ?: ctx
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            toast(app, t(app, R.string.fb_dl_busy))
            return true
        }
        toast(app, t(app, R.string.fb_dl_saving))
        executor.execute {
            try {
                work()
                toast(app, t(app, done))
            } catch (e: Throwable) {
                Logger.printException({ "Facebook: download failed" }, e)
                toast(app, t(app, R.string.fb_dl_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return true
    }

    private fun fileName(id: String?, ext: String) =
        "facebook_${id?.takeIf { it.all(Char::isDigit) } ?: "media"}_${System.currentTimeMillis()}.$ext"

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun temp(ctx: Context, suffix: String) = File.createTempFile("nexalloy_fb_", suffix, ctx.cacheDir)

    private fun saveFile(ctx: Context, url: String, name: String, video: Boolean) {
        val file = temp(ctx, ".bin")
        try {
            download(url, file)
            if (video) return publish(ctx, file, name, "video/mp4", video = true)
            val (mime, ext) = imageType(file)
            if (mime == "image/avif") {
                val jpeg = avifToJpeg(ctx, file)
                try { publish(ctx, jpeg, name, "image/jpeg", video = false) } finally { jpeg.delete() }
                return
            }
            publish(ctx, file, name.substringBeforeLast('.') + ".$ext", mime, video = false)
        } finally {
            file.delete()
        }
    }

    private fun imageType(file: File): Pair<String, String> {
        val head = ByteArray(16)
        FileInputStream(file).use { it.read(head) }
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            head[0] == 0x89.toByte() && ascii.substring(1, 4) == "PNG" -> "image/png" to "png"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "image/webp" to "webp"
            ascii.substring(4, 12) == "ftypavif" || ascii.substring(4, 12) == "ftypavis" -> "image/avif" to "avif"
            ascii.substring(4, 8) == "ftyp" -> "image/heic" to "heic"
            else -> "image/jpeg" to "jpg"
        }
    }

    private fun avifToJpeg(ctx: Context, file: File): File {
        check(Build.VERSION.SDK_INT >= 31) { "AVIF needs Android 12" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val jpeg = temp(ctx, ".jpg")
        FileOutputStream(jpeg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return jpeg
    }

    private fun saveDash(ctx: Context, video: Dash.Track, audio: Dash.Track?, name: String, progressive: String?) {
        val v = temp(ctx, ".v.mp4")
        val xhe = audio != null && Dash.isXheAac(audio) && progressive != null
        val a = audio?.let { temp(ctx, ".a.mp4") }
        val out = temp(ctx, ".mp4")
        try {
            download(video.url, v)
            if (xhe) {
                download(progressive!!, a!!)
                val ok = runCatching { Dash.muxSoundFrom(v, a, out) }.isSuccess
                if (ok) return publish(ctx, out, name, "video/mp4", video = true)
                Logger.printInfo { "Facebook: sound from the plain file failed, keeping the DASH sound" }
            }
            a?.let { download(audio.url, it) }
            Dash.mux(listOfNotNull(v, a), out)
            publish(ctx, out, name, "video/mp4", video = true)
        } finally {
            v.delete(); a?.delete(); out.delete()
        }
    }

    private fun publish(ctx: Context, file: File, name: String, mime: String, video: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val root = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> FileInputStream(file).use { it.copyTo(out, 64 * 1024) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
        }
    }
}
