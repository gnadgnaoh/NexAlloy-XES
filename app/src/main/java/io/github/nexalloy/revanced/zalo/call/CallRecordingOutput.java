package io.github.nexalloy.revanced.zalo.calls;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process finalization for captured Zalo call audio.
 *
 * <p>Ported and simplified from the Zalo Patch {@code CallRecordingStore}. The
 * original ran in the module process and received raw WAV bytes over a broadcast
 * to a module-owned receiver. NexAlloy patch code already runs inside {@code
 * com.zing.zalo}, which holds the audio and storage permissions, so this class
 * does the whole finalization in-process: it repairs the native WAV header,
 * transcodes it to M4A with {@link CallRecordingTranscoder}, and publishes the
 * result to the shared MediaStore under Zalo's own UID.
 *
 * <p>All work is off the caller's thread on a single-thread executor.
 */
public final class CallRecordingOutput {
    /** Cache subdirectory that holds native temp WAV files. */
    static final String TEMP_DIRECTORY = "nexalloy_call_recordings";
    private static final String SHARED_DIRECTORY = "Recordings/Zalo Call Recordings";
    private static final Pattern PART_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part");
    private static final ExecutorService FINALIZER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NexAlloyCallFinalizer");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<>());

    /** Optional status sink so the patch can drive recording notifications. */
    public interface StatusListener {
        void onSaved();

        void onFailed();
    }

    private CallRecordingOutput() {
    }

    /** Directory (in Zalo's cache) where native WAV temp files are written. */
    public static File tempDirectory(Context context) {
        File directory = new File(context.getCacheDir(), TEMP_DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    public static String newPendingName(long startedAt, String direction) {
        String safeDirection = safeDirection(direction);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format(Locale.US, "zalo-call-%013d-%s-%s.part",
                Math.max(0L, startedAt), safeDirection, nonce);
    }

    public static boolean isNativeImportReady(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.isPcmWave(file);
    }

    public static boolean repairNativeImport(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.repairHeader(file);
    }

    /**
     * Queues the captured native WAV for conversion and publication. Caller
     * identity is resolved on the finalizer thread from Zalo's local databases
     * (via {@link CallRecordingContacts}), falling back to the values observed
     * from the call notification. The source file is consumed (deleted) once the
     * M4A is stored.
     */
    public static void finalizeRecording(
            Context context,
            File wavFile,
            long startedAt,
            String direction,
            String peerUid,
            String fallbackName,
            String fallbackPhone,
            StatusListener listener) {
        if (context == null || wavFile == null) {
            if (listener != null) listener.onFailed();
            return;
        }
        final Context app = context.getApplicationContext();
        final String key = wavFile.getAbsolutePath();
        if (!QUEUED.add(key)) {
            return;
        }
        FINALIZER.execute(() -> {
            try {
                CallRecordingContacts.Result contact =
                        CallRecordingContacts.resolve(app, peerUid, fallbackName, fallbackPhone);
                boolean ok = convertAndPublish(app, wavFile, startedAt,
                        contact.displayName, contact.phoneNumber);
                if (listener != null) {
                    if (ok) listener.onSaved();
                    else listener.onFailed();
                }
            } finally {
                QUEUED.remove(key);
            }
        });
    }

    /** Re-attempts any leftover WAV files from a call that ended during a crash/kill. */
    public static void recoverPending(Context context, StatusListener listener) {
        final Context app = context.getApplicationContext();
        FINALIZER.execute(() -> {
            File[] pending = tempDirectory(app).listFiles(
                    (ignored, name) -> name.endsWith(".part"));
            if (pending == null) {
                return;
            }
            for (File file : pending) {
                if (!isNativeImportReady(file) && !repairNativeImport(file)) {
                    continue;
                }
                Matcher matcher = PART_NAME.matcher(file.getName());
                long startedAt = matcher.matches() ? parseLong(matcher.group(1)) : file.lastModified();
                boolean ok = convertAndPublish(app, file, startedAt, "Zalo contact", "");
                if (listener != null) {
                    if (ok) listener.onSaved();
                    else listener.onFailed();
                }
            }
        });
    }

    private static boolean convertAndPublish(
            Context context, File wavFile, long startedAt, String displayName, String phoneNumber) {
        if (!isNativeImportReady(wavFile) && !repairNativeImport(wavFile)) {
            return false;
        }
        File encoded = new File(wavFile.getParentFile(), wavFile.getName() + ".m4a.tmp");
        try {
            CallRecordingTranscoder.wavToM4a(wavFile, encoded);
            Uri saved = publish(context, encoded,
                    buildDisplayName(startedAt, displayName, phoneNumber));
            if (saved == null) {
                return false;
            }
            wavFile.delete();
            return true;
        } catch (Throwable throwable) {
            return false;
        } finally {
            encoded.delete();
        }
    }

    private static Uri publish(Context context, File source, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Shared call recordings require Android 10 or newer");
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.TITLE,
                displayName.substring(0, displayName.length() - ".m4a".length()));
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED_DIRECTORY);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("Shared output unavailable");
            }
            copy(input, output);
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException(throwable);
        }
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        return uri;
    }

    static String buildDisplayName(long startedAt, String displayName, String phoneNumber) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
                .format(new Date(startedAt > 0L ? startedAt : System.currentTimeMillis()));
        StringBuilder name = new StringBuilder(timestamp).append(" - ")
                .append(sanitizeFilename(safeDisplayName(displayName)));
        String safePhone = safePhone(phoneNumber);
        if (!safePhone.isEmpty()) {
            name.append(" - ").append(safePhone);
        }
        return name.append(".m4a").toString();
    }

    private static String sanitizeFilename(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", " ").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (clean.isEmpty()) {
            clean = "Zalo contact";
        }
        return clean.length() > 80 ? clean.substring(0, 80).trim() : clean;
    }

    private static String safeDisplayName(String value) {
        return value == null || value.trim().isEmpty() ? "Zalo contact" : value.trim();
    }

    private static String safePhone(String value) {
        if (value == null) {
            return "";
        }
        boolean plus = value.trim().startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return "";
        }
        return plus ? "+" + digits : digits;
    }

    private static String safeDirection(String direction) {
        return "incoming".equals(direction) || "outgoing".equals(direction)
                ? direction : "unknown";
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
