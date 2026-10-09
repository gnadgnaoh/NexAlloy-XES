package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import android.view.View
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hoodles.morphe.protonvpn.freeservers.serverGroupsMainScreenStateConstructor
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId
import io.github.nexalloy.patch

private const val TYPE_ONE_TIME_POPUP = 1
private const val TYPE_HOME_SCREEN_BANNER = 2
private const val TYPE_HOME_PROMINENT_BANNER = 3
private const val TYPE_ONE_TIME_IAP_POPUP = 5
private const val TYPE_BUILTIN_UPSELL_ONBOARDING = 6
private const val TYPE_BUILTIN_UPSELL_PADLOCK = 7
private const val TYPE_INTERNAL_ONE_TIME_IAP_POPUP = 1_000_000

private val PROMO_NOTIFICATION_TYPES = setOf(
    TYPE_ONE_TIME_POPUP,
    TYPE_HOME_SCREEN_BANNER,
    TYPE_HOME_PROMINENT_BANNER,
    TYPE_ONE_TIME_IAP_POPUP,
    TYPE_BUILTIN_UPSELL_ONBOARDING,
    TYPE_BUILTIN_UPSELL_PADLOCK,
    TYPE_INTERNAL_ONE_TIME_IAP_POPUP,
)

val HideUpgradePromotions = patch(
    name = "Hide upgrade promotions",
    description = "Hides settings that need a paid plan, upgrade banners, the Discover VPN Plus carousel and special offers.",
) {
    fun optional(what: String, block: () -> Unit) = runCatching(block).onFailure { e ->
        Logger.printInfo { "Proton VPN: $what not hidden: $e" }
    }

    ::activeNotificationsFingerprint.hookMethod {
        after { param ->
            val notifications = param.result as? List<*> ?: return@after
            param.result = notifications.filterNot { notification ->
                val type = notification?.javaClass?.getMethod("getType")?.invoke(notification) as? Int
                type in PROMO_NOTIFICATION_TYPES
            }
        }
    }

    optional("VPN Plus badge rows") {
        val plusBadge = getResourceId(ResourceType.DRAWABLE, "vpn_plus_badge")
        ::settingRowWithIconFingerprint.hookMethod {
            before { param ->
                if (param.args[4] == plusBadge) param.result = null
            }
        }
    }

    optional("restricted setting items") {
        val isRestricted = ::settingViewStateIsRestricted.method
        ::settingsValueItemFingerprint.hookMethod {
            before { param ->
                val state = param.args[0] ?: return@before
                if (isRestricted.invoke(state) == true) param.result = null
            }
        }
    }

    optional("server list upgrade banner") {
        val bannerClass = ::serverGroupBannerClass.clazz
        ::serverGroupsMainScreenStateConstructor.hookMethod {
            before { param ->
                val items = param.args[1] as? List<*> ?: return@before
                if (items.any { bannerClass.isInstance(it) }) {
                    param.args[1] = items.filterNot { bannerClass.isInstance(it) }
                }
            }
        }
    }

    optional("free connections upsell banner") {
        val upsellBannerField = ::freeConnectionsUpsellBannerField.field.apply { isAccessible = true }
        val upsellBannerRoot = ::freeConnectionsUpsellBannerRoot.method.apply { isAccessible = true }
        ::freeConnectionsSetupViewsFingerprint.hookMethod {
            after { param ->
                val banner = upsellBannerField.get(param.args[0]) ?: return@after
                (upsellBannerRoot.invoke(banner) as? View)?.visibility = View.GONE
            }
        }
    }

    optional("upgrade carousel") {
        val stateClass = ::upsellCarouselStateClass.clazz
        ::upgradeCarouselFingerprints.dexMethodList.ifEmpty { error("no producer") }.forEach { producer ->
            producer.hookMethod {
                after { param -> if (stateClass.isInstance(param.result)) param.result = null }
            }
        }
    }

    optional("account upgrade banner") {
        ::accountSettingsViewStateConstructor.hookMethod {
            before { param -> param.args[5] = false }
        }
    }

    optional("upgrade onboarding dialog") {
        dependsOn(SkipUpgradeOnboarding)
    }
}
