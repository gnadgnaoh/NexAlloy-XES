package io.github.nexalloy.revanced.zalo.telemetry

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch
import java.util.concurrent.atomic.AtomicBoolean

val DisableCrashlytics = patch(
    name = "Disable Crashlytics crash reporting",
    description = "Stops Firebase Crashlytics from collecting and uploading Zalo crash " +
        "reports, logs, custom keys and your user ID. Zalo itself is unaffected.",
) {
    ::crashlyticsRecordingMethods.dexMethodList.forEach { method ->
        method.hookMethod(XC_MethodReplacement.DO_NOTHING)
    }

    ::crashlyticsCollectionSwitchMethods.dexMethodList.forEach { method ->
        method.hookMethod {
            before { param ->
                val arg = param.args[0]
                param.args[0] = if (arg is Boolean || arg == null) false else arg
            }
        }
    }

    val disabledOnce = AtomicBoolean(false)
    ::crashlyticsGetInstanceFingerprint.hookMethod {
        after { param ->
            val instance = param.result ?: return@after
            if (!disabledOnce.compareAndSet(false, true)) return@after
            runCatching {
                instance.javaClass.getMethod(
                    "setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType
                ).invoke(instance, false)
                instance.javaClass.getMethod("deleteUnsentReports").invoke(instance)
            }
        }
    }
}
