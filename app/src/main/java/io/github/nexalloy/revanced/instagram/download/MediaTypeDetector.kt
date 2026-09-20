package io.github.nexalloy.revanced.instagram.download

import java.io.File
import java.io.FileInputStream

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Detects downloaded media from its response header and file signature. */
internal object MediaTypeDetector {

    enum class Kind { VIDEO, IMAGE, UNKNOWN }

    internal class Result(
        @JvmField val kind: Kind,
        @JvmField val mimeType: String,
        @JvmField val filename: String?,
    ) {
        fun isVideo(): Boolean = kind == Kind.VIDEO
    }

    fun resolve(
        file: File,
        responseContentType: String?,
        requestedMime: String?,
        requestedFilename: String?,
    ): Result {
        var kind = sniff(file)
        if (kind == Kind.UNKNOWN) kind = fromContentType(responseContentType)
        if (kind == Kind.UNKNOWN) kind = fromContentType(requestedMime)

        var mime = when (kind) {
            Kind.VIDEO -> "video/mp4"
            Kind.IMAGE -> "image/jpeg"
            else -> normalizeContentType(requestedMime)
        }
        if (mime.isNullOrEmpty()) mime = "application/octet-stream"
        return Result(kind, mime, withCorrectExtension(requestedFilename, kind))
    }

    fun fromContentType(contentType: String?): Kind {
        val normalized = normalizeContentType(contentType) ?: return Kind.UNKNOWN
        if (normalized.startsWith("video/")) return Kind.VIDEO
        if (normalized.startsWith("image/")) return Kind.IMAGE
        return Kind.UNKNOWN
    }

    fun withCorrectExtension(filename: String?, kind: Kind): String? {
        if (filename.isNullOrEmpty() || kind == Kind.UNKNOWN) return filename
        val wanted = if (kind == Kind.VIDEO) ".mp4" else ".jpg"
        val lower = filename.lowercase(Locale.US)
        if (lower.endsWith(wanted)) return filename

        val slash = maxOf(filename.lastIndexOf('/'), filename.lastIndexOf('\\'))
        val dot = filename.lastIndexOf('.')
        if (dot > slash) return filename.substring(0, dot) + wanted
        return filename + wanted
    }

    fun sniff(file: File): Kind = FileInputStream(file).use { input ->
        val header = ByteArray(16)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        sniff(header, read)
    }

    fun sniff(header: ByteArray?, length: Int): Kind {
        if (header == null || length < 3) return Kind.UNKNOWN

        if (u(header[0]) == 0xff && u(header[1]) == 0xd8 && u(header[2]) == 0xff) {
            return Kind.IMAGE
        }
        if (length >= 8 &&
            u(header[0]) == 0x89 && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() &&
            header[3] == 'G'.code.toByte() && u(header[4]) == 0x0d && u(header[5]) == 0x0a &&
            u(header[6]) == 0x1a && u(header[7]) == 0x0a
        ) {
            return Kind.IMAGE
        }
        if (length >= 6 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte() &&
            (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte()
        ) {
            return Kind.IMAGE
        }
        if (length >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        ) {
            return Kind.IMAGE
        }

        // MP4 and HEIF/AVIF share ISO-BMFF's ftyp box.
        if (length >= 12 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        ) {
            val brand = String(header, 8, 4, StandardCharsets.US_ASCII).lowercase(Locale.US)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) {
                return Kind.IMAGE
            }
            return Kind.VIDEO
        }
        return Kind.UNKNOWN
    }

    private fun normalizeContentType(contentType: String?): String? {
        if (contentType == null) return null
        val semicolon = contentType.indexOf(';')
        val normalized = (if (semicolon >= 0) contentType.substring(0, semicolon) else contentType)
            .trim().lowercase(Locale.US)
        return normalized.ifEmpty { null }
    }

    private fun u(value: Byte): Int = value.toInt() and 0xff
}
