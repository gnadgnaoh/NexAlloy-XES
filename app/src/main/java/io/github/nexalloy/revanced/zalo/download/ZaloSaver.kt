package io.github.nexalloy.revanced.zalo.download

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.StringRes
import app.morphe.extension.shared.Logger
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.R
import io.github.nexalloy.modulePath
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object ZaloSaver {
    private const val FOLDER = "Zalo"
    private const val JXL = "image/jxl"
    private const val MAX_IN_FLIGHT = 3
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "NexAlloy-ZL-DL").apply { isDaemon = true } }
    private val inFlight = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var module: Resources? = null

    private fun resources(ctx: Context): Resources = module ?: (runCatching {
        ctx.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
    }.getOrNull() ?: run {
        val info = ctx.packageManager.getPackageArchiveInfo(modulePath, 0)?.applicationInfo ?: ApplicationInfo()
        info.sourceDir = modulePath
        info.publicSourceDir = modulePath
        val loaded = ctx.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        Resources(loaded.assets, ctx.resources.displayMetrics, ctx.resources.configuration)
    }).also { module = it }

    fun t(ctx: Context, @StringRes id: Int, vararg args: Any?): String = runCatching {
        val res = resources(ctx)
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }.onFailure { Logger.printInfo { "Zalo: module string unavailable: $it" } }.getOrDefault("")

    fun toast(ctx: Context, text: String, long: Boolean = false) {
        if (text.isEmpty()) return
        main.post { runCatching { Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() } }
    }

    fun save(ctx: Context, url: String, name: String) {
        val app = ctx.applicationContext ?: ctx
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return toast(app, t(app, R.string.zl_dl_busy))
        }
        toast(app, t(app, R.string.zl_dl_saving))
        executor.execute {
            val temp = File.createTempFile("nexalloy_zl_", ".bin", app.cacheDir)
            try {
                val (mime, ext) = fetch(app, url, temp)
                val video = mime.startsWith("video/")
                publish(app, temp, "$name.$ext", mime, video)
                toast(app, t(app, if (video) R.string.zl_dl_saved_video else R.string.zl_dl_saved_photo))
            } catch (e: Throwable) {
                Logger.printException({ "Zalo: download failed" }, e)
                toast(app, t(app, R.string.zl_dl_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                temp.delete()
                inFlight.decrementAndGet()
            }
        }
    }

    fun saveAll(ctx: Context, urls: List<String>, name: String, missing: Int, streams: Int) {
        val app = ctx.applicationContext ?: ctx
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return toast(app, t(app, R.string.zl_dl_busy))
        }
        toast(app, t(app, R.string.zl_dl_all_start, urls.size))
        executor.execute {
            var saved = 0
            try {
                urls.forEachIndexed { i, url ->
                    val temp = File.createTempFile("nexalloy_zl_", ".bin", app.cacheDir)
                    try {
                        val (mime, ext) = fetch(app, url, temp)
                        val video = mime.startsWith("video/")
                        publish(app, temp, "${name}_${i + 1}.$ext", mime, video)
                        saved++
                    } catch (e: Throwable) {
                        Logger.printException({ "Zalo: download failed ${i + 1}/${urls.size}" }, e)
                    } finally {
                        temp.delete()
                    }
                }
            } finally {
                inFlight.decrementAndGet()
                val lines = mutableListOf(t(app, R.string.zl_dl_all_done, saved, urls.size))
                if (missing > 0) lines += t(app, R.string.zl_dl_all_more, missing)
                if (streams > 0) lines += t(app, R.string.zl_dl_all_stream, streams)
                toast(app, lines.filter { it.isNotEmpty() }.joinToString("\n"), lines.size > 1)
            }
        }
    }

    private fun fetch(app: Context, url: String, temp: File): Pair<String, String> {
        var mime = mimeOf(download(url, temp), url)
        if (mime == JXL || ZaloJxl.isJxl(temp)) {
            val jpeg = File(temp.path + ".jpg")
            try {
                if (runCatching { ZaloJxl.toJpeg(app.classLoader, temp, jpeg) }
                        .onFailure { Logger.printException({ "Zalo: JXL convert failed" }, it) }.getOrDefault(false)) {
                    jpeg.copyTo(temp, overwrite = true)
                    mime = "image/jpeg"
                } else {
                    mime = JXL
                }
            } finally {
                jpeg.delete()
            }
        }
        val ext = when (mime) {
            JXL -> "jxl"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (mime.startsWith("video/")) "mp4" else "jpg"
        }
        return mime to ext
    }

    private fun mimeOf(header: String?, url: String): String {
        val clean = header?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (clean != null && (clean.startsWith("video/") || clean.startsWith("image/"))) return clean
        val ext = MimeTypeMap.getFileExtensionFromUrl(Uri.parse(url).path ?: url).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
    }

    private fun download(url: String, dest: File): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) } }
            return conn.contentType
        } finally {
            conn.disconnect()
        }
    }

    private fun publish(ctx: Context, file: File, name: String, mime: String, video: Boolean) {
        val root = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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
            val dir = File(Environment.getExternalStoragePublicDirectory(root), FOLDER)
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
        }
    }
}
