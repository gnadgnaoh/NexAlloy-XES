package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.morphe.Fingerprint

/**
 * com.x.models.text.UrlEntity (12.24.0: Lcom/x/models/text/m1;)
 *
 * data class UrlEntity(displayUrl, expandedUrl, url, startIdx, endIdx)
 * The three String fields always appear in the constructors in that order,
 * so the argument indices are derived from the constructor shape instead of
 * being hard coded (see NoShortenedUrlPatch).
 */
internal object UrlEntityToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("UrlEntity(displayUrl="),
)

/**
 * ExternalScreenNav.openUrl(url, showError): Boolean
 *
 * 12.24.0: Lcom/x/navigation/p4;->b(Ljava/lang/String;Z)Z
 * Used to be a single String parameter; R8 now splits it into
 * a(String) -> b(String, boolean), and only b carries the log strings.
 */
internal object OpenExternalUrlFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z"),
    strings = listOf(
        "ExternalScreenNav",
        "Unable to start Intent",
        "No activity found for Intent",
    ),
)

/**
 * ExternalScreenNav.openInExternalBrowser(url): Boolean
 *
 * 12.24.0: Lcom/x/navigation/p4;->g(Ljava/lang/String;)Z
 * Called directly by the media player, the video tab and timeline items,
 * so it does not go through [OpenExternalUrlFingerprint].
 */
internal object OpenExternalBrowserFingerprint : Fingerprint(
    classFingerprint = OpenExternalUrlFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("SecurityException opening external app"),
)

/**
 * ExternalScreenNav.openUrlInApp(nav, url): Unit
 *
 * 12.24.0: static Lcom/x/navigation/p4;->d(Lcom/x/navigation/p4;Ljava/lang/String;)V
 * Entry point used by link post detail and by composer link taps.
 */
internal object OpenUrlInAppFingerprint : Fingerprint(
    classFingerprint = OpenExternalUrlFingerprint,
    returnType = "V",
    strings = listOf("com.twitter.android.debug"),
)

internal object LinkWithPostDetailArgsToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("LinkWithPostDetailArgs(url="),
)

internal object WebViewArgsToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("WebViewArgs(url="),
)
