package io.github.nexalloy.revanced.shared.tigon

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Returns true when a request to [host] + [path] must not reach the server. [host] may be empty. */
internal fun interface TigonRequestRule {
    fun shouldBlock(host: String, path: String): Boolean
}

private class NamedRule(val name: String, val rule: TigonRequestRule)

private val rules = CopyOnWriteArrayList<NamedRule>()

/**
 * Registers [rule] for [TigonRequestHook]. A patch calls this and then
 * `dependsOn(TigonRequestHook)`; rules are read on every request, so order does not matter.
 */
internal fun addTigonRequestRule(name: String, rule: TigonRequestRule) {
    synchronized(rules) {
        if (rules.none { it.name == name }) rules += NamedRule(name, rule)
    }
}

/**
 * Declared name of the request URI field to try before searching by type. Set by an app whose
 * request class is known to keep the URI under an obfuscated name (Threads: `A08`); otherwise
 * the first `URI` field in the class hierarchy is used.
 */
@Volatile
internal var tigonPreferredUriField: String? = null

private val NO_FIELD = Any()
private val uriFields = ConcurrentHashMap<Class<*>, Any>()

private fun uriFieldOf(requestClass: Class<*>): Field? =
    uriFields.getOrPut(requestClass) {
        val preferred = tigonPreferredUriField?.let { name ->
            runCatching { requestClass.getDeclaredField(name) }.getOrNull()
        }
        (preferred ?: generateSequence(requestClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.type == URI::class.java })
            ?.apply { isAccessible = true }
            ?: NO_FIELD
    } as? Field

private val BLOCKED_URI = URI("https", "0.0.0.0", "/0", null)

/**
 * The single hook on TigonServiceLayer.startRequest behind every request-blocking patch. A
 * blocked request is pointed at 0.0.0.0/0, so it fails like a dropped connection.
 */
internal val TigonRequestHook = patch(name = "<TigonRequestHook>") {
    TigonServiceLayerStartRequestFingerprint.hookMethod {
        before { param ->
            if (rules.isEmpty()) return@before
            val request = param.args.firstOrNull() ?: return@before
            val uriField = uriFieldOf(request.javaClass) ?: return@before
            val uri = uriField.get(request) as? URI ?: return@before
            val path = uri.path ?: return@before
            val host = uri.host.orEmpty()

            val matched = rules.firstOrNull { it.rule.shouldBlock(host, path) } ?: return@before
            uriField.set(request, BLOCKED_URI)
            Logger.printDebug { "[Tigon] ${matched.name} blocked: $host$path" }
        }
    }
}
