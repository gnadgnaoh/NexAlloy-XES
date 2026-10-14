package io.github.nexalloy.revanced.zalo.call

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal object CallRecordingTranscoder {

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val TRANSCODE_MARGIN_MS = 10L * 60L * 1000L

    @Throws(IOException::class)
    fun wavToM4a(wavFile: File, outputFile: File) {
        val wav = readWav(wavFile)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            RandomAccessFile(wavFile, "r").use { input ->
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels
                )
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    if (wav.channels == 1) 64_000 else 96_000
                )
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)

                val activeCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec = activeCodec
                activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                activeCodec.start()
                val activeMuxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = activeMuxer

                input.seek(wav.dataOffset)
                var remaining = wav.dataLength
                val recordingDurationMs = wav.dataLength * 1000L /
                    (2L * wav.channels * wav.sampleRate)
                val deadlineMs = SystemClock.elapsedRealtime() +
                    recordingDurationMs * 2L + TRANSCODE_MARGIN_MS
                var sampleFrames = 0L
                var inputEnded = false
                var outputEnded = false
                var track = -1
                val info = MediaCodec.BufferInfo()

                while (!outputEnded) {
                    if (SystemClock.elapsedRealtime() > deadlineMs) {
                        throw IOException("AAC transcode deadline exceeded")
                    }
                    if (!inputEnded) {
                        val inputIndex = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = activeCodec.getInputBuffer(inputIndex)
                                ?: throw IOException("AAC input buffer unavailable")
                            buffer.clear()
                            var requested = minOf(remaining, buffer.remaining().toLong()).toInt()
                            requested -= requested % (2 * wav.channels)
                            val count = if (requested <= 0) -1 else read(input, buffer, requested)
                            val presentationUs = sampleFrames * 1_000_000L / wav.sampleRate
                            if (count <= 0) {
                                activeCodec.queueInputBuffer(
                                    inputIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                activeCodec.queueInputBuffer(
                                    inputIndex, 0, count, presentationUs, 0
                                )
                                remaining -= count.toLong()
                                sampleFrames += count / (2L * wav.channels)
                            }
                        }
                    }

                    val outputIndex = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw IOException("AAC output format changed twice")
                        track = activeMuxer.addTrack(activeCodec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    } else if (outputIndex >= 0) {
                        val output = activeCodec.getOutputBuffer(outputIndex)
                            ?: throw IOException("AAC output buffer unavailable")
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            if (!muxerStarted || track < 0) {
                                throw IOException("AAC muxer not started")
                            }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            activeMuxer.writeSampleData(track, output, info)
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (ignored: Throwable) {
                }
                it.release()
            }
            muxer?.let {
                if (muxerStarted) {
                    try {
                        it.stop()
                    } catch (ignored: Throwable) {
                    }
                }
                it.release()
            }
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw IOException("AAC output is empty")
        }
    }

    fun isPcmWave(file: File): Boolean = try {
        readWav(file)
        true
    } catch (ignored: IOException) {
        false
    }

    fun repairHeader(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        if (isPcmWave(file)) return true
        try {
            RandomAccessFile(file, "rw").use { input ->
                val fileLength = input.length()
                if (fileLength < 44L) return false
                if ("RIFF" != readFourCc(input)) return false
                readUnsignedInt(input)
                if ("WAVE" != readFourCc(input)) return false
                var pcm16 = false
                var dataLengthField = -1L
                var dataContent = -1L
                while (input.filePointer + 8L <= fileLength) {
                    val chunk = readFourCc(input)
                    val lengthFieldPos = input.filePointer
                    val length = readUnsignedInt(input)
                    val content = input.filePointer
                    if ("data" == chunk) {
                        dataLengthField = lengthFieldPos
                        dataContent = content
                        break
                    }
                    if ("fmt " == chunk && length >= 16L) {
                        val format = readUnsignedShort(input)
                        val channels = readUnsignedShort(input)
                        readUnsignedInt(input)
                        readUnsignedInt(input)
                        readUnsignedShort(input)
                        val bitsPerSample = readUnsignedShort(input)
                        pcm16 = format == 1 && bitsPerSample == 16 &&
                            channels >= 1 && channels <= 2
                    }
                    if (content + length > fileLength) return false
                    input.seek(content + length + (length and 1L))
                }
                if (!pcm16 || dataContent < 0L || dataContent >= fileLength) return false
                val realDataLength = fileLength - dataContent
                if (realDataLength <= 0L) return false
                input.seek(dataLengthField)
                writeUnsignedInt(input, realDataLength)
                input.seek(4L)
                writeUnsignedInt(input, fileLength - 8L)
            }
        } catch (ignored: IOException) {
            return false
        }
        return isPcmWave(file)
    }

    @Throws(IOException::class)
    private fun read(input: RandomAccessFile, buffer: ByteBuffer, requested: Int): Int {
        val bytes = ByteArray(requested)
        val count = input.read(bytes)
        if (count > 0) buffer.put(bytes, 0, count)
        return count
    }

    @Throws(IOException::class)
    private fun readWav(file: File): WavInfo {
        RandomAccessFile(file, "r").use { input ->
            if ("RIFF" != readFourCc(input)) throw IOException("Missing RIFF header")
            val riffLength = readUnsignedInt(input)
            if (riffLength + 8L != input.length()) throw IOException("Incomplete RIFF length")
            if ("WAVE" != readFourCc(input)) throw IOException("Missing WAVE header")
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataLength = -1L
            while (input.filePointer + 8L <= input.length()) {
                val chunk = readFourCc(input)
                val length = readUnsignedInt(input)
                val content = input.filePointer
                if ("fmt " == chunk && length >= 16L) {
                    val format = readUnsignedShort(input)
                    channels = readUnsignedShort(input)
                    sampleRate = readUnsignedInt(input).toInt()
                    readUnsignedInt(input)
                    readUnsignedShort(input)
                    bitsPerSample = readUnsignedShort(input)
                    if (format != 1) throw IOException("WAV is not PCM")
                } else if ("data" == chunk) {
                    if (content + length > input.length()) {
                        throw IOException("Incomplete WAV data")
                    }
                    dataOffset = content
                    dataLength = length
                    break
                }
                input.seek(content + length + (length and 1L))
            }
            if (channels < 1 || channels > 2 || sampleRate <= 0 || bitsPerSample != 16 ||
                dataOffset < 0L || dataLength <= 0L
            ) {
                throw IOException("Unsupported WAV format")
            }
            return WavInfo(channels, sampleRate, dataOffset, dataLength)
        }
    }

    @Throws(IOException::class)
    private fun readFourCc(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
    }

    @Throws(IOException::class)
    private fun readUnsignedShort(input: RandomAccessFile): Int {
        val bytes = ByteArray(2)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    @Throws(IOException::class)
    private fun readUnsignedInt(input: RandomAccessFile): Long {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    @Throws(IOException::class)
    private fun writeUnsignedInt(output: RandomAccessFile, value: Long) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((value and 0xffffffffL).toInt()).array()
        output.write(bytes)
    }

    private class WavInfo(
        @JvmField val channels: Int,
        @JvmField val sampleRate: Int,
        @JvmField val dataOffset: Long,
        @JvmField val dataLength: Long,
    )
}
