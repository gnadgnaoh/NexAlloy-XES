package io.github.nexalloy.revanced.facebook.ad

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookVoidMethodsNoOp

private val AD_TELEMETRY_CLASSES = listOf(
    "com.facebook.ads.screenshot.AdsScreenshotController",
    "com.facebook.ads.AdsScreenshotDetector",
    "com.facebook.feed.platformads.AppInstallTrackerScheduler",
    "com.facebook.feed.platformads.AppInstallService",
    "com.facebook.attribution.LatStatusJob",
    "com.facebook.privacysandbox.PrivacySandboxCapabilitiesChecker",
    "com.facebook.privacysandbox.protectedaudience.TestPACustomAudienceAppJob",
)

private const val ATTRIBUTION_PROVIDER = "com.facebook.katana.provider.AttributionIdProvider\$Impl"

val BlockAdTelemetry = patch(
    name = "Block ad telemetry",
    description = "Stops Facebook watching for screenshots of ads, reporting apps you install after seeing ads, " +
        "and several ways it sends your advertising ID.",
) {
    ::sponsoredPoolAddMethodFingerprint.method

    val missing = AD_TELEMETRY_CLASSES.filter { it.findClassOrNull(classLoader) == null }.toMutableList()
    check(missing.size < AD_TELEMETRY_CLASSES.size) { "no ad telemetry class found" }

    AD_TELEMETRY_CLASSES.forEach { name -> name.findClassOrNull(classLoader)?.let { hookVoidMethodsNoOp(it) } }

    ::adIdSendersFingerprint.dexMethodList.forEach { dm ->
        dm.hookMethod { before { param -> param.result = null } }
    }

    ATTRIBUTION_PROVIDER.findClassOrNull(classLoader)?.declaredMethods
        ?.singleOrNull { it.returnType == Cursor::class.java && it.parameterCount == 5 && it.parameterTypes[0] == Uri::class.java }
        ?.hookMethod {
            before { param ->
                param.result = MatrixCursor(arrayOf("aid", "androidid", "limit_tracking")).apply {
                    addRow(arrayOf<Any?>(null, null, null))
                }
            }
        }
        ?: missing.add(ATTRIBUTION_PROVIDER)

    if (missing.isNotEmpty()) Logger.printInfo { "Facebook: ad telemetry entry points not present: ${missing.joinToString()}" }
}
