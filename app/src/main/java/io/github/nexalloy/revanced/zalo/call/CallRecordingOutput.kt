package io.github.nexalloy.revanced.zalo.call

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

object CallRecordingOutput {

    internal const val TEMP_DIRECTORY = "nexalloy_call_recordings"
    private const val SHARED_DIRECTORY = "Recordings/Zalo Call Recordings"
    private const val WAV_READY_TIMEOUT_MS = 5_000L
    private const val WAV_READY_POLL_MS = 100L
    private val PART_NAME: Pattern = Pattern.compile(
        "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part"
    )
    private val FINALIZER: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NexAlloyCallFinalizer").apply { isDaemon = true }
    }
    private val QUEUED: MutableSet<String> = Collections.synchronizedSet(HashSet())

    interface StatusListener {
        fun onSaved()

        fun onFailed()
    }

    fun tempDirectory(context: Context): File {
        val directory = File(context.cacheDir, TEMP_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    fun newPendingName(startedAt: Long, direction: String?): String {
        val safeDirection = safeDirection(direction)
        val nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        return "zalo-call-%013d-%s-%s.part".format(
            Locale.US, maxOf(0L, startedAt), safeDirection, nonce
        )
    }

    fun isNativeImportReady(file: File?): Boolean =
        file != null && file.isFile && CallRecordingTranscoder.isPcmWave(file)

    fun repairNativeImport(file: File?): Boolean =
        file != null && file.isFile && CallRecordingTranscoder.repairHeader(file)

    private fun awaitNativeWav(file: File): Boolean {
        val deadline = System.currentTimeMillis() + WAV_READY_TIMEOUT_MS
        var previousLength = -1L
        var stablePasses = 0
        while (System.currentTimeMillis() <= deadline) {
            val length = if (!file.isFile) -1L else file.length()
            if (length > 0L && isNativeImportReady(file)) {
                if (length == previousLength) {
                    stablePasses++
                    if (stablePasses >= 2) return true
                } else {
                    stablePasses = 0
                }
            } else {
                stablePasses = 0
            }
            previousLength = length
            try {
                Thread.sleep(WAV_READY_POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return repairNativeImport(file)
    }

    private fun sidecarFor(wavFile: File): File =
        File(wavFile.parentFile, wavFile.name + ".json")

    private fun writeSidecar(
        wavFile: File,
        startedAt: Long,
        displayName: String?,
        phoneNumber: String?,
    ) {
        try {
            val json = JSONObject()
            json.put("started_at", startedAt)
            json.put("display_name", displayName ?: "")
            json.put("phone_number", phoneNumber ?: "")
            FileOutputStream(sidecarFor(wavFile)).use { output ->
                output.write(json.toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun readSidecar(wavFile: File): RecoveryMeta? {
        val sidecar = sidecarFor(wavFile)
        if (!sidecar.isFile) return null
        return try {
            val json = JSONObject(sidecar.readText(StandardCharsets.UTF_8))
            RecoveryMeta(
                json.optLong("started_at", 0L),
                json.optString("display_name", ""),
                json.optString("phone_number", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun deleteSidecar(wavFile: File) {
        sidecarFor(wavFile).delete()
    }

    private fun markBad(file: File) {
        file.renameTo(File(file.parentFile, file.name + ".bad"))
        deleteSidecar(file)
    }

    private class RecoveryMeta(
        @JvmField val startedAt: Long,
        @JvmField val displayName: String,
        @JvmField val phoneNumber: String,
    )

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
                writeSidecar(wavFile, startedAt, contact.displayName, contact.phoneNumber)
                val ok = convertAndPublish(
                    app, wavFile, startedAt,
                    contact.displayName, contact.phoneNumber
                )
                if (ok) deleteSidecar(wavFile)
                if (listener != null) {
                    if (ok) listener.onSaved() else listener.onFailed()
                }
            } finally {
                QUEUED.remove(key)
            }
        }
    }

    fun recoverPending(context: Context, listener: StatusListener?) {
        val app = context.applicationContext
        FINALIZER.execute {
            val pending = tempDirectory(app).listFiles { _, name -> name.endsWith(".part") }
                ?: return@execute
            for (file in pending) {
                if (!isNativeImportReady(file) && !repairNativeImport(file)) {
                    markBad(file)
                    continue
                }
                val meta = readSidecar(file)
                val matcher = PART_NAME.matcher(file.name)
                val startedAt = meta?.startedAt?.takeIf { it > 0L }
                    ?: if (matcher.matches()) parseLong(matcher.group(1)) else file.lastModified()
                val displayName = meta?.displayName?.takeIf { it.isNotEmpty() } ?: "Zalo contact"
                val phoneNumber = meta?.phoneNumber ?: ""
                val ok = convertAndPublish(app, file, startedAt, displayName, phoneNumber)
                if (ok) {
                    deleteSidecar(file)
                    listener?.onSaved()
                } else {
                    markBad(file)
                    listener?.onFailed()
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
        if (!awaitNativeWav(wavFile)) return false
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
