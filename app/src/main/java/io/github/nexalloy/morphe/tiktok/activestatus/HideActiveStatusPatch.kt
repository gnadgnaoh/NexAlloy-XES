package io.github.nexalloy.morphe.tiktok.activestatus

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val TAG = "[TikTok active status]"

val HideActiveStatus = patch(
    name = "Hide your active status",
    description = "Stops TikTok from telling others you are online while you still see the activity status " +
        "of your friends. Keep Activity status set to Friends or Everyone in TikTok.",
) {
    val report = ::activityStatusReportFingerprint.method
    val fetch = ::activityStatusFetchFingerprint.method
    val api = report.declaringClass
    val flagAt = fetch.parameterAnnotations.indexOfFirst { list -> list.any { FETCH_AS_REPORT in it.toString() } }
    check(flagAt >= 0) { "$FETCH_AS_REPORT parameter not found" }
    val holders = ::activityStatusApiHoldersFingerprint.fieldList.filter { !Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }
    check(holders.isNotEmpty()) { "no instance holder of ${api.name}" }

    fun wrap(original: Any): Any = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, args ->
        when {
            method == report -> null
            method == fetch -> {
                val call = args?.copyOf() ?: emptyArray()
                if (flagAt < call.size) call[flagAt] = 0
                invoke(method, original, call)
            }
            else -> invoke(method, original, args ?: emptyArray())
        }
    }

    holders.groupBy { it.declaringClass }.forEach { (owner, fields) ->
        owner.declaredConstructors.forEach { ctor ->
            ctor.hookMethod {
                after { param ->
                    fields.forEach { field ->
                        val current = field.get(param.thisObject) ?: return@forEach
                        runCatching { field.set(param.thisObject, wrap(current)) }
                            .onFailure { Logger.printException({ "$TAG wrap ${field.name} failed" }, it) }
                    }
                }
            }
        }
    }
    Logger.printDebug { "$TAG api=${api.name} holders=${holders.map { "${it.declaringClass.name}.${it.name}" }} flag=$flagAt" }
}

private fun invoke(method: java.lang.reflect.Method, target: Any, args: Array<Any?>): Any? = try {
    method.invoke(target, *args)
} catch (e: InvocationTargetException) {
    throw e.targetException
}
