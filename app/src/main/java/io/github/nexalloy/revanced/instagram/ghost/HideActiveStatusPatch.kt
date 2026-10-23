package io.github.nexalloy.revanced.instagram.ghost

import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val PRESENCE_WRITE_REQUEST = "com.facebook.presence.model.upi.PresenceWriteRequest"
private const val PRESENCE_STATUS = "com.facebook.presence.model.upi.PresenceStatus"

val HideActiveStatus = patch(
    name = "Hide your active status",
    description = "Stops people from seeing Active now for you. You still see when they're active.",
) {
    val request = PRESENCE_WRITE_REQUEST.findClassOrNull(classLoader) ?: error("$PRESENCE_WRITE_REQUEST not found")
    val status = PRESENCE_STATUS.findClassOrNull(classLoader) ?: error("$PRESENCE_STATUS not found")
    val values = status.enumConstants.orEmpty()
    val active = values.singleOrNull { (it as Enum<*>).name == "ACTIVE" } ?: error("no ACTIVE status")
    val idle = values.singleOrNull { (it as Enum<*>).name == "IDLE" } ?: error("no IDLE status")

    val constructors = request.declaredConstructors.filter { status in it.parameterTypes }
    check(constructors.isNotEmpty()) { "no PresenceWriteRequest constructor takes a status" }
    constructors.forEach { c ->
        val at = c.parameterTypes.indexOf(status)
        c.hookMethod { before { param -> if (param.args[at] === active) param.args[at] = idle } }
    }
}
