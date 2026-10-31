package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.upgradeOnboardingLaunchFingerprint
import io.github.nexalloy.patch

private const val ONBOARDING = "ONBOARDING"

internal val SkipUpgradeOnboarding = patch {
    val launcher = runCatching {
        ::upgradeDialogLauncherFingerprint.hookMethod {
            before { param ->
                val source = param.args[1] as? Enum<*>
                val trigger = param.args[2] as? Enum<*>
                if (source?.name == ONBOARDING || trigger?.name == ONBOARDING) param.result = null
            }
        }
    }
    if (launcher.isFailure) ::upgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
}
