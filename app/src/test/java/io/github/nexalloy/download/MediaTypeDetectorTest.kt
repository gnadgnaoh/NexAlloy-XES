package io.github.nexalloy.revanced.instagram.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class MediaTypeDetectorTest {

    @Test
    fun detectsMp4ByFtypEvenWhenUrlAndNameLookLikeImage() {
        val header = byteArrayOf(
            0, 0, 0, 24,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 0, 0
        )

        assertEquals(
            MediaTypeDetector.Kind.VIDEO,
            MediaTypeDetector.sniff(header, header.size)
        )
        assertEquals(
            "story_123.mp4",
            MediaTypeDetector.withCorrectExtension("story_123.jpg", MediaTypeDetector.Kind.VIDEO)
        )
    }

    @Test
    fun detectsJpegAndCorrectsWrongVideoExtension() {
        val header = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()
        )

        assertEquals(
            MediaTypeDetector.Kind.IMAGE,
            MediaTypeDetector.sniff(header, header.size)
        )
        assertEquals(
            "story_123.jpg",
            MediaTypeDetector.withCorrectExtension("story_123.mp4", MediaTypeDetector.Kind.IMAGE)
        )
    }

    @Test
    fun doesNotTreatHeicAsVideo() {
        val header = byteArrayOf(
            0, 0, 0, 24,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(),
            0, 0, 0, 0
        )

        assertEquals(
            MediaTypeDetector.Kind.IMAGE,
            MediaTypeDetector.sniff(header, header.size)
        )
    }

    @Test
    fun usesContentTypeWhenSignatureIsUnknown() {
        assertEquals(
            MediaTypeDetector.Kind.VIDEO,
            MediaTypeDetector.fromContentType("video/mp4; charset=binary")
        )
        assertEquals(
            MediaTypeDetector.Kind.IMAGE,
            MediaTypeDetector.fromContentType("image/webp")
        )
        assertEquals(
            MediaTypeDetector.Kind.UNKNOWN,
            MediaTypeDetector.sniff("unknown".toByteArray(StandardCharsets.US_ASCII), 7)
        )
    }
}
