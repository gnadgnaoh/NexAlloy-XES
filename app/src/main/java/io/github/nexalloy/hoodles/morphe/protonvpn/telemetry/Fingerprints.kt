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

private const val METRIC_EVENT = "me.proton.core.observability.data.api.request.MetricEvent"
private const val PAYMENT_FFI = "me.proton.android.payment.core.PaymentFfi"

private fun <T> List<T>.only(what: String, describe: (T) -> String): T =
    singleOrNull() ?: throw Exception("Expected one $what, found $size: ${joinToString { describe(it) }}")

private fun <T> firstOf(what: String, vararg strategies: () -> T): T {
    val errors = mutableListOf<String>()
    for (strategy in strategies) {
        try {
            return strategy()
        } catch (e: Exception) {
            errors += e.message ?: e.toString()
        }
    }
    throw Exception("$what: all ${strategies.size} strategies failed: ${errors.joinToString(" | ")}")
}

internal val vpnTelemetryEventFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.Object"
            paramTypes(null, TELEMETRY_EVENT, "boolean", null)
        }
    }.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.first() == it.className }
        .only("VpnTelemetry event method") { it.descriptor }
}

internal val coreTelemetryEnqueueFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(listOf("core.telemetry.enqueue"), StringMatchType.Equals)
            returnType = "java.lang.Object"
        }
    }.filter { it.paramCount == 4 && it.paramTypeNames.first() == USER_ID }
        .only("core telemetry enqueue") { it.descriptor }
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
