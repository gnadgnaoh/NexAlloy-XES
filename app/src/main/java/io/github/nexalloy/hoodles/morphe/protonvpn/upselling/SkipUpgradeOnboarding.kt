package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.upgradeOnboardingLaunchFingerprint
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal val SkipUpgradeOnboarding = patch {
    var hooked = 0

    runCatching {
        ::upgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
        hooked++
    }.onFailure { e -> Logger.printInfo { "Proton VPN: launchOnboarding not found: $e" } }

    ::upgradeOnboardingStartFingerprints.dexMethodList.forEach { dexMethod ->
        runCatching {
            val method = dexMethod.toMethod()
            method.hookMethod(XC_MethodReplacement.returnConstant(method.emptyResult()))
            hooked++
        }.onFailure { e -> Logger.printInfo { "Proton VPN: $dexMethod not hooked: $e" } }
    }

    if (hooked == 0) error("Upgrade onboarding launcher not found")
}

private fun Method.emptyResult(): Any? {
    if (returnType == Void.TYPE) return null
    return returnType.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == returnType }
        ?.apply { isAccessible = true }?.get(null)
}
