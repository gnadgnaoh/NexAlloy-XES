package io.github.nexalloy.revanced.shared.presence

import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import org.json.JSONObject

private const val PRESENCE_WRITE_REQUEST = "com.facebook.presence.model.upi.PresenceWriteRequest"
private const val PRESENCE_STATUS = "com.facebook.presence.model.upi.PresenceStatus"
private const val APP_STATE = "com.facebook.presence.model.upi.AppState"
private const val CONNECTION_CONFIG = "com.facebook.mqtt.service.ConnectionConfig"
private const val CHAT_ON_FIELD = "chatOnEnabled"
private const val CHAT_ON_GETTER = "getChatOnEnabled"

private fun PatchExecutor.metaClass(name: String): Class<*> = name.findClassOrNull(classLoader) ?: error("$name not found")

private fun Class<*>.constant(name: String): Any =
    enumConstants.orEmpty().singleOrNull { (it as Enum<*>).name == name } ?: error("no $name in ${this.name}")

internal fun PatchExecutor.hideUpiPresence() {
    val request = metaClass(PRESENCE_WRITE_REQUEST)
    val status = metaClass(PRESENCE_STATUS)
    val appState = metaClass(APP_STATE)
    val active = status.constant("ACTIVE")
    val idle = status.constant("IDLE")
    val foreground = appState.constant("FOREGROUND")
    val background = appState.constant("BACKGROUND")
    val constructors = request.declaredConstructors.filter { status in it.parameterTypes }
    check(constructors.isNotEmpty()) { "no PresenceWriteRequest constructor takes a status" }
    constructors.forEach { c ->
        val at = c.parameterTypes.indexOf(status)
        val stateAt = c.parameterTypes.indexOf(appState)
        c.hookMethod {
            before { param ->
                if (param.args[at] !== active) return@before
                param.args[at] = idle
                if (stateAt >= 0 && param.args[stateAt] === foreground) param.args[stateAt] = background
            }
        }
    }
}

internal fun PatchExecutor.hideNativeMqttPresence() {
    val config = metaClass(CONNECTION_CONFIG)
    val chatOn = config.getDeclaredField(CHAT_ON_FIELD).apply { isAccessible = true }
    val getter = config.getDeclaredMethod(CHAT_ON_GETTER)
    config.declaredConstructors.forEach { c ->
        c.hookMethod { after { param -> runCatching { chatOn.setBoolean(param.thisObject, false) } } }
    }
    getter.hookMethod { before { param -> param.result = false } }
}

internal fun PatchExecutor.hideJavaMqttPresence() {
    val writer = ::clientInfoWriterFingerprint.method
    val available = writer.declaringClass.getDeclaredField(AVAILABLE_FIELD).apply { isAccessible = true }
    val publishers = ::clientSettingsPublishFingerprint.dexMethodList.map { it.toMethod() }
    writer.hookMethod {
        before { param -> if (available.get(param.thisObject) == true) available.set(param.thisObject, false) }
    }
    publishers.forEach { publish ->
        publish.hookMethod {
            before { param ->
                if (param.args.none { it == CLIENT_SETTINGS_TOPIC }) return@before
                param.args.indices.forEach { i ->
                    when (val arg = param.args[i]) {
                        is String -> if (arg != CLIENT_SETTINGS_TOPIC) unavailable(arg)?.let { param.args[i] = it }
                        is ByteArray -> unavailable(String(arg, Charsets.UTF_8))?.let { param.args[i] = it.toByteArray(Charsets.UTF_8) }
                    }
                }
            }
        }
    }
}

private fun unavailable(payload: String): String? {
    if (!payload.contains(AVAILABLE_KEY)) return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    if (!json.optBoolean(AVAILABLE_KEY, false)) return null
    return json.put(AVAILABLE_KEY, false).toString()
}
