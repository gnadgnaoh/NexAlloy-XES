package io.github.nexalloy.revanced.instagram.network

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

private val SETUP_SCREENS = setOf(
    "com.bloks.www.bloks.ig.ndx.ci.entry.screen",
    "com.bloks.www.bloks.ig.ndx.ls.entry.screen",
)

private fun holdsSetupScreen(host: Any?): Boolean {
    var type: Class<*>? = host?.javaClass ?: return false
    while (type != null && type != Any::class.java) {
        for (f in type.declaredFields) {
            if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
            val value = runCatching { f.isAccessible = true; f.get(host) }.getOrNull()
            if (value in SETUP_SCREENS) return true
        }
        type = type.superclass
    }
    return false
}

val SkipSetupScreens = patch(
    name = "Skip contacts and location setup screens",
    description = "Skips the contacts and location setup screens, which keep coming back while analytics are blocked.",
) {
    val openers = ::bloksScreenOpenersFingerprint.dexMethodList.map { it.toMethod() }
    check(openers.isNotEmpty()) { "no Bloks screen opener found" }
    val presenter = runCatching { ::bloksScreenPresenterFingerprint.method }.getOrNull()

    openers.forEach { m ->
        m.hookMethod { before { param -> if (holdsSetupScreen(param.thisObject)) param.result = null } }
    }
    presenter?.hookMethod { before { param -> if (holdsSetupScreen(param.args[1])) param.result = null } }
}
