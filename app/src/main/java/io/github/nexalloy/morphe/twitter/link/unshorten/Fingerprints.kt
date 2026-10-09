package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.twitter.utils.X_MODELS_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.X_NAVIGATION_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.dataClassToString

internal object UrlEntityToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("UrlEntity", X_MODELS_PACKAGE) },
)

internal object OpenExternalUrlFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z"),
    strings = listOf(
        "ExternalScreenNav",
        "Unable to start Intent",
        "No activity found for Intent",
    ),
)

internal object OpenExternalBrowserFingerprint : Fingerprint(
    classFingerprint = OpenExternalUrlFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("SecurityException opening external app"),
)

internal object OpenUrlInAppFingerprint : Fingerprint(
    classFingerprint = OpenExternalUrlFingerprint,
    returnType = "V",
    strings = listOf("com.twitter.android.debug"),
)

internal object LinkWithPostDetailArgsToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("LinkWithPostDetailArgs", X_NAVIGATION_PACKAGE) },
)

internal object WebViewArgsToStringFingerprint : Fingerprint(
    name = "toString",
    custom = { dataClassToString("WebViewArgs", X_NAVIGATION_PACKAGE) },
)
