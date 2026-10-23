package io.github.nexalloy.revanced.messenger.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import app.morphe.extension.shared.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object OriginalPhoto {
    private const val MAX_BYTES = 20_000_000L
    private const val MIN_TARGET = 1024.0
    private const val HD_TARGET = 4096.0
    private const val MAX_DECODE_BYTES = 400L shl 20
    private const val MAX_SAMPLE = 64
    private const val NATIVE_TRANSCODER = "ENABLE_UIT_TRANSCODING"
    private const val KEEP_COPIES_MS = 7L * 24 * 60 * 60 * 1000

    lateinit var context: Context
    private val completion = Executors.newSingleThreadExecutor()
    private val swept = AtomicBoolean()
    private val decodeRequest = ThreadLocal<Decode>()

    private class NotPassable(reason: String) : IOException(reason)

    private class Prepared(val copy: File, val width: Int, val height: Int, val orientation: Int) {
        fun quarterTurn() = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
        fun describe() = "${copy.length()} bytes, ${width}x$height" + if (orientation == 0) "" else ", rotation tag $orientation"
    }

    private class Decode(val width: Double, val height: Double, val sample: Int)

    private class LimitedInput(file: File) : InputStream() {
        private val input = FileInputStream(file)
        private var consumed = 0L
        private var exceeded = false

        init {
            try { check() } catch (e: Throwable) { input.close(); throw e }
        }

        fun check() {
            if (exceeded || input.channel.size() > MAX_BYTES) throw NotPassable("over 20 MB")
        }

        private fun count(bytes: Long) {
            if (bytes > 0) consumed += bytes
            if (consumed > MAX_BYTES) { exceeded = true; throw NotPassable("over 20 MB") }
        }

        override fun read(): Int {
            count(0); val v = input.read(); count(if (v < 0) 0 else 1); return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            count(0)
            val n = input.read(b, off, minOf(len.toLong(), MAX_BYTES - consumed + 1).toInt())
            count(n.toLong()); return n
        }

        override fun skip(n: Long): Long {
            count(0)
            val s = input.skip(maxOf(0, minOf(n, MAX_BYTES - consumed + 1)))
            count(s); return s
        }

        override fun available(): Int { count(0); return minOf(input.available().toLong(), MAX_BYTES - consumed).toInt() }
        override fun close() = input.close()
    }

    private fun <T> skip(reason: String): T? {
        Logger.printDebug { "Messenger: original photo skipped: $reason" }
        return null
    }

    private fun pathOf(url: String?): String? = when {
        url == null -> null
        url.startsWith("file:") -> Uri.parse(url).path
        File(url).isAbsolute -> url
        else -> null
    }

    fun sync(url: String?, maxWidth: Double, maxHeight: Double, extras: Map<*, *>?): ByteArray? = try {
        prepare(url, maxWidth, maxHeight, extras)?.let { prepared ->
            try {
                Logger.printDebug { "Messenger: original photo ${prepared.describe()}" }
                LimitedInput(prepared.copy).use { input ->
                    ByteArrayOutputStream(minOf(prepared.copy.length(), MAX_BYTES).toInt()).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (n == 0) throw IOException("No image data")
                            out.write(buffer, 0, n)
                        }
                        input.check()
                        out.toByteArray()
                    }
                }
            } finally {
                prepared.copy.delete()
            }
        }
    } catch (e: Throwable) {
        Logger.printInfo { "Messenger: original photo failed, Messenger's copy is sent: $e" }
        null
    }

    fun async(url: String?, maxWidth: Double, maxHeight: Double, extras: Map<*, *>?, callback: Any?): Boolean {
        callback ?: return false
        var prepared: Prepared? = null
        return try {
            val success = callback.javaClass.getMethod(
                "success", String::class.java, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            )
            val failure = callback.javaClass.getMethod(
                "failure", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Throwable::class.java,
            )
            prepared = prepare(url, maxWidth, maxHeight, extras) ?: return false
            val sent: Prepared = prepared
            Logger.printDebug { "Messenger: original photo ${sent.describe()}" }
            completion.execute { report(callback, success, failure, sent) }
            true
        } catch (e: Throwable) {
            prepared?.copy?.delete()
            Logger.printInfo { "Messenger: original photo failed, Messenger's copy is sent: $e" }
            false
        }
    }

    private fun report(callback: Any, success: Method, failure: Method, sent: Prepared) {
        val width = sent.width.toDouble()
        val height = sent.height.toDouble()
        try {
            LimitedInput(sent.copy).use { it.check() }
            val rotated = sent.orientation >= ExifInterface.ORIENTATION_ROTATE_180
            success.invoke(
                callback, Uri.fromFile(sent.copy).toString(), width, height,
                if (sent.quarterTurn()) height else width, if (sent.quarterTurn()) width else height,
                100.0, -1.0, rotated, 0, false, 0.0, 0.0, 0.0,
            )
        } catch (e: InvocationTargetException) {
            Logger.printInfo { "Messenger: original photo success callback failed: ${e.cause}" }
        } catch (e: Throwable) {
            sent.copy.delete()
            Logger.printInfo { "Messenger: original photo couldn't hand over its copy: $e" }
            runCatching { failure.invoke(callback, width, height, IOException("NexAlloy couldn't hand over the original photo")) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun transcodeConfig(config: Any?) {
        val map = config as? MutableMap<Any?, Any?> ?: return
        runCatching { if (map[NATIVE_TRANSCODER] is Boolean) map[NATIVE_TRANSCODER] = false }
    }

    fun decodeLimits(url: String?, maxWidth: Double, maxHeight: Double): Pair<Double, Double>? {
        decodeRequest.remove()
        if (!(maxWidth > 0 && maxHeight > 0)) return null
        return try {
            val decode = lift(url, maxWidth, maxHeight) ?: Decode(maxWidth, maxHeight, 0)
            decodeRequest.set(decode)
            decode.width to decode.height
        } catch (e: Throwable) {
            decodeRequest.remove()
            Logger.printInfo { "Messenger: original photo couldn't size a photo: $e" }
            null
        }
    }

    fun clearDecode() = decodeRequest.remove()

    fun hasDecode() = decodeRequest.get() != null

    fun decodeFile(path: String?): Bitmap? {
        val decode = decodeRequest.get()
        decodeRequest.remove()
        if (decode == null || path == null) return BitmapFactory.decodeFile(path, null)
        var options = BitmapFactory.Options()
        try {
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            val sample = if (decode.sample > 0) decode.sample
            else if (options.outWidth > 0 && options.outHeight > 0) sample(options.outWidth, options.outHeight, decode.width, decode.height)
            else 1
            options = BitmapFactory.Options().apply { inSampleSize = sample }
        } catch (e: RuntimeException) {
            return BitmapFactory.decodeFile(path, null)
        }
        while (true) {
            try {
                return BitmapFactory.decodeFile(path, options)
            } catch (e: OutOfMemoryError) {
                if (options.inSampleSize >= MAX_SAMPLE) return null
                options.inSampleSize *= 2
            }
        }
    }

    private fun lift(url: String?, maxWidth: Double, maxHeight: Double): Decode? {
        val target = minOf(maxWidth, maxHeight)
        if (target < HD_TARGET) return null
        val path = pathOf(url) ?: return skip("not a file")
        val file = File(path)
        val size = file.length()
        if (!file.isFile || size <= 0) return skip("unreadable file")
        if (size > MAX_BYTES) return skip("over 20 MB")
        if (!startsLikeJpeg(file)) return skip("not a JPEG")
        val bounds = bounds(path) ?: return skip("no image size")
        val longSide = maxOf(bounds[0], bounds[1])
        if (longSide <= target) return null
        val copies = if (orientation(file, ExifInterface.ORIENTATION_NORMAL) > ExifInterface.ORIENTATION_NORMAL) 2 else 1
        val budget = minOf(MAX_DECODE_BYTES, freeMemory())
        var sample = 1
        while (sample <= MAX_SAMPLE) {
            val side = (longSide + sample - 1) / sample
            if (side <= target) break
            val bytes = 4L * copies * ((bounds[0] + sample - 1) / sample) * ((bounds[1] + sample - 1) / sample)
            if (bytes <= budget) return Decode(side.toDouble(), side.toDouble(), sample)
            sample *= 2
        }
        return skip("not enough memory for ${bounds[0]}x${bounds[1]}")
    }

    private fun sample(width: Int, height: Int, maxWidth: Double, maxHeight: Double): Int {
        val ratio = minOf(maxOf(width / maxWidth, height / maxHeight), maxOf(height / maxWidth, width / maxHeight))
        var sample = 1
        while (sample < MAX_SAMPLE && sample * 2 <= ratio) sample *= 2
        return sample
    }

    private fun freeMemory(): Long {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return if (info.lowMemory) 0 else maxOf(0, (info.availMem - info.threshold) / 2)
    }

    private fun prepare(url: String?, maxWidth: Double, maxHeight: Double, extras: Map<*, *>?): Prepared? {
        if (extras == null || extras["IS_HD"] != true || extras["IS_PREVIEW"] == true) return null
        val longTarget = maxOf(maxWidth, maxHeight)
        val shortTarget = minOf(maxWidth, maxHeight)
        if (longTarget > 0 && longTarget < MIN_TARGET) return skip("preview size")
        val path = pathOf(url) ?: return skip("not a file")
        val file = File(path)
        val size = file.length()
        if (!file.isFile || size <= 0) return skip("unreadable file")
        if (size > MAX_BYTES) return skip("over 20 MB")
        if (!startsLikeJpeg(file)) return skip("not a JPEG")
        var orientation = orientation(file, ExifInterface.ORIENTATION_NORMAL)
        if (orientation < ExifInterface.ORIENTATION_UNDEFINED || orientation > ExifInterface.ORIENTATION_ROTATE_270) {
            return skip("unknown rotation tag $orientation")
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL) orientation = ExifInterface.ORIENTATION_UNDEFINED
        val bounds = bounds(path) ?: return skip("no image size")
        val longSide = maxOf(bounds[0], bounds[1])
        val shortSide = minOf(bounds[0], bounds[1])
        if (longTarget > 0 && (longSide > longTarget || shortSide > shortTarget)) {
            return skip("${bounds[0]}x${bounds[1]} is larger than ${maxWidth.toInt()}x${maxHeight.toInt()}")
        }
        val dir = context.cacheDir
        if (swept.compareAndSet(false, true)) sweepOldCopies(dir)
        val copy = File.createTempFile("nexalloy-photo", ".jpg", dir)
        try {
            copyImageData(file, copy, orientation)
            val copied = bounds(copy.path)
            if (copied == null || copied[0] != bounds[0] || copied[1] != bounds[1]) throw NotPassable("copy decodes differently")
            if (orientation(copy, ExifInterface.ORIENTATION_UNDEFINED) != orientation) throw NotPassable("rotation tag didn't carry over")
        } catch (e: NotPassable) {
            copy.delete(); return skip(e.message ?: "")
        } catch (e: Throwable) {
            copy.delete(); throw e
        }
        return Prepared(copy, bounds[0], bounds[1], orientation)
    }

    private fun sweepOldCopies(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles { _, name -> name.startsWith("nexalloy-photo") && name.endsWith(".jpg") }
            ?.filter { now - it.lastModified() > KEEP_COPIES_MS }
            ?.forEach { it.delete() }
    }

    private fun bounds(path: String): IntArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        LimitedInput(File(path)).use { source ->
            BufferedInputStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            source.check()
        }
        return if (options.outWidth > 0 && options.outHeight > 0) intArrayOf(options.outWidth, options.outHeight) else null
    }

    private fun startsLikeJpeg(file: File): Boolean = LimitedInput(file).use { input ->
        val jpeg = input.read() == 0xFF && input.read() == 0xD8
        input.check()
        jpeg
    }

    private fun orientation(file: File, fallback: Int): Int = LimitedInput(file).use { input ->
        val value = ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, fallback)
        input.check()
        value
    }

    private fun copyImageData(source: File, target: File, orientation: Int) {
        try {
            LimitedInput(source).use { sourceInput ->
                DataInputStream(BufferedInputStream(sourceInput, 65536)).use { input ->
                    BufferedOutputStream(FileOutputStream(target), 65536).use { out ->
                        if (input.readUnsignedByte() != 0xFF || input.readUnsignedByte() != 0xD8) throw NotPassable("not a JPEG")
                        out.write(0xFF); out.write(0xD8)
                        var exifDue = orientation != 0
                        var marker = nextMarker(input)
                        while (true) {
                            if (marker == 0xD9) { out.write(0xFF); out.write(0xD9); break }
                            if (marker == 0x01 || marker in 0xD0..0xD7) throw NotPassable("stray marker")
                            val length = input.readUnsignedShort()
                            if (length < 2) throw NotPassable("bad segment length")
                            var payload = ByteArray(length - 2)
                            input.readFully(payload)
                            if (keep(marker, payload)) {
                                if (marker == 0xE0) {
                                    if (payload.size < 14) throw NotPassable("short JFIF header")
                                    payload = payload.copyOf(14)
                                    payload[12] = 0; payload[13] = 0
                                }
                                if (exifDue && marker != 0xE0) {
                                    writeSegment(out, 0xE1, orientationExif(orientation))
                                    exifDue = false
                                }
                                writeSegment(out, marker, payload)
                            }
                            marker = if (marker == 0xDA) copyScan(input, out) else nextMarker(input)
                        }
                        sourceInput.check()
                        out.flush()
                    }
                }
            }
            if (target.length() > MAX_BYTES) throw NotPassable("over 20 MB")
        } catch (e: EOFException) {
            throw NotPassable("ends early")
        }
    }

    private fun writeSegment(out: OutputStream, marker: Int, payload: ByteArray) {
        val length = payload.size + 2
        out.write(0xFF); out.write(marker); out.write(length shr 8); out.write(length and 0xFF); out.write(payload)
    }

    private fun orientationExif(orientation: Int) = byteArrayOf(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0,
    )

    private fun nextMarker(input: DataInputStream): Int {
        if (input.readUnsignedByte() != 0xFF) throw NotPassable("expected a marker")
        var marker: Int
        do marker = input.readUnsignedByte() while (marker == 0xFF)
        if (marker == 0x00) throw NotPassable("expected a marker")
        return marker
    }

    private fun copyScan(input: DataInputStream, out: OutputStream): Int {
        while (true) {
            val value = input.readUnsignedByte()
            if (value != 0xFF) { out.write(value); continue }
            var next: Int
            do next = input.readUnsignedByte() while (next == 0xFF)
            if (next == 0x00 || next in 0xD0..0xD7) { out.write(0xFF); out.write(next); continue }
            return next
        }
    }

    private fun keep(marker: Int, payload: ByteArray): Boolean = when (marker) {
        0xE0 -> startsWith(payload, "JFIF\u0000")
        0xE2 -> startsWith(payload, "ICC_PROFILE\u0000")
        0xEE -> startsWith(payload, "Adobe")
        else -> !(marker in 0xE0..0xEF) && marker != 0xFE
    }

    private fun startsWith(payload: ByteArray, prefix: String): Boolean {
        val expected = prefix.toByteArray(Charsets.US_ASCII)
        if (payload.size < expected.size) return false
        return expected.indices.all { payload[it] == expected[it] }
    }
}
