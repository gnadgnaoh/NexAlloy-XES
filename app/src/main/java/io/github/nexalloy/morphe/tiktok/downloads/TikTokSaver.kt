package io.github.nexalloy.morphe.tiktok.downloads

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaCodecList
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import app.morphe.extension.shared.Logger
import io.github.nexalloy.R
import io.github.nexalloy.morphe.tiktok.shared.Model
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.revanced.shared.media.Dash
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object TikTokSaver {
    private const val FOLDER = "TikTok"
    private const val MAX_IN_FLIGHT = 3
    private const val STORY_TYPE = 40
    private const val STORY_TYPE_SHARED = 45
    private const val TALL_ENOUGH = 720

    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "NexAlloy-TT-DL").apply { isDaemon = true } }
    private val inFlight = AtomicInteger()

    fun isStory(aweme: Any?): Boolean {
        aweme ?: return false
        if (Model.get(aweme, "getIsTikTokStory") == true) return true
        val type = (Model.get(aweme, "getAwemeType") as? Number)?.toInt()
        return type == STORY_TYPE || type == STORY_TYPE_SHARED
    }

    private fun photos(aweme: Any?): List<Any> =
        (Model.get(Model.get(aweme, "getPhotoModeImageInfo"), "getImageList") as? List<*>)?.filterNotNull().orEmpty()

    fun isPhotoPost(aweme: Any?) = photos(aweme).isNotEmpty()

    private fun video(aweme: Any?) = Model.get(aweme, "getVideo")

    fun hasCover(aweme: Any?) = !isPhotoPost(aweme) && coverUrls(aweme).isNotEmpty()

    fun hasSound(aweme: Any?) = videoUrls(aweme).isNotEmpty() || musicUrls(aweme).isNotEmpty()

    private fun id(aweme: Any?) = Model.string(aweme, "getAid", "aid")?.filter(Char::isDigit) ?: "media"

    private fun gears(video: Any?): List<Any> {
        val raw = (Model.get(video, "getRawBitRate") ?: Model.field(video, "bitRateList")) as? List<*> ?: return emptyList()
        return raw.filterNotNull().filter { gear ->
            val codec = (Model.field(gear, "isBytevc1") as? Number)?.toInt() ?: 0
            Model.urls(Model.field(gear, "playAddr")).isNotEmpty() && (codec == 0 || (codec == 1 && hevcHardware))
        }.sortedWith(compareByDescending<Any> { height(it) }
            .thenBy { (Model.field(it, "isBytevc1") as? Number)?.toInt() ?: 0 }
            .thenByDescending { (Model.field(it, "bitRate") as? Number)?.toLong() ?: 0L })
    }

    private fun height(gear: Any): Int {
        val addr = Model.field(gear, "playAddr")
        val w = (Model.field(addr, "width") as? Number)?.toInt() ?: 0
        val h = (Model.field(addr, "height") as? Number)?.toInt() ?: 0
        if (w > 0 && h > 0) return minOf(w, h)
        val name = Model.field(gear, "gearName") as? String ?: return maxOf(w, h)
        return Regex("(?<![0-9])(2160|1440|1080|960|720|640|576|540|480|360|240)(?![0-9])").find(name)?.value?.toInt() ?: maxOf(w, h)
    }

    private val hevcHardware: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                    (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
            }
        }.getOrDefault(false)
    }

    fun bestGearAddress(video: Any?): Any? {
        if (Model.get(video, "hasDashBitrate") == true) return null
        val top = gears(video).firstOrNull()
        val best = top?.let { Model.field(it, "playAddr") }
        val tallest = top?.let { height(it) } ?: 0
        if (tallest >= TALL_ENOUGH) return best
        return sources(video).filter { gearHeight(it) > tallest }.maxByOrNull { gearHeight(it) } ?: best
    }

    private fun sources(video: Any?): List<Any> = listOfNotNull(
        Model.get(video, "getPlayAddrH264"),
        Model.get(video, "getH264PlayAddr"),
        if (hevcHardware) Model.get(video, "getPlayAddrBytevc1") else null,
    ).filter { Model.urls(it).isNotEmpty() }

    fun gearHeight(address: Any?): Int {
        val w = (Model.field(address, "width") as? Number)?.toInt() ?: 0
        val h = (Model.field(address, "height") as? Number)?.toInt() ?: 0
        return if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
    }

    private fun videoUrls(aweme: Any?): List<String> {
        val v = video(aweme) ?: return emptyList()
        val list = ArrayList<String>()
        if (Model.get(v, "hasDashBitrate") != true) {
            bestGearAddress(v)?.let { list += Model.urls(it) }
            gears(v).forEach { list += Model.urls(Model.field(it, "playAddr")) }
        }
        list += Model.urls(Model.get(v, "getDownloadNoWatermarkAddr"))
        list += Model.urls(Model.get(v, "getH264PlayAddr"))
        list += Model.urls(Model.get(v, "getPlayAddr"))
        return list.distinct()
    }

    private fun musicUrls(aweme: Any?) = Model.urls(Model.get(Model.get(aweme, "getMusic"), "getPlayUrl"))

    private fun coverUrls(aweme: Any?): List<String> {
        val v = video(aweme) ?: return emptyList()
        return (Model.urls(Model.get(v, "getOriginCover")) + Model.urls(Model.get(v, "getCover"))).distinct()
    }

    private fun photoUrls(photo: Any): List<String> {
        val urls = Model.urls(Model.get(photo, "getDisplayImageNoWatermark", "displayImageNoWatermark"))
        fun path(u: String) = u.substringBefore('?').lowercase()
        val jpeg = urls.filter { path(it).let { p -> p.endsWith(".jpeg") || p.endsWith(".jpg") } }
        val heif = urls.filter { path(it).let { p -> p.endsWith(".heic") || p.endsWith(".heif") || p.endsWith(".avif") } }
        return jpeg + (urls - jpeg.toSet() - heif.toSet()) + heif
    }

    fun avatarUrls(user: Any?): List<String> =
        listOf("getAvatarLarger", "getAvatarMedium", "getAvatar300", "getAvatar168", "getAvatarThumb")
            .flatMap { Model.urls(Model.get(user, it)) }.distinct()

    fun saveStory(aweme: Any) {
        val pictures = photos(aweme)
        if (pictures.isNotEmpty()) return savePhotos(aweme, pictures)
        val urls = videoUrls(aweme)
        if (urls.isEmpty()) return TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
        start(R.string.tt_saved_video) { ctx ->
            val file = temp(ctx, ".mp4")
            try {
                downloadAny(urls, file)
                publish(ctx, file, "tiktok_${id(aweme)}_${stamp()}.mp4", "video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            } finally { file.delete() }
        }
    }

    private fun savePhotos(aweme: Any, pictures: List<Any>) {
        val done = TikTokUi.t(R.string.tt_saved_photos, pictures.size)
        start(0, done) { ctx ->
            pictures.forEachIndexed { i, photo ->
                saveImage(ctx, photoUrls(photo), "tiktok_${id(aweme)}_${i + 1}_${stamp()}")
            }
        }
    }

    fun saveSound(aweme: Any) {
        val video = videoUrls(aweme).asReversed()
        val music = musicUrls(aweme)
        if (video.isEmpty() && music.isEmpty()) return TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
        start(R.string.tt_saved_sound) { ctx ->
            val name = "tiktok_${id(aweme)}_${stamp()}"
            if (video.isNotEmpty() && !isPhotoPost(aweme)) {
                val source = temp(ctx, ".mp4")
                val out = temp(ctx, ".m4a")
                try {
                    downloadAny(video, source)
                    Dash.extractAudio(source, out)
                    return@start publish(ctx, out, "$name.m4a", "audio/mp4", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                } catch (e: Throwable) {
                    if (music.isEmpty()) throw e
                    Logger.printInfo { "TikTok: sound from the video failed, saving the post's sound: ${e.message}" }
                } finally { source.delete(); out.delete() }
            }
            val file = temp(ctx, ".bin")
            try {
                downloadAny(music, file)
                val (mime, ext) = audioType(file)
                publish(ctx, file, "$name.$ext", mime, Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            } finally { file.delete() }
        }
    }

    fun saveCommentPhoto(urls: List<String>, done: (Boolean) -> Unit) {
        val ctx = TikTokUi.app() ?: return done(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        executor.execute {
            val ok = runCatching { saveImage(ctx, urls, "tiktok_comment_${stamp()}") }
                .onFailure { Logger.printException({ "TikTok: comment photo save failed" }, it) }.isSuccess
            main.post { done(ok) }
        }
    }

    fun saveCover(aweme: Any) {
        val urls = coverUrls(aweme)
        if (urls.isEmpty()) return TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
        start(R.string.tt_saved_photo) { ctx -> saveImage(ctx, urls, "tiktok_${id(aweme)}_cover_${stamp()}") }
    }

    fun saveAvatar(user: Any) {
        val urls = avatarUrls(user)
        if (urls.isEmpty()) return TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
        val handle = Model.string(user, "getUniqueId", "uniqueId")?.filter { it.isLetterOrDigit() || it == '_' || it == '.' } ?: "user"
        start(R.string.tt_saved_avatar) { ctx -> saveImage(ctx, urls, "tiktok_${handle}_avatar_${stamp()}") }
    }

    fun saveSticker(urls: List<String>, id: String?) {
        if (urls.isEmpty()) return TikTokUi.toast(TikTokUi.t(R.string.tt_nothing_to_save))
        start(R.string.tt_saved_sticker) { ctx -> saveRawImage(ctx, urls, "tiktok_sticker_${safeId(id)}_${stamp()}") }
    }

    fun saveStickerBitmap(bitmap: Bitmap, id: String?) {
        start(R.string.tt_saved_sticker) { ctx ->
            val file = temp(ctx, ".png")
            try {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                publishImage(ctx, file, "tiktok_sticker_${safeId(id)}_${stamp()}.png", "image/png")
            } finally {
                file.delete()
            }
        }
    }

    private fun safeId(id: String?) = id?.filter { it.isLetterOrDigit() }?.takeLast(24)?.ifEmpty { null } ?: "media"

    private fun saveRawImage(ctx: Context, urls: List<String>, name: String) {
        val file = temp(ctx, ".img")
        try {
            downloadAny(urls, file)
            val head = ByteArray(16).also { b -> FileInputStream(file).use { it.read(b) } }
            val ascii = String(head, Charsets.ISO_8859_1)
            val (ext, mime) = when {
                ascii.startsWith("GIF8") -> "gif" to "image/gif"
                ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "webp" to "image/webp"
                head[0] == 0x89.toByte() && ascii.substring(1, 4) == "PNG" -> "png" to "image/png"
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg" to "image/jpeg"
                else -> "heic" to "image/heic"
            }
            publishImage(ctx, file, "$name.$ext", mime)
        } finally {
            file.delete()
        }
    }

    private fun start(@StringRes done: Int, doneText: String = "", work: (Context) -> Unit) {
        val ctx = TikTokUi.app() ?: return
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return TikTokUi.toast(TikTokUi.t(R.string.tt_busy))
        }
        TikTokUi.toast(TikTokUi.t(R.string.tt_saving))
        executor.execute {
            try {
                work(ctx)
                TikTokUi.toast(if (done != 0) TikTokUi.t(done) else doneText)
            } catch (e: Throwable) {
                Logger.printException({ "TikTok: save failed" }, e)
                TikTokUi.toast(TikTokUi.t(R.string.tt_save_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun stamp() = System.currentTimeMillis()

    private fun temp(ctx: Context, suffix: String) = File.createTempFile("nexalloy_tt_", suffix, ctx.cacheDir)

    private fun downloadAny(urls: List<String>, dest: File) {
        var last: Throwable? = null
        for (url in urls) {
            try { return download(url, dest) } catch (e: Throwable) { last = e }
        }
        throw last ?: IOException("no address")
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        System.getProperty("http.agent")?.let { conn.setRequestProperty("User-Agent", it) }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) } }
            if (dest.length() == 0L) throw IOException("empty file")
        } finally {
            conn.disconnect()
        }
    }

    private fun saveImage(ctx: Context, urls: List<String>, name: String) {
        if (urls.isEmpty()) throw IOException("no image address")
        val file = temp(ctx, ".img")
        try {
            downloadAny(urls, file)
            val head = ByteArray(16).also { b -> FileInputStream(file).use { it.read(b) } }
            val ascii = String(head, Charsets.ISO_8859_1)
            when {
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> publishImage(ctx, file, "$name.jpg", "image/jpeg")
                head[0] == 0x89.toByte() && ascii.substring(1, 4) == "PNG" -> publishImage(ctx, file, "$name.png", "image/png")
                else -> {
                    val jpeg = runCatching { toJpeg(ctx, file) }.getOrNull()
                    if (jpeg != null) try { publishImage(ctx, jpeg, "$name.jpg", "image/jpeg") } finally { jpeg.delete() }
                    else if (ascii.startsWith("RIFF")) publishImage(ctx, file, "$name.webp", "image/webp")
                    else publishImage(ctx, file, "$name.heic", "image/heic")
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun toJpeg(ctx: Context, file: File): File {
        check(Build.VERSION.SDK_INT >= 28)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val jpeg = temp(ctx, ".jpg")
        FileOutputStream(jpeg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return jpeg
    }

    private fun audioType(file: File): Pair<String, String> {
        val head = ByteArray(12).also { b -> FileInputStream(file).use { it.read(b) } }
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            ascii.startsWith("ID3") || (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> "audio/mpeg" to "mp3"
            ascii.startsWith("OggS") -> "audio/ogg" to "ogg"
            else -> "audio/mp4" to "m4a"
        }
    }

    private fun publishImage(ctx: Context, file: File, name: String, mime: String) =
        publish(ctx, file, name, mime, Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

    private fun publish(ctx: Context, file: File, name: String, mime: String, root: String, collection: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            val dir = File(Environment.getExternalStoragePublicDirectory(root), FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
        }
    }
}
