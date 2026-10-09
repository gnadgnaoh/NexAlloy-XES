package io.github.nexalloy.revanced.zalo.telemetry

import android.content.ContextWrapper
import android.content.Intent
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method

private const val MEASUREMENT_START_ACTION = "com.google.android.gms.measurement.START"
private val MEASUREMENT_SERVICES = setOf(
    "com.google.android.gms.measurement.service.MeasurementBrokerService",
    "com.google.android.gms.measurement.AppMeasurementService",
)

val DisableFirebaseAnalytics = patch(
    name = "Disable Firebase Analytics",
    description = "Turns off Google/Firebase Analytics (measurement) inside Zalo and blocks " +
        "its connection to the Google Play services measurement service. Push " +
        "notifications are unaffected.",
) {
    ::firebaseAnalyticsDeactivatedFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    val contextImpl = runCatching { Class.forName("android.app.ContextImpl") }.getOrNull()
    listOfNotNull(contextImpl, ContextWrapper::class.java).forEach { owner ->
        runCatching { owner.declaredMethods }.getOrDefault(emptyArray())
            .filter { it.name.contains("Service") && it.parameterTypes.any(Intent::class.java::isAssignableFrom) }
            .forEach { method ->
                method.hookMethod {
                    before { param ->
                        val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return@before
                        if (!intent.isMeasurementService()) return@before
                        param.result = fallbackFor(method)
                    }
                }
            }
    }
}

private fun Intent.isMeasurementService(): Boolean =
    action == MEASUREMENT_START_ACTION || component?.className in MEASUREMENT_SERVICES

private fun fallbackFor(method: Method): Any? = when (method.returnType) {
    java.lang.Boolean.TYPE -> false
    Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    else -> null // void, ComponentName (startService)
}

