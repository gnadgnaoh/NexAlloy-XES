package io.github.nexalloy.revanced.threads.download

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
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
import io.github.nexalloy.modulePath
import io.github.nexalloy.revanced.shared.media.Dash
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object ThreadsSaver {
    private const val FOLDER = "Threads"
    private const val MAX_IN_FLIGHT = 3
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
    private val cdnHosts = listOf("cdninstagram.com", "fbcdn.net", "fbsbx.com")

    class Rendition(val url: String, val width: Int, val height: Int)
    class Video(val renditions: List<Rendition>, val manifest: String?, val name: String)

    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "NexAlloy-TH-DL").apply { isDaemon = true } }
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
    }.onFailure { Logger.printInfo { "Threads: module string unavailable: $it" } }.getOrDefault("")

    fun toast(ctx: Context, text: String) {
        if (text.isEmpty()) return
        main.post { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }
    }

    fun fileName(username: String?, code: String?, page: Int?): String {
        val user = username?.filter { it.isLetterOrDigit() || it == '.' || it == '_' }?.takeIf { it.isNotEmpty() } ?: "user"
        val post = code?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.takeIf { it.isNotEmpty() }
            ?: System.currentTimeMillis().toString()
        return "threads_${user}_$post" + (page?.let { "_$it" } ?: "") + ".mp4"
    }

    fun save(ctx: Context, videos: List<Video>) {
        val app = ctx.applicationContext ?: ctx
        if (videos.isEmpty()) return toast(app, t(app, R.string.th_no_video))
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return toast(app, t(app, R.string.th_save_busy))
        }
        val many = videos.size > 1
        toast(app, if (many) t(app, R.string.th_saving_videos, videos.size) else t(app, R.string.th_saving_video))
        executor.execute {
            try {
                var saved = 0
                var failure: Throwable? = null
                videos.forEach { video ->
                    runCatching { saveOne(app, video) }.onSuccess { saved++ }.onFailure {
                        failure = it
                        Logger.printException({ "Threads: video download failed" }, it)
                    }
                }
                when {
                    saved == 0 -> throw failure ?: IOException("no video")
                    many -> toast(app, t(app, R.string.th_saved_videos, saved))
                    else -> toast(app, t(app, R.string.th_saved_video))
                }
            } catch (e: Throwable) {
                toast(app, t(app, R.string.th_save_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun isCdn(url: String): Boolean = runCatching {
        val u = URL(url)
        val host = u.host.lowercase(Locale.ROOT)
        u.protocol == "https" && cdnHosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun saveOne(ctx: Context, video: Video) {
        val files = video.renditions.filter { isCdn(it.url) }
            .sortedWith(compareByDescending<Rendition> { minOf(it.width, it.height) }.thenByDescending { it.width.toLong() * it.height })
        val tracks = video.manifest?.let { Dash.parse(it) }.orEmpty().filter { isCdn(it.url) }
        val best = Dash.bestVideo(tracks)
        val audio = Dash.bestAudio(tracks)
        val dash = best != null && (audio != null || !Dash.hasSound(tracks))
        val file = files.firstOrNull()?.url
        if (!dash && file == null) throw IOException("no video address")
        val fileShort = files.firstOrNull()?.let { minOf(it.width, it.height) } ?: 0
        val dashShort = best?.let { minOf(it.width, it.height) } ?: 0
        if (dash && (file == null || dashShort >= fileShort)) {
            val ok = runCatching { saveDash(ctx, best!!, audio, video.name, file) }.onFailure {
                if (file == null) throw it
                Logger.printInfo { "Threads: DASH save failed, saving the file instead: ${it.message}" }
            }.isSuccess
            if (ok) return
        }
        val temp = temp(ctx, ".mp4")
        try {
            download(file!!, temp)
            publish(ctx, temp, video.name)
        } finally {
            temp.delete()
        }
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
                if (runCatching { Dash.muxSoundFrom(v, a, out) }.isSuccess) return publish(ctx, out, name)
            }
            a?.let { download(audio.url, it) }
            Dash.mux(listOfNotNull(v, a), out)
            publish(ctx, out, name)
        } finally {
            v.delete(); a?.delete(); out.delete()
        }
    }

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

    private fun temp(ctx: Context, suffix: String) = File.createTempFile("nexalloy_th_", suffix, ctx.cacheDir)

    private fun publish(ctx: Context, file: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> FileInputStream(file).use { it.copyTo(out, 64 * 1024) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf("video/mp4"), null)
        }
    }
}
