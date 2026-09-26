package io.github.nexalloy.revanced.zalo.telemetry

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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

internal const val ANALYTICS_DATABASE_CLASS = "com.zing.zalo.analytics.db.AnalyticsRoomDatabase"

private val ANALYTICS_INSERT_SQL = listOf(
    "INSERT OR ABORT INTO `events` (",
    "INSERT OR ABORT INTO `screens` (",
    "INSERT OR ABORT INTO `sessions` (",
    "INSERT OR ABORT INTO `views` (",
)

val roomEntityAdapterBaseFingerprint = findClassDirect {
    val adapters = ANALYTICS_INSERT_SQL.flatMap { sql ->
        findClass { matcher { addUsingString(sql, StringMatchType.StartsWith) } }
    }
    check(adapters.isNotEmpty()) { "no analytics insertion adapter" }
    adapters.map { it.superClass ?: error("${it.name} has no superclass") }
        .distinctBy { it.name }
        .single()
}

val roomEntityInsertMethods = findMethodListDirect {
    roomEntityAdapterBaseFingerprint().methods.filter {
        it.isMethod && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") &&
            it.returnTypeName in setOf("long", "void")
    }.also { check(it.size == 2) { "expected 2 Room insert methods, found ${it.size}" } }
}

val roomStatementDatabaseField = findFieldDirect {
    val statement = roomEntityAdapterBaseFingerprint().superClass
        ?: error("Room adapter base has no superclass")
    val roomDatabase = findClass { matcher { className = ANALYTICS_DATABASE_CLASS } }.single()
        .superClass ?: error("$ANALYTICS_DATABASE_CLASS has no superclass")
    statement.fields.single { it.typeName == roomDatabase.name }
}

val firebaseAnalyticsDeactivatedFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("firebase_analytics_collection_deactivated")
            paramCount = 0
            returnType = "boolean"
        }
    }.single()
}
