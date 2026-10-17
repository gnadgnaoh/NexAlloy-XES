package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import android.view.View
import io.github.nexalloy.hoodles.morphe.protonvpn.freeservers.serverGroupsMainScreenStateConstructor
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId
import io.github.nexalloy.hookMethod
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
    fun isPromo(notification: Any?): Boolean {
        val type = notification?.javaClass?.getMethod("getType")?.invoke(notification) as? Int
        return type in PROMO_NOTIFICATION_TYPES
    }

    val apiNotification = classLoader.loadClass("com.protonvpn.android.promooffers.data.ApiNotification")
    val coroutine = ::activeNotificationsCoroutineFingerprint.method
    val listFields = coroutine.declaringClass.declaredFields
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
        .onEach { it.isAccessible = true }
    coroutine.hookMethod {
        before { param ->
            val self = param.thisObject ?: return@before
            for (field in listFields) {
                val value = field.get(self) as? List<*> ?: continue
                if (!apiNotification.isInstance(value.firstOrNull { it != null })) continue
                if (value.any(::isPromo)) field.set(self, value.filterNot(::isPromo))
            }
        }
    }

    run { // VPN Plus badge rows
        val plusBadge = getResourceId(ResourceType.DRAWABLE, "vpn_plus_badge")
        ::settingRowWithIconFingerprint.hookMethod {
            before { param ->
                if (param.args[4] == plusBadge) param.result = null
            }
        }
    }

    run { // restricted setting items
        val stateClass = ::settingsValueItemFingerprint.method.parameterTypes.first()
        val restrictedField = stateClass.declaredFields.single { it.type == Boolean::class.javaPrimitiveType }
            .apply { isAccessible = true }
        ::settingsValueItemFingerprint.hookMethod {
            before { param ->
                val state = param.args[0] ?: return@before
                if (restrictedField.getBoolean(state)) param.result = null
            }
        }
    }

    run { // server list upgrade banner
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

    run { // upgrade carousel
        val stateClass = ::upsellCarouselStateClass.clazz
        ::upgradeCarouselFingerprints.dexMethodList.ifEmpty { error("no producer") }.forEach { producer ->
            producer.hookMethod {
                after { param -> if (stateClass.isInstance(param.result)) param.result = null }
            }
        }
    }

    run { // account upgrade banner
        ::accountSettingsViewStateConstructor.hookMethod {
            before { param -> param.args[5] = false }
        }
    }

    run { // upgrade onboarding dialog
        dependsOn(SkipUpgradeOnboarding)
    }
}
