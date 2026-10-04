package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.enumValueOf
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections

private const val SUBSCRIBED = 1
private const val MAX_TIER = 3
private const val PAID_TIER_NAME = "vpn2022"

private val freeServersOnlyDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

private fun Constructor<*>.onlyParamOf(type: Class<*>, role: String): Int =
    parameterTypes.withIndex().filter { it.value == type }.map { it.index }.singleOrNull()
        ?: throw Exception("$declaringClass: expected one ${type.simpleName} parameter for $role")

private fun Constructor<*>.firstParamOf(type: Class<*>, role: String): Int =
    parameterTypes.indexOfFirst { it == type }.takeIf { it >= 0 }
        ?: throw Exception("$declaringClass: no ${type.simpleName} parameter for $role")

val UnlockVpnPlus = patch(
    name = "Unlock VPN Plus",
    description = "Spoofs the highest plan tier for all UI unlocks, while routing connections through free servers for server-side compatibility. Also skips the upgrade onboarding dialog after login.",
) {
    val serverClass = classLoader.loadClass(SERVER)
    val isFreeServer: Method = serverClass.getMethod("isFreeServer")
    val getOnline: Method = serverClass.getMethod("getOnline")
    val getServerList: Method = classLoader.loadClass(VPN_COUNTRY).getMethod("getServerList")
    fun Any.isFree() = isFreeServer.invoke(this) as Boolean

    val vpnUserCtor = ::vpnUserConstructor.constructor
    val subscribedArg = vpnUserCtor.firstParamOf(Int::class.javaPrimitiveType!!, "subscribed")
    val planNameArg = vpnUserCtor.firstParamOf(String::class.java, "planName")
    val maxTierArg = vpnUserCtor.onlyParamOf(Int::class.javaObjectType, "maxTier")
    vpnUserCtor.hookMethod {
        before { param ->
            param.args[subscribedArg] = SUBSCRIBED
            param.args[planNameArg] = PAID_TIER_NAME
            param.args[maxTierArg] = MAX_TIER
        }
    }

    ::hasAccessToServerFingerprint.hookMethod {
        before { param ->
            param.result = if (freeServersOnlyDepth.get()!! > 0) {
                param.args[0] != null && param.args[1]?.isFree() == true
            } else {
                true
            }
        }
    }
    ::haveAccessWithFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    ::serverGroupConstructor.constructor.let { ctor ->
        val availableArg = ctor.firstParamOf(Boolean::class.javaPrimitiveType!!, "available")
        ctor.hookMethod { before { param -> param.args[availableArg] = true } }
    }

    ::serverListFilterFingerprint.method.let { filter ->
        val serverArg = filter.parameterTypes.indexOfFirst { it == serverClass }
        filter.hookMethod {
            before { param ->
                val server = param.args[serverArg] ?: return@before
                if (!server.isFree()) param.result = false
            }
        }
    }

    ::getBestScoreServerFingerprint.hookMethod {
        before { param ->
            val servers = param.args[0] as? Iterable<*> ?: return@before
            val freeServers = ArrayList<Any>()
            for (server in servers) {
                if (server != null && server.isFree()) freeServers.add(server)
            }
            if (freeServers.isNotEmpty()) param.args[0] = freeServers
        }
    }

    ::isFeatureFlagEnabledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    ::getNetShieldAvailabilityFingerprint.method.let { method ->
        val available = method.returnType.enumValueOf("AVAILABLE")
            ?: error("NetShieldAvailability.AVAILABLE not found")
        method.hookMethod(XC_MethodReplacement.returnConstant(available))
    }

    ::getFilterButtonsFingerprint.hookMethod(
        XC_MethodReplacement.returnConstant(Collections.emptyList<Any>())
    )

    val standardProfileType = ::profileTypeEnum.clazz.enumValueOf("Standard")
        ?: error("ProfileType.Standard not found")
    ::standardProfileStateConstructor.constructor.hookMethod {
        before { param -> param.args[0] = arrayListOf<Any>(standardProfileType) }
    }

    val getVpnCountries = ::serverManager2GetVpnCountriesFingerprint.method
    val getFreeCountries = ::serverManager2GetFreeCountriesFingerprint.method.apply { isAccessible = true }
    ::profileCountriesFingerprint.hookMethod(scopedHook(getVpnCountries) {
        before { param ->
            try {
                param.result = getFreeCountries.invoke(param.thisObject, *param.args)
            } catch (e: InvocationTargetException) {
                param.throwable = e.cause ?: e
            }
        }
    })

    runCatching {
        val getRandomServer = ::serverManager2GetRandomServerFingerprint.method
        val serverManagerField = ::serverManager2ServerManagerField.field.apply { isAccessible = true }
        val getExitCountries = ::serverManagerExitCountriesFingerprint.method.apply { isAccessible = true }

        val collectMethod = ::changeServerViewStateFlowCollectFingerprint.method
        val freeStateField = ::freeUserChangeServerStateField.field.apply { isAccessible = true }
        val flowCollect = collectMethod.declaringClass.interfaces.firstNotNullOf { flow ->
            runCatching { flow.getMethod(collectMethod.name, *collectMethod.parameterTypes) }.getOrNull()
        }

        collectMethod.hookMethod {
            before { param ->
                val flow = freeStateField.get(param.thisObject) ?: return@before
                try {
                    param.result = flowCollect.invoke(flow, *param.args)
                } catch (e: InvocationTargetException) {
                    param.throwable = e.cause ?: e
                }
            }
        }

        getRandomServer.hookMethod {
            before { freeServersOnlyDepth.set(freeServersOnlyDepth.get()!! + 1) }
            after { param ->
                freeServersOnlyDepth.set(freeServersOnlyDepth.get()!! - 1)
                val server = param.result
                if (server == null || !serverClass.isInstance(server) || server.isFree()) return@after
                val serverManager = serverManagerField.get(param.thisObject) ?: return@after
                pickRandomFreeServer(serverManager, getExitCountries, getServerList, isFreeServer, getOnline)
                    ?.let { param.result = it }
            }
        }
    }.onFailure { e ->
        Logger.printInfo { "Proton VPN: change server button not restored: $e" }
    }

    runCatching {
        ::upgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    }.onFailure { e ->
        Logger.printInfo { "Proton VPN: UpgradeOnboardingLaunch not hooked, onboarding dialog not skipped: $e" }
    }
}

private fun pickRandomFreeServer(
    serverManager: Any,
    getExitCountries: Method,
    getServerList: Method,
    isFreeServer: Method,
    getOnline: Method,
): Any? = runCatching {
    val countries = getExitCountries.invoke(serverManager, false) as List<*>
    countries
        .mapNotNull { country ->
            (getServerList.invoke(country) as List<*>)
                .filter { server ->
                    server != null &&
                        isFreeServer.invoke(server) as Boolean &&
                        getOnline.invoke(server) as Boolean
                }
                .takeIf { it.isNotEmpty() }
        }
        .randomOrNull()
        ?.randomOrNull()
}.getOrNull()
