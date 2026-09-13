package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.callMethod
import io.github.nexalloy.enumValueOf
import io.github.nexalloy.findField
import io.github.nexalloy.getObjectField
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections

private const val MAX_TIER = 3
private const val PAID_TIER_NAME = "vpn2022"

private val freeServersOnlyDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

val UnlockVpnPlus = patch(
    name = "Unlock VPN Plus",
    description = "Spoofs the highest plan tier for all UI unlocks, while routing connections through free servers for server-side compatibility. Also skips the upgrade onboarding dialog after login.",
) {
    val serverClass = classLoader.loadClass("com.protonvpn.android.servers.Server")
    val isFreeServer: Method = serverClass.getMethod("isFreeServer")
    fun Any.isFree() = isFreeServer.invoke(this) as Boolean

    VpnUserConstructorFingerprint.hookMethod {
        before { param ->
            param.args[1] = 1
            param.args[11] = MAX_TIER
        }
    }

    VpnUserGetUserTierFingerprint.hookMethod(XC_MethodReplacement.returnConstant(MAX_TIER))
    VpnUserGetMaxTierFingerprint.hookMethod(XC_MethodReplacement.returnConstant(MAX_TIER))
    VpnUserIsFreeUserFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
    VpnUserIsUserPlusOrAboveFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    VpnUserGetUserTierNameFingerprint.hookMethod(XC_MethodReplacement.returnConstant(PAID_TIER_NAME))
    HasAccessToServerFingerprint.hookMethod {
        before { param ->
            param.result = if (freeServersOnlyDepth.get()!! > 0) {
                param.args[0] != null && param.args[1]?.isFree() == true
            } else {
                true
            }
        }
    }
    HaveAccessWithFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    ServerGroupGetAvailableFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    ServerListFilterFingerprint.hookMethod {
        before { param ->
            val server = param.args[6] ?: return@before
            if (!server.isFree()) param.result = false
        }
    }

    GetBestScoreServerFingerprint.hookMethod {
        before { param ->
            val servers = param.args[0] as? Iterable<*> ?: return@before
            val freeServers = ArrayList<Any>()
            for (server in servers) {
                if (server != null && server.isFree()) freeServers.add(server)
            }
            if (freeServers.isNotEmpty()) param.args[0] = freeServers
        }
    }

    IsFeatureFlagEnabledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    GetNetShieldAvailabilityFingerprint.method.let { method ->
        val available = method.returnType.enumValueOf("AVAILABLE")
            ?: error("NetShieldAvailability.AVAILABLE not found")
        method.hookMethod(XC_MethodReplacement.returnConstant(available))
    }

    GetFilterButtonsFingerprint.hookMethod(
        XC_MethodReplacement.returnConstant(Collections.emptyList<Any>())
    )

    val standardProfileType = classLoader
        .loadClass("com.protonvpn.android.profiles.ui.ProfileType")
        .enumValueOf("Standard")
        ?: error("ProfileType.Standard not found")
    ProfileAvailableTypesFingerprint.hookMethod {
        before { param ->
            param.result = arrayListOf<Any>(standardProfileType)
        }
    }

    val getVpnCountries = ServerManager2GetVpnCountriesFingerprint.method
    val getFreeCountries = getVpnCountries.declaringClass
        .getDeclaredMethod("getFreeCountries", *getVpnCountries.parameterTypes)
        .apply { isAccessible = true }
    ProfileCountriesFingerprint.hookMethod(scopedHook(getVpnCountries) {
        before { param ->
            try {
                param.result = getFreeCountries.invoke(param.thisObject, *param.args)
            } catch (e: InvocationTargetException) {
                param.throwable = e.cause ?: e
            }
        }
    })

    runCatching {
        val getRandomServer = ServerManager2GetRandomServerFingerprint.method
        val collectMethod = ChangeServerViewStateFlowCollectFingerprint.method
        val freeStateField = collectMethod.declaringClass.findField("freeUserChangeServerState")
        val flowCollect = classLoader.loadClass("kotlinx.coroutines.flow.Flow")
            .getMethod("collect", *collectMethod.parameterTypes)
        val distinctUntilChanged = runCatching {
            classLoader.loadClass("kotlinx.coroutines.flow.FlowKt")
                .getMethod("distinctUntilChanged", flowCollect.declaringClass)
        }.getOrNull()

        collectMethod.hookMethod {
            before { param ->
                var flow = freeStateField.get(param.thisObject) ?: return@before
                distinctUntilChanged?.let { flow = it.invoke(null, flow) ?: flow }
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
                pickRandomFreeServer(param.thisObject, isFreeServer)?.let { param.result = it }
            }
        }
    }.onFailure { e ->
        Logger.printInfo { "Proton VPN: change server button not restored: $e" }
    }

    runCatching {
        UpgradeOnboardingLaunchFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    }.onFailure { e ->
        Logger.printInfo { "Proton VPN: UpgradeOnboardingLaunch not hooked, onboarding dialog not skipped: $e" }
    }
}

private fun pickRandomFreeServer(serverManager2: Any, isFreeServer: Method): Any? = runCatching {
    val serverManager = serverManager2.getObjectField("serverManager")!!
    val countries = serverManager.callMethod("getExitCountries", false) as List<*>
    countries
        .mapNotNull { country ->
            (country!!.callMethod("getServerList") as List<*>)
                .filter { server ->
                    server != null &&
                        isFreeServer.invoke(server) as Boolean &&
                        server.callMethod("getOnline") as Boolean
                }
                .takeIf { it.isNotEmpty() }
        }
        .randomOrNull()
        ?.randomOrNull()
}.getOrNull()
