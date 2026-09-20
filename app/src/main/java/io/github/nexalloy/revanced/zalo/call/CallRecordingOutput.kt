package io.github.nexalloy.revanced.zalo.calls

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * In-process finalization for captured Zalo call audio.
 *
 * Ported and simplified from the Zalo Patch `CallRecordingStore`. The
 * original ran in the module process and received raw WAV bytes over a broadcast
 * to a module-owned receiver. NexAlloy patch code already runs inside
 * `com.zing.zalo`, which holds the audio and storage permissions, so this class
 * does the whole finalization in-process: it repairs the native WAV header,
 * transcodes it to M4A with [CallRecordingTranscoder], and publishes the
 * result to the shared MediaStore under Zalo's own UID.
 *
 * All work is off the caller's thread on a single-thread executor.
 */
object CallRecordingOutput {

    /** Cache subdirectory that holds native temp WAV files. */
    internal const val TEMP_DIRECTORY = "nexalloy_call_recordings"
    private const val SHARED_DIRECTORY = "Recordings/Zalo Call Recordings"
    private val PART_NAME: Pattern = Pattern.compile(
        "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part"
    )
    private val FINALIZER: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NexAlloyCallFinalizer").apply { isDaemon = true }
    }
    private val QUEUED: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Optional status sink so the patch can drive recording notifications. */
    interface StatusListener {
        fun onSaved()

        fun onFailed()
    }

    /** Directory (in Zalo's cache) where native WAV temp files are written. */
    fun tempDirectory(context: Context): File {
        val directory = File(context.cacheDir, TEMP_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    fun newPendingName(startedAt: Long, direction: String?): String {
        val safeDirection = safeDirection(direction)
        val nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        return String.format(
            Locale.US, "zalo-call-%013d-%s-%s.part",
            maxOf(0L, startedAt), safeDirection, nonce
        )
    }

    fun isNativeImportReady(file: File?): Boolean =
        file != null && file.isFile && CallRecordingTranscoder.isPcmWave(file)

    fun repairNativeImport(file: File?): Boolean =
        file != null && file.isFile && CallRecordingTranscoder.repairHeader(file)

    /**
     * Queues the captured native WAV for conversion and publication. Caller
     * identity is resolved on the finalizer thread from Zalo's local databases
     * (via [CallRecordingContacts]), falling back to the values observed
     * from the call notification. The source file is consumed (deleted) once the
     * M4A is stored.
     */
    fun finalizeRecording(
        context: Context?,
        wavFile: File?,
        startedAt: Long,
        direction: String?,
        peerUid: String?,
        fallbackName: String?,
        fallbackPhone: String?,
        listener: StatusListener?,
    ) {
        if (context == null || wavFile == null) {
            listener?.onFailed()
            return
        }
        val app = context.applicationContext
        val key = wavFile.absolutePath
        if (!QUEUED.add(key)) return
        FINALIZER.execute {
            try {
                val contact =
                    CallRecordingContacts.resolve(app, peerUid, fallbackName, fallbackPhone)
                val ok = convertAndPublish(
                    app, wavFile, startedAt,
                    contact.displayName, contact.phoneNumber
                )
                if (listener != null) {
                    if (ok) listener.onSaved() else listener.onFailed()
                }
            } finally {
                QUEUED.remove(key)
            }
        }
    }

    /** Re-attempts any leftover WAV files from a call that ended during a crash/kill. */
    fun recoverPending(context: Context, listener: StatusListener?) {
        val app = context.applicationContext
        FINALIZER.execute {
            val pending = tempDirectory(app).listFiles { _, name -> name.endsWith(".part") }
                ?: return@execute
            for (file in pending) {
                if (!isNativeImportReady(file) && !repairNativeImport(file)) continue
                val matcher = PART_NAME.matcher(file.name)
                val startedAt =
                    if (matcher.matches()) parseLong(matcher.group(1)) else file.lastModified()
                val ok = convertAndPublish(app, file, startedAt, "Zalo contact", "")
                if (listener != null) {
                    if (ok) listener.onSaved() else listener.onFailed()
                }
            }
        }
    }

    private fun convertAndPublish(
        context: Context,
        wavFile: File,
        startedAt: Long,
        displayName: String,
        phoneNumber: String,
    ): Boolean {
        if (!isNativeImportReady(wavFile) && !repairNativeImport(wavFile)) return false
        val encoded = File(wavFile.parentFile, wavFile.name + ".m4a.tmp")
        return try {
            CallRecordingTranscoder.wavToM4a(wavFile, encoded)
            val saved = publish(
                context, encoded,
                buildDisplayName(startedAt, displayName, phoneNumber)
            )
            if (saved == null) {
                false
            } else {
                wavFile.delete()
                true
            }
        } catch (throwable: Throwable) {
            false
        } finally {
            encoded.delete()
        }
    }

    @Throws(IOException::class)
    private fun publish(context: Context, source: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Shared call recordings require Android 10 or newer")
        }
        val resolver = context.contentResolver
        val values = ContentValues()
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        values.put(
            MediaStore.Audio.Media.TITLE,
            displayName.substring(0, displayName.length - ".m4a".length)
        )
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED_DIRECTORY)
        values.put(MediaStore.Audio.Media.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            FileInputStream(source).use { input ->
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Shared output unavailable")
                output.use { copy(input, it) }
            }
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            if (throwable is IOException) throw throwable
            throw IOException(throwable)
        }
        val complete = ContentValues()
        complete.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, complete, null, null)
        return uri
    }

    internal fun buildDisplayName(
        startedAt: Long,
        displayName: String?,
        phoneNumber: String?,
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
            .format(Date(if (startedAt > 0L) startedAt else System.currentTimeMillis()))
        val name = StringBuilder(timestamp).append(" - ")
            .append(sanitizeFilename(safeDisplayName(displayName)))
        val safePhone = safePhone(phoneNumber)
        if (safePhone.isNotEmpty()) {
            name.append(" - ").append(safePhone)
        }
        return name.append(".m4a").toString()
    }

    private fun sanitizeFilename(value: String?): String {
        var clean = value?.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length - 1).trim()
        }
        if (clean.isEmpty()) clean = "Zalo contact"
        return if (clean.length > 80) clean.substring(0, 80).trim() else clean
    }

    private fun safeDisplayName(value: String?): String =
        if (value == null || value.trim().isEmpty()) "Zalo contact" else value.trim()

    private fun safePhone(value: String?): String {
        if (value == null) return ""
        val plus = value.trim().startsWith("+")
        val digits = value.replace(Regex("\\D"), "")
        if (digits.length < 8 || digits.length > 15) return ""
        return if (plus) "+$digits" else digits
    }

    private fun safeDirection(direction: String?): String =
        if ("incoming" == direction || "outgoing" == direction) direction!! else "unknown"

    @Throws(IOException::class)
    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
        }
    }

    private fun parseLong(value: String?): Long = try {
        value?.toLong() ?: 0L
    } catch (ignored: NumberFormatException) {
        0L
    }
}
