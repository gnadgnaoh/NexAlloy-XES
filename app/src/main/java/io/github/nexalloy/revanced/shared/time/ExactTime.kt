package io.github.nexalloy.revanced.shared.time

import android.content.Context
import io.github.nexalloy.hookMethod
import java.lang.reflect.Method

internal fun exactFromSeconds(ctx: Context, seconds: Any?): String? {
    val value = (seconds as? Number)?.toDouble() ?: return null
    if (value.isNaN() || value <= 0 || value > Long.MAX_VALUE / 1000.0) return null
    return TimeFormat.exact(ctx, value.toLong() * 1000)
}

internal fun hookExactSeconds(method: Method, applies: () -> Boolean = { true }) {
    val ctxAt = method.parameterTypes.indexOfFirst { Context::class.java.isAssignableFrom(it) }
    val secondsAt = method.parameterTypes.indexOfFirst { it == Double::class.javaPrimitiveType || it == Long::class.javaPrimitiveType }
    check(ctxAt >= 0 && secondsAt >= 0) { "${method.name} takes no Context and time" }
    method.hookMethod {
        after { param ->
            if (param.result !is String || !applies()) return@after
            val ctx = param.args[ctxAt] as? Context ?: return@after
            exactFromSeconds(ctx, param.args[secondsAt])?.let { param.result = it }
        }
    }
}
