package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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

private const val METRIC_EVENT = "me.proton.core.observability.data.api.request.MetricEvent"
private const val PAYMENT_FFI = "me.proton.android.payment.core.PaymentFfi"

private fun <T> List<T>.only(what: String, describe: (T) -> String): T =
    singleOrNull() ?: throw Exception("Expected one $what, found $size: ${joinToString { describe(it) }}")

internal val vpnTelemetryEventFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.PRIVATE or Modifier.FINAL
            returnType = "java.lang.Object"
            paramTypes(TELEMETRY_EVENT, "boolean", null)
        }
    }.only("VpnTelemetry event method") { it.descriptor }
}

val coreTelemetryUploadSchedulerFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(USER_ID, "long")
            addUsingField(FieldMatcher().declaredClass(TELEMETRY_WORKER), UsingType.Read)
        }
    }.atLeastOne("core telemetry upload scheduler") { it.descriptor }
}

internal val observabilityUploadFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.Object"
            paramTypes("java.util.List", null)
            addUsingField(FieldMatcher().declaredClass(METRIC_EVENT), UsingType.Read)
        }
    }.only("observability upload") { it.descriptor }
}

internal val observabilityEnabledFingerprint = findMethodDirect {
    val dependencyTypes = findMethod {
        matcher {
            name = "<init>"
            usingStrings(listOf("isObservabilityEnabled"), StringMatchType.Equals)
        }
    }.flatMap { it.paramTypeNames }.toSet()
    dependencyTypes.flatMap { type ->
        findClass { matcher { addInterface(type) } }.findMethod {
            matcher {
                returnType = "java.lang.Object"
                paramCount = 1
                addInvoke("Landroid/content/res/Resources;->getBoolean(I)Z")
            }
        }
    }.distinctBy { it.descriptor }.only("IsObservabilityEnabled implementation") { it.descriptor }
}

internal val paymentsObservabilityWorkerFingerprint = findMethodDirect {
    findClass { matcher { addFieldForType(PAYMENT_FFI) } }.findMethod {
        matcher {
            name = "start"
            returnType = "void"
            paramCount = 0
        }
    }.only("payments observability worker start()") { it.descriptor }
}
