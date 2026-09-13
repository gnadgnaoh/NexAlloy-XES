package io.github.nexalloy.morphe.tiktok.captcha

import java.util.Locale

internal object CaptchaPolicy {
    const val ACCOUNT_ACTIVITY_PREFIX = "com.ss.android.ugc.aweme.account."

    private val PUZZLE_SUBTYPES = setOf("slide", "whirl", "3d", "text", "smarter", "seal")

    private val ACCOUNT_SUBTYPES = setOf(
        "sms", "mobile_sms_verify", "mobile_up_sms_verify", "mobile_voice_sms_verify", "voice",
        "email_verify", "pwd_verify", "qa", "identify", "idv", "login",
    )

    private val SUBTYPE_PATTERN = Regex("\"subtype\"\\s*:\\s*\"([^\"]*)\"")

    fun isAccountRoute(value: String?): Boolean {
        val normalized = value?.lowercase(Locale.ROOT) ?: return false
        return normalized == "login" ||
            normalized == "passport" ||
            normalized.contains("/passport/") ||
            normalized.contains("/login/") ||
            normalized.contains("\"passport\"") ||
            normalized.contains("\"login\"")
    }

    /** Null when the payload carries no subtype (classic image captcha). */
    fun subtypeOf(riskInfo: String?): String? =
        riskInfo?.let { SUBTYPE_PATTERN.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotEmpty() }

    fun isPuzzle(subtype: String?): Boolean =
        subtype == null || subtype.lowercase(Locale.ROOT) in PUZZLE_SUBTYPES

    /**
     * @param activityClassName class name of the activity the dialog would be shown on, if known.
     * @param activityRoute the activity's intent data, if known.
     * @param riskInfo the verification payload, if known.
     */
    fun shouldHide(activityClassName: String?, activityRoute: String?, riskInfo: String?): Boolean {
        if (activityClassName != null && activityClassName.startsWith(ACCOUNT_ACTIVITY_PREFIX)) return false
        if (isAccountRoute(activityRoute) || isAccountRoute(riskInfo)) return false
        return isPuzzle(subtypeOf(riskInfo))
    }

    /**
     * Same decision for the TikTok Shop verification SDK, whose request object carries the subtype
     * as a field instead of a payload string. Nothing is hidden unless a puzzle subtype is present,
     * because that SDK also drives SMS, e-mail and password verification.
     *
     * @param values every string the request object holds.
     */
    fun shouldHideVerifyRequest(
        activityClassName: String?,
        activityRoute: String?,
        values: Collection<String>,
    ): Boolean {
        if (activityClassName != null && activityClassName.startsWith(ACCOUNT_ACTIVITY_PREFIX)) return false
        if (isAccountRoute(activityRoute)) return false
        val normalized = values.map { it.lowercase(Locale.ROOT) }
        if (normalized.any { it in ACCOUNT_SUBTYPES || isAccountRoute(it) }) return false
        return normalized.any { it in PUZZLE_SUBTYPES }
    }
}
