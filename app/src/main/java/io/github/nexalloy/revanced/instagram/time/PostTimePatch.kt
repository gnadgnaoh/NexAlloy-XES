package io.github.nexalloy.revanced.instagram.time

import android.content.Context
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val ShowPostTime = patch(
    name = "Show a post's exact time",
    description = "Shows the date and time a post went up, like Oct 2, 3:45 PM, instead of how long ago. It does the same for comments.",
) {
    val formatters = ::relativeTimeFormatterMethodsFingerprint.dexMethodList.map { it.toMethod() }
    check(formatters.isNotEmpty()) { "no relative time formatter" }
    val rowTime = ::commentRowTimeFieldFingerprint.field
    val rowClass = rowTime.declaringClass
    val inside = ThreadLocal.withInitial { 0 }

    fun scope(method: java.lang.reflect.Method, applies: (Any?) -> Boolean = { true }) = method.hookMethod {
        before { param -> if (applies(param.thisObject)) inside.set(inside.get() + 1) else param.setObjectExtra("na_out", true) }
        after { param ->
            if (param.getObjectExtra("na_out") == true) return@after
            val now = inside.get() - 1
            if (now <= 0) inside.remove() else inside.set(now)
        }
    }

    scope(::feedFooterBuilderFingerprint.method)
    scope(::commentHeaderFingerprint.method)
    ::commentRowTimeReadersFingerprint.dexMethodList.map { it.toMethod() }.forEach { reader ->
        scope(reader) { self -> self == null || holds(self, rowClass) }
    }

    formatters.forEach { f ->
        f.hookMethod {
            after { param ->
                if (inside.get() <= 0 || param.result !is String) return@after
                val ctx = param.args[0] as? Context ?: return@after
                val seconds = (param.args[1] as? Number)?.toDouble() ?: return@after
                if (seconds.isNaN() || seconds <= 0 || seconds > Long.MAX_VALUE / 1000.0) return@after
                param.result = TimeFormat.exact(ctx, seconds.toLong() * 1000)
            }
        }
    }
}

private fun holds(self: Any, type: Class<*>): Boolean {
    var c: Class<*>? = self.javaClass
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
            if (runCatching { f.isAccessible = true; type.isInstance(f.get(self)) }.getOrDefault(false)) return true
        }
        c = c.superclass
    }
    return false
}
