package io.github.nexalloy.revanced.zalo.calls;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class CallRecordingTranscoder {
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long TRANSCODE_MARGIN_MS = 10L * 60L * 1000L;

    private CallRecordingTranscoder() {
    }

    static void wavToM4a(File wavFile, File outputFile) throws IOException {
        WavInfo wav = readWav(wavFile);
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try (RandomAccessFile input = new RandomAccessFile(wavFile, "r")) {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, wav.channels == 1 ? 64_000 : 96_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            input.seek(wav.dataOffset);
            long remaining = wav.dataLength;
            long recordingDurationMs = wav.dataLength * 1000L
                    / (2L * wav.channels * wav.sampleRate);
            long deadlineMs = SystemClock.elapsedRealtime()
                    + recordingDurationMs * 2L + TRANSCODE_MARGIN_MS;
            long sampleFrames = 0L;
            boolean inputEnded = false;
            boolean outputEnded = false;
            int track = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputEnded) {
                if (SystemClock.elapsedRealtime() > deadlineMs) {
                    throw new IOException("AAC transcode deadline exceeded");
                }
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                        if (buffer == null) {
                            throw new IOException("AAC input buffer unavailable");
                        }
                        buffer.clear();
                        int requested = (int) Math.min(remaining, buffer.remaining());
                        requested -= requested % (2 * wav.channels);
                        int count = requested <= 0 ? -1 : read(input, buffer, requested);
                        long presentationUs = sampleFrames * 1_000_000L / wav.sampleRate;
                        if (count <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count, presentationUs, 0);
                            remaining -= count;
                            sampleFrames += count / (2L * wav.channels);
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IOException("AAC output format changed twice");
                    }
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output == null) {
                        throw new IOException("AAC output buffer unavailable");
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0) {
                        if (!muxerStarted || track < 0) {
                            throw new IOException("AAC muxer not started");
                        }
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        muxer.writeSampleData(track, output, info);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Throwable ignored) {
                    }
                }
                muxer.release();
            }
        }
        if (!outputFile.isFile() || outputFile.length() <= 0L) {
            throw new IOException("AAC output is empty");
        }
    }

    static boolean isPcmWave(File file) {
        try {
            readWav(file);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Rebuilds the RIFF and {@code data} chunk length fields from the file's real
     * size and re-validates.
     *
     * <p>Some native ZRTC writers stream PCM samples into the WAV but never
     * back-patch the placeholder length fields when the recording stops, so the
     * finished file fails {@link #isPcmWave} even though it holds valid audio.
     * When the file already validates, or has no recoverable {@code data} chunk,
     * this leaves it untouched.
     */
    static boolean repairHeader(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        if (isPcmWave(file)) {
            return true;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "rw")) {
            long fileLength = input.length();
            if (fileLength < 44L) {
                return false;
            }
            if (!"RIFF".equals(readFourCc(input))) {
                return false;
            }
            readUnsignedInt(input);
            if (!"WAVE".equals(readFourCc(input))) {
                return false;
            }
            boolean pcm16 = false;
            long dataLengthField = -1L;
            long dataContent = -1L;
            while (input.getFilePointer() + 8L <= fileLength) {
                String chunk = readFourCc(input);
                long lengthFieldPos = input.getFilePointer();
                long length = readUnsignedInt(input);
                long content = input.getFilePointer();
                if ("data".equals(chunk)) {
                    dataLengthField = lengthFieldPos;
                    dataContent = content;
                    break;
                }
                if ("fmt ".equals(chunk) && length >= 16L) {
                    int format = readUnsignedShort(input);
                    int channels = readUnsignedShort(input);
                    readUnsignedInt(input);
                    readUnsignedInt(input);
                    readUnsignedShort(input);
                    int bitsPerSample = readUnsignedShort(input);
                    pcm16 = format == 1 && bitsPerSample == 16
                            && channels >= 1 && channels <= 2;
                }
                if (content + length > fileLength) {
                    return false;
                }
                input.seek(content + length + (length & 1L));
            }
            if (!pcm16 || dataContent < 0L || dataContent >= fileLength) {
                return false;
            }
            long realDataLength = fileLength - dataContent;
            if (realDataLength <= 0L) {
                return false;
            }
            input.seek(dataLengthField);
            writeUnsignedInt(input, realDataLength);
            input.seek(4L);
            writeUnsignedInt(input, fileLength - 8L);
        } catch (IOException ignored) {
            return false;
        }
        return isPcmWave(file);
    }

    private static int read(RandomAccessFile input, ByteBuffer buffer, int requested)
            throws IOException {
        byte[] bytes = new byte[requested];
        int count = input.read(bytes);
        if (count > 0) {
            buffer.put(bytes, 0, count);
        }
        return count;
    }

    private static WavInfo readWav(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (!"RIFF".equals(readFourCc(input))) {
                throw new IOException("Missing RIFF header");
            }
            long riffLength = readUnsignedInt(input);
            if (riffLength + 8L != input.length()) {
                throw new IOException("Incomplete RIFF length");
            }
            if (!"WAVE".equals(readFourCc(input))) {
                throw new IOException("Missing WAVE header");
            }
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            long dataOffset = -1L;
            long dataLength = -1L;
            while (input.getFilePointer() + 8L <= input.length()) {
                String chunk = readFourCc(input);
                long length = readUnsignedInt(input);
                long content = input.getFilePointer();
                if ("fmt ".equals(chunk) && length >= 16L) {
                    int format = readUnsignedShort(input);
                    channels = readUnsignedShort(input);
                    sampleRate = (int) readUnsignedInt(input);
                    readUnsignedInt(input);
                    readUnsignedShort(input);
                    bitsPerSample = readUnsignedShort(input);
                    if (format != 1) {
                        throw new IOException("WAV is not PCM");
                    }
                } else if ("data".equals(chunk)) {
                    if (content + length > input.length()) {
                        throw new IOException("Incomplete WAV data");
                    }
                    dataOffset = content;
                    dataLength = length;
                    break;
                }
                input.seek(content + length + (length & 1L));
            }
            if (channels < 1 || channels > 2 || sampleRate <= 0 || bitsPerSample != 16
                    || dataOffset < 0L || dataLength <= 0L) {
                throw new IOException("Unsupported WAV format");
            }
            return new WavInfo(channels, sampleRate, dataOffset, dataLength);
        }
    }

    private static String readFourCc(RandomAccessFile input) throws IOException {
        byte[] bytes = new byte[4];
        input.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readUnsignedShort(RandomAccessFile input) throws IOException {
        byte[] bytes = new byte[2];
        input.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static long readUnsignedInt(RandomAccessFile input) throws IOException {
        byte[] bytes = new byte[4];
        input.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
    }

    private static void writeUnsignedInt(RandomAccessFile output, long value)
            throws IOException {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (value & 0xffffffffL)).array();
        output.write(bytes);
    }

    private static final class WavInfo {
        final int channels;
        final int sampleRate;
        final long dataOffset;
        final long dataLength;

        WavInfo(int channels, int sampleRate, long dataOffset, long dataLength) {
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }
    }
}
