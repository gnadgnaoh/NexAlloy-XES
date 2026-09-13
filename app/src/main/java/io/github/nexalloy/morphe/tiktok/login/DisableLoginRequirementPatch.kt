package io.github.nexalloy.morphe.tiktok.login

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method

private const val TAG = "[TikTok login]"

val DisableLoginRequirement = patch(
    name = "Disable login requirement",
    description = "Removes TikTok's forced login screen so the feed stays usable while signed out.",
) {
    val byName = MANDATORY_LOGIN_SERVICE_CLASS.findClassOrNull(classLoader)
        ?.declaredMethods
        ?.filter {
            it.name in MANDATORY_LOGIN_GATES &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterCount == 1
        }
        .orEmpty()

    // Only trust the name lookup when it found every gate; otherwise ask DexKit for all of them.
    val gates: List<Method> = byName.takeIf { it.map(Method::getName).containsAll(MANDATORY_LOGIN_GATES) }
        ?: ::mandatoryLoginGatesFingerprint.dexMethodList.map { it.toMethod() }
    check(gates.isNotEmpty()) { "no mandatory login gate found" }

    gates.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }
    Logger.printInfo { "$TAG forced login gates disabled: ${gates.joinToString { it.name }}" }
}
