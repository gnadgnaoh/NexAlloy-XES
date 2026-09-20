package io.github.nexalloy.revanced.zalo.calls

import android.app.Notification
import android.os.Build
import android.os.Bundle
import java.util.Locale
import java.util.regex.Pattern

/**
 * Best-effort caller identity captured from Zalo's own call notification.
 *
 * Ported from the Zalo Patch `CallRecordingMetadata`. In the Zalo Patch
 * design this was fed by a module notification listener; here it is fed directly
 * in-process from the `NotificationManager.notify(...)` hook installed by
 * `AutoRecordCallsPatch`. The values are only a fallback: [CallRecordingContacts]
 * still resolves the real name/phone from Zalo's local databases when a peer UID
 * is known.
 */
object CallRecordingMetadataStore {

    private const val MAX_AGE_MS = 2L * 60L * 60L * 1000L
    private val PHONE: Pattern = Pattern.compile(
        "(?<!\\d)(\\+?\\d[\\d .()\\-]{6,}\\d)(?!\\d)"
    )

    @Volatile
    private var latest: Snapshot = Snapshot.empty()

    class Snapshot(
        @JvmField val displayName: String,
        @JvmField val phoneNumber: String,
        @JvmField val peerUid: String,
        @JvmField val observedAt: Long,
    ) {
        companion object {
            fun empty(): Snapshot = Snapshot("Zalo contact", "", "", 0L)
        }
    }

    fun observe(notification: Notification?) {
        if (notification == null || !isCallChannel(channelId(notification))) return
        val extras: Bundle? = notification.extras
        val title = value(extras, Notification.EXTRA_TITLE)
        val text = value(extras, Notification.EXTRA_TEXT)
        val subText = value(extras, Notification.EXTRA_SUB_TEXT)
        val bigText = value(extras, Notification.EXTRA_BIG_TEXT)
        val combined = join(title, text, subText, bigText)
        val phone = findPhone(combined)
        val displayName = preferredName(title, text, subText, phone)
        latest = Snapshot(displayName, phone, findPeerUid(extras), System.currentTimeMillis())
    }

    fun current(): Snapshot {
        val snapshot = latest
        if (snapshot.observedAt <= 0L ||
            System.currentTimeMillis() - snapshot.observedAt > MAX_AGE_MS
        ) {
            return Snapshot.empty()
        }
        return snapshot
    }

    fun clear() {
        latest = Snapshot.empty()
    }

    private fun channelId(notification: Notification?): String {
        if (notification == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        return notification.channelId ?: ""
    }

    private fun isCallChannel(channelId: String?): Boolean =
        channelId != null && (
            channelId.startsWith("zalo_03_call_channel_") ||
                channelId.startsWith("zalo_09_call_channel_")
            )

    private fun preferredName(
        title: String?,
        text: String?,
        subText: String?,
        phoneNumber: String,
    ): String {
        val candidates = arrayOf(title, text, subText)
        for (candidate in candidates) {
            var value = candidate?.trim() ?: ""
            if (value.isEmpty() || value == phoneNumber || generic(value)) continue
            val matcher = PHONE.matcher(value)
            value = matcher.replaceAll("")
                .replace(Regex("^[\\s:\u2013\u2014-]+|[\\s:\u2013\u2014-]+$"), "")
                .trim()
            if (value.isNotEmpty() && !generic(value)) return value
        }
        return "Zalo contact"
    }

    private fun generic(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower == "zalo" ||
            lower.contains("incoming call") ||
            lower.contains("outgoing call") ||
            lower.contains("ongoing call") ||
            lower.contains("cuộc gọi đến") ||
            lower.contains("cuộc gọi đi") ||
            lower.contains("đang gọi")
    }

    private fun findPhone(value: String?): String {
        val matcher = PHONE.matcher(value ?: "")
        if (!matcher.find()) return ""
        val raw = matcher.group(1)?.trim() ?: return ""
        val plus = raw.startsWith("+")
        val digits = raw.replace(Regex("\\D"), "")
        if (digits.length < 8 || digits.length > 15) return ""
        return if (plus) "+$digits" else digits
    }

    private fun findPeerUid(extras: Bundle?): String {
        if (extras == null) return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val callPerson = extras.get("android.callPerson")
            var uid = personUid(callPerson)
            if (uid.isNotEmpty()) return uid
            val people = extras.getParcelableArrayList<android.os.Parcelable>(
                Notification.EXTRA_PEOPLE_LIST
            )
            if (people != null) {
                for (person in people) {
                    uid = personUid(person)
                    if (uid.isNotEmpty()) return uid
                }
            }
        }
        for (key in extras.keySet()) {
            val lower = key?.lowercase(Locale.ROOT) ?: ""
            if (!lower.contains("uid") && !lower.contains("user_id") &&
                !lower.contains("zalo_id")
            ) continue
            val uid = numericUid(extras.get(key))
            if (uid.isNotEmpty()) return uid
        }
        return ""
    }

    private fun personUid(value: Any?): String {
        if (value == null || value.javaClass.name != "android.app.Person") return ""
        return try {
            val uid = numericUid(value.javaClass.getMethod("getKey").invoke(value))
            if (uid.isNotEmpty()) uid
            else numericUid(value.javaClass.getMethod("getUri").invoke(value))
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun numericUid(value: Any?): String {
        if (value == null) return ""
        val matcher = Pattern.compile("(?<!\\d)(\\d{5,20})(?!\\d)")
            .matcher(value.toString())
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    private fun value(extras: Bundle?, key: String): String {
        if (extras == null) return ""
        val value = extras.getCharSequence(key)
        return value?.toString() ?: ""
    }

    private fun join(vararg values: String?): String {
        val builder = StringBuilder()
        for (value in values) {
            if (value.isNullOrEmpty()) continue
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(value)
        }
        return builder.toString()
    }
}
