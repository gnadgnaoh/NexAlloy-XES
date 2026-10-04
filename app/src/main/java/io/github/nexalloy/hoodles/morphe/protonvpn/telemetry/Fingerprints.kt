package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

private const val TELEMETRY_WORKER = "me.proton.core.telemetry.data.worker.TelemetryWorker"
private const val DATA_METRICS_REQUEST = "me.proton.core.observability.data.api.request.DataMetricsRequest"
private const val TELEMETRY_EVENT = "com.protonvpn.android.telemetry.TelemetryEvent"
private const val USER_ID = "me.proton.core.domain.entity.UserId"

private fun <T> List<T>.atLeastOne(what: String, describe: (T) -> String): List<T> =
    ifEmpty { throw Exception("No $what found") }.also {
        if (it.size > 4) throw Exception("Too many $what (${it.size}): ${it.joinToString { d -> describe(d) }}")
    }

val telemetryWorkerEnqueueFingerprints = findMethodListDirect {
    val requestBuilders = findClass {
        matcher { className(TELEMETRY_WORKER + "$", StringMatchType.StartsWith) }
    }.flatMap { it.methods }
        .filter { it.paramTypeNames.firstOrNull() == USER_ID && it.returnTypeName != "void" }

    requestBuilders.flatMap { it.callers }
        .filter { it.returnTypeName == "void" && !it.className.startsWith(TELEMETRY_WORKER) }
        .filter { it.paramTypeNames.firstOrNull() == USER_ID }
        .distinctBy { it.descriptor }
        .atLeastOne("TelemetryWorker enqueue method") { it.descriptor }
}

internal val sendObservabilityFingerprint = findMethodDirect {
    val producers: List<MethodData> = findMethod {
        matcher { addInvoke { declaredClass(DATA_METRICS_REQUEST); name = "<init>" } }
    }.filter { !it.className.startsWith(DATA_METRICS_REQUEST) }

    val senders = producers.flatMap { producer ->
        val outer = producer.className.substringBefore('$')
        val inOuter = findMethod {
            matcher {
                declaredClass(outer)
                returnType = "java.lang.Object"
                paramCount = 2
            }
        }.filter { it.paramTypeNames.firstOrNull() == "java.util.List" }
        if (producer.paramTypeNames.firstOrNull() == "java.util.List") inOuter + producer else inOuter
    }.distinctBy { it.descriptor }

    senders.singleOrNull() ?: throw Exception(
        "Expected one observability sender, found ${senders.size}: ${senders.joinToString { it.descriptor }}",
    )
}

internal val vpnTelemetryAddEventFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(listOf("event added, total: "), StringMatchType.Equals)
            returnType = "java.lang.Object"
        }
    }.filter { it.paramTypeNames.firstOrNull() == TELEMETRY_EVENT }
        .let { it.singleOrNull() ?: throw Exception("Expected one VpnTelemetry.addEvent, found ${it.size}") }
}
