package io.github.nexalloy.revanced.threads.network

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

internal const val LOGGING_URL = "https://graph.facebook.com/logging_client_events"

val pigeonUrlFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.String"
            paramTypes("java.lang.String", "boolean")
            usingStrings("/pigeon_nest", "/logging_client_events")
        }
    }.single { Modifier.isStatic(it.modifiers) }
}

val loggingUrlProvidersFingerprint = findMethodListDirect {
    findMethod {
        matcher { paramCount = 0; usingEqStrings(LOGGING_URL) }
    }.filter { it.isMethod && (it.returnTypeName == "java.lang.Object" || it.returnTypeName == "java.lang.String") }
        .also { check(it.isNotEmpty()) }
}

val mqttSettingsFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            paramTypes("org.json.JSONObject")
            usingEqStrings("analytics_endpoint", LOGGING_URL)
        }
    }.single()
}
