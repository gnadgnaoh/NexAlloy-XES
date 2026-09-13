package io.github.nexalloy.revanced.zalo.calls;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort caller identity captured from Zalo's own call notification.
 *
 * <p>Ported from the Zalo Patch {@code CallRecordingMetadata}. In the Zalo Patch
 * design this was fed by a module notification listener; here it is fed directly
 * in-process from the {@code NotificationManager.notify(...)} hook installed by
 * {@code AutoRecordCallsPatch}. The values are only a fallback: {@link
 * CallRecordingContacts} still resolves the real name/phone from Zalo's local
 * databases when a peer UID is known.
 */
public final class CallRecordingMetadataStore {
    private static final long MAX_AGE_MS = 2L * 60L * 60L * 1000L;
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(\\+?\\d[\\d .()\\-]{6,}\\d)(?!\\d)");
    private static volatile Snapshot latest = Snapshot.empty();

    public static final class Snapshot {
        public final String displayName;
        public final String phoneNumber;
        public final String peerUid;
        public final long observedAt;

        Snapshot(String displayName, String phoneNumber, String peerUid, long observedAt) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
            this.peerUid = peerUid;
            this.observedAt = observedAt;
        }

        static Snapshot empty() {
            return new Snapshot("Zalo contact", "", "", 0L);
        }
    }

    private CallRecordingMetadataStore() {
    }

    public static void observe(Notification notification) {
        if (notification == null || !isCallChannel(channelId(notification))) {
            return;
        }
        Bundle extras = notification.extras;
        String title = value(extras, Notification.EXTRA_TITLE);
        String text = value(extras, Notification.EXTRA_TEXT);
        String subText = value(extras, Notification.EXTRA_SUB_TEXT);
        String bigText = value(extras, Notification.EXTRA_BIG_TEXT);
        String combined = join(title, text, subText, bigText);
        String phone = findPhone(combined);
        String displayName = preferredName(title, text, subText, phone);
        latest = new Snapshot(displayName, phone, findPeerUid(extras), System.currentTimeMillis());
    }

    public static Snapshot current() {
        Snapshot snapshot = latest;
        if (snapshot.observedAt <= 0L
                || System.currentTimeMillis() - snapshot.observedAt > MAX_AGE_MS) {
            return Snapshot.empty();
        }
        return snapshot;
    }

    public static void clear() {
        latest = Snapshot.empty();
    }

    private static String channelId(Notification notification) {
        if (notification == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "";
        }
        return notification.getChannelId();
    }

    private static boolean isCallChannel(String channelId) {
        return channelId != null && (channelId.startsWith("zalo_03_call_channel_")
                || channelId.startsWith("zalo_09_call_channel_"));
    }

    private static String preferredName(
            String title, String text, String subText, String phoneNumber) {
        String[] candidates = new String[]{title, text, subText};
        for (String candidate : candidates) {
            String value = candidate == null ? "" : candidate.trim();
            if (value.isEmpty() || value.equals(phoneNumber) || generic(value)) {
                continue;
            }
            Matcher matcher = PHONE.matcher(value);
            value = matcher.replaceAll("").replaceAll("^[\\s:–—-]+|[\\s:–—-]+$", "").trim();
            if (!value.isEmpty() && !generic(value)) {
                return value;
            }
        }
        return "Zalo contact";
    }

    private static boolean generic(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return "zalo".equals(lower)
                || lower.contains("incoming call")
                || lower.contains("outgoing call")
                || lower.contains("ongoing call")
                || lower.contains("cuộc gọi đến")
                || lower.contains("cuộc gọi đi")
                || lower.contains("đang gọi");
    }

    private static String findPhone(String value) {
        Matcher matcher = PHONE.matcher(value == null ? "" : value);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1).trim();
        boolean plus = raw.startsWith("+");
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return "";
        }
        return plus ? "+" + digits : digits;
    }

    private static String findPeerUid(Bundle extras) {
        if (extras == null) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Object callPerson = extras.get("android.callPerson");
            String uid = personUid(callPerson);
            if (!uid.isEmpty()) {
                return uid;
            }
            java.util.ArrayList<?> people = extras.getParcelableArrayList(
                    Notification.EXTRA_PEOPLE_LIST);
            if (people != null) {
                for (Object person : people) {
                    uid = personUid(person);
                    if (!uid.isEmpty()) {
                        return uid;
                    }
                }
            }
        }
        for (String key : extras.keySet()) {
            String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("uid") && !lower.contains("user_id")
                    && !lower.contains("zalo_id")) {
                continue;
            }
            String uid = numericUid(extras.get(key));
            if (!uid.isEmpty()) {
                return uid;
            }
        }
        return "";
    }

    private static String personUid(Object value) {
        if (value == null || !"android.app.Person".equals(value.getClass().getName())) {
            return "";
        }
        try {
            String uid = numericUid(value.getClass().getMethod("getKey").invoke(value));
            if (!uid.isEmpty()) {
                return uid;
            }
            return numericUid(value.getClass().getMethod("getUri").invoke(value));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String numericUid(Object value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{5,20})(?!\\d)")
                .matcher(String.valueOf(value));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String value(Bundle extras, String key) {
        if (extras == null) {
            return "";
        }
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
