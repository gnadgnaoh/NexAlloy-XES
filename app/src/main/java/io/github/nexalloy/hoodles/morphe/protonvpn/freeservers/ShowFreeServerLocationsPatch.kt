package io.github.nexalloy.hoodles.morphe.protonvpn.freeservers

import io.github.nexalloy.enumValueOf
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.SERVER
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.serverListFilterIncludeFreeField
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.serverListFilterLambdaFingerprint
import io.github.nexalloy.patch
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId
import java.lang.reflect.Constructor
import java.lang.reflect.Method

private const val FREE_TIER = 0

val ShowFreeServerLocations = patch(
    name = "Show free server locations",
    description = "Lists only free server locations in Countries and Search and connects to the one you pick. " +
        "Works with or without Unlock VPN Plus.",
) {
    val tierOf: Method = ::serverGroupItemTierGetter.method.apply { isAccessible = true }
    fun Any.tier() = tierOf.invoke(this) as Int

    dependsOn(FreeServersOnlyServerList)

    ::serverGroupUiItemConstructor.constructor.let { ctor ->
        val availableArg = 1
        ctor.hookMethod {
            before { param ->
                val item = param.args[0] ?: return@before
                if (item.tier() == FREE_TIER) param.args[availableArg] = true
            }
        }
    }

    ::searchResultsConstructor.hookMethod {
        before { param ->
            for (i in param.args.indices) {
                val items = param.args[i] as? List<*> ?: continue
                if (items.isNotEmpty()) param.args[i] = items.filter { it != null && it.tier() == FREE_TIER }
            }
        }
    }

    val filterType = ::serverFilterTypeEnum.clazz
    val filterAll = filterType.enumValueOf("All") ?: error("ServerFilterType.All not found")
    fun Constructor<*>.forceFilterAll() {
        val filterArg = parameterTypes.indexOfFirst { it == filterType }
        if (filterArg < 0) error("$this has no ServerFilterType parameter")
        hookMethod { before { param -> param.args[filterArg] = filterAll } }
    }
    listOf(
        ::serverGroupsMainScreenSaveStateConstructor,
        ::citiesScreenSaveStateConstructor,
        ::serversScreenSaveStateConstructor,
    ).forEach { fingerprint ->
        fingerprint.constructor.forceFilterAll()
    }
    ::serverGroupsMainScreenStateConstructor.constructor.let { ctor ->
        ctor.forceFilterAll()
        ctor.hookMethod { before { param -> param.args[2] = emptyList<Any>() } }
    }

    run { // header "free locations"
        val allCountries = getResourceId(ResourceType.STRING, "country_filter_all_list_header")
        val plusCountries = getResourceId(ResourceType.STRING, "country_filter_all_list_header_free")
        val freeLocations = getResourceId(ResourceType.STRING, "free_connections_info_server_locations")
        ::serverGroupHeaderConstructor.hookMethod {
            before { param ->
                val label = param.args[0]
                if (label == allCountries || label == plusCountries) param.args[0] = freeLocations
            }
        }
    }
}

internal val FreeServersOnlyServerList = patch {
    val isFreeServer: Method = classLoader.loadClass(SERVER).getMethod("isFreeServer")
    val filter = ::serverListFilterLambdaFingerprint.method
    val includeFree = ::serverListFilterIncludeFreeField.field.apply { isAccessible = true }
    val restoreKey = "protonvpn.restoreIncludeFree"
    filter.hookMethod {
        before { param ->
            val self = param.thisObject ?: return@before
            if (includeFree.getBoolean(self)) return@before
            val server = param.args[0] ?: return@before
            if (isFreeServer.invoke(server) == true) {
                includeFree.setBoolean(self, true)
                param.setObjectExtra(restoreKey, true)
            } else {
                param.result = false
            }
        }
        after { param ->
            if (param.getObjectExtra(restoreKey) == true) includeFree.setBoolean(param.thisObject, false)
        }
    }
}
