package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.upgradeOnboardingLaunchFingerprint
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal val SkipUpgradeOnboarding = patch {
    ::upgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
}

private fun Method.emptyResult(): Any? {
    if (returnType == Void.TYPE) return null
    return returnType.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == returnType }
        ?.apply { isAccessible = true }?.get(null)
}
