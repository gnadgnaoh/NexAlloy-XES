package io.github.nexalloy.v4n1x.morphe.soundcloud.consent

import android.view.View
import android.view.ViewGroup
import app.morphe.extension.shared.ResourceUtils
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch
import io.github.nexalloy.v4n1x.morphe.soundcloud.shared.keepCollapsed
import io.github.nexalloy.v4n1x.morphe.soundcloud.shared.singletonInstance
import org.luckypray.dexkit.wrap.DexMethod

private const val NOOP_CONSENT_CONTROLLER_CLASS =
    "com.soundcloud.android.privacy.consent.base.NoopPrivacyConsentController"

private const val CONSENT_BANNER_LAYOUT = "fragment_ot_banner"

val DisableConsent = patch(
    name = "Disable consent popup",
    description = "Disables the OneTrust consent/cookies popup and collapses banner views.",
) {
    val noopController = classLoader.loadClass(NOOP_CONSENT_CONTROLLER_CLASS).singletonInstance()
    PrivacyConsentControllerProviderFingerprint.hookMethod(
        XC_MethodReplacement.returnConstant(noopController)
    )

    val bannerLayoutId =
        runCatching { ResourceUtils.getLayoutIdentifier(CONSENT_BANNER_LAYOUT) }.getOrDefault(0)
    if (bannerLayoutId == 0) return@patch

    DexMethod("Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;")
        .hookMethod {
            after {
                if ((it.args[0] as? Int) != bannerLayoutId) return@after
                val result = it.result as? View ?: return@after
                val root = it.args[1] as? ViewGroup

                val banner = if (root != null && result === root) {
                    root.getChildAt(root.childCount - 1)
                } else {
                    result
                }
                banner?.keepCollapsed()
            }
        }
}
