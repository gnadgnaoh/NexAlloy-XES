package io.github.nexalloy.revanced.zalo.telemetry

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

private const val CRASHLYTICS_CLASS = "com.google.firebase.crashlytics.FirebaseCrashlytics"

private val RECORDING_METHODS = setOf(
    "log", "recordException", "setCustomKey", "setCustomKeys", "setUserId", "sendUnsentReports",
)

private fun DexKitBridge.crashlyticsMethods(): List<MethodData> =
    findClass { matcher { className = CRASHLYTICS_CLASS } }.single().methods

val crashlyticsRecordingMethods = findMethodListDirect {
    crashlyticsMethods().filter { it.isMethod && it.name in RECORDING_METHODS && it.returnTypeName == "void" }
}

val crashlyticsCollectionSwitchMethods = findMethodListDirect {
    crashlyticsMethods().filter { it.isMethod && it.name == "setCrashlyticsCollectionEnabled" }
}

val crashlyticsGetInstanceFingerprint = findMethodDirect {
    crashlyticsMethods().single { it.isMethod && it.name == "getInstance" && it.paramTypeNames.isEmpty() }
}
