package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.opcodeEnum
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val SERVER = "com.protonvpn.android.servers.Server"
internal const val VPN_COUNTRY = "com.protonvpn.android.models.vpn.VpnCountry"
internal const val SERVER_MANAGER = "com.protonvpn.android.utils.ServerManager"
private const val PROTOCOL_SELECTION = "com.protonvpn.android.vpn.ProtocolSelection"
private const val USER_ID = "me.proton.core.domain.entity.UserId"
private const val SESSION_ID = "me.proton.core.network.domain.session.SessionId"
private const val BOOLEAN = "boolean"
private const val OBJECT = "java.lang.Object"
private const val LIST = "java.util.List"
private const val ARRAY_LIST = "java.util.ArrayList"
private const val INTEGER = "java.lang.Integer"
private const val ITERABLE = "java.lang.Iterable"
private const val SERIALIZABLE = "java.io.Serializable"

// R8 in newer builds (5.20.7x) narrows some declared types (List -> ArrayList, Object -> Serializable, ...).
private val LIST_TYPES = setOf(LIST, ARRAY_LIST)
private val OBJECT_TYPES = setOf(OBJECT, SERIALIZABLE)

private fun <T> firstOf(what: String, vararg strategies: () -> T): T {
    val errors = mutableListOf<String>()
    for (strategy in strategies) {
        try {
            return strategy()
        } catch (e: Exception) {
            errors += e.message ?: e.toString()
        }
    }
    throw Exception("$what: all ${strategies.size} strategies failed: ${errors.joinToString(" | ")}")
}

private const val IS_FREE_SERVER = "L${"com/protonvpn/android/servers/Server"};->isFreeServer()Z"
private const val GET_TIER = "L${"com/protonvpn/android/servers/Server"};->getTier()I"

private fun <T> List<T>.only(what: String, describe: (T) -> String): T =
    singleOrNull() ?: throw Exception("Expected one $what, found $size: ${joinToString { describe(it) }}")

private fun MethodData.isStatic() = Modifier.isStatic(modifiers)

private fun DexKitBridge.classesUsing(
    string: String,
    match: StringMatchType = StringMatchType.Equals,
): List<ClassData> = findClass { matcher { usingStrings(listOf(string), match) } }

private fun DexKitBridge.classByConstructorParams(
    vararg parameterNames: String,
    constructor: (MethodData) -> Boolean = { true },
): ClassData =
    findMethod {
        matcher {
            name = "<init>"
            usingStrings(parameterNames.toList(), StringMatchType.Equals)
        }
    }.filter(constructor).map { it.declaredClass!! }.distinctBy { it.name }
        .only("class with constructor params ${parameterNames.toList()}") { it.name }
        
internal val vpnUserClass = findClassDirect {
    classesUsing("VpnUser(", StringMatchType.StartsWith)
        .filter { cls -> cls.methods.any { it.isConstructor && it.paramTypeNames.firstOrNull() == USER_ID } }
        .only("VpnUser data class") { it.name }
}

internal val vpnUserConstructor = findMethodDirect {
    val owner = vpnUserClass(this).name
    findMethod {
        matcher {
            declaredClass(owner)
            name = "<init>"
        }
    }.filter { it.paramTypeNames.firstOrNull() == USER_ID && SESSION_ID in it.paramTypeNames }
        .only("VpnUser primary constructor") { it.descriptor }
}

internal val hasAccessToServerFingerprint = findMethodDirect {
    val vpnUser = vpnUserClass(this).name
    findMethod {
        matcher {
            returnType = BOOLEAN
            paramTypes(vpnUser, SERVER)
            addInvoke(GET_TIER)
        }
    }.filter { it.isStatic() }.only("hasAccessToServer") { it.descriptor }
}

/**
 * Newer builds inline [haveAccessWithFingerprint] into ServerGroup.hasAccessibleServer(Integer).
 * The public method name and the abstract getServerList() survive R8 on both builds.
 */
internal val serverGroupHasAccessibleServerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = BOOLEAN
            paramTypes(INTEGER)
        }
    }.filter { m ->
        !m.isStatic() && m.invokes.any { it.name == "getServerList" && it.className == m.className }
    }.only("ServerGroup.hasAccessibleServer(Integer)") { it.descriptor }
}

/**
 * Newer builds turn the server list filter into a lambda: ctor(includeFree: Boolean, ServerFilterType, ...),
 * invoke(Object server): Object.
 */
internal val serverListFilterLambdaFingerprint = findMethodDirect {
    val filterEnum = io.github.nexalloy.hoodles.morphe.protonvpn.freeservers.serverFilterTypeEnum(this).name
    findMethod {
        matcher {
            paramCount = 1
            addInvoke(IS_FREE_SERVER)
        }
    }.filter { m ->
        m.paramTypeNames == listOf(OBJECT) && m.returnTypeName == OBJECT && !m.isStatic() &&
            m.declaredClass!!.methods.any { c ->
                c.isConstructor && c.paramTypeNames.getOrNull(0) == BOOLEAN && c.paramTypeNames.getOrNull(1) == filterEnum
            }
    }.only("server list filter lambda") { it.descriptor }
}

/** The lambda field holding `includeFree` (first boolean field read by the lambda). */
internal val serverListFilterIncludeFreeField = findFieldDirect {
    val invoke = serverListFilterLambdaFingerprint(this)
    invoke.instructions.firstOrNull { it.opcodeEnum == Opcode.IGET_BOOLEAN && it.fieldRef?.className == invoke.className }
        ?.fieldRef ?: throw Exception("includeFree field not found in ${invoke.descriptor}")
}

internal val getBestScoreServerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER)
            returnType = SERVER
            usingStrings(listOf("serverList", "smartProtocols"), StringMatchType.Equals)
        }
    }.filter { it.paramTypeNames.firstOrNull() in setOf(ITERABLE, LIST) }
        .only("getBestScoreServer") { it.descriptor }
}

internal val serverManagerExitCountriesFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER)
            returnType = LIST
            paramTypes(BOOLEAN)
        }
    }.only("ServerManager.getExitCountries") { it.descriptor }
}

internal val serverGroupConstructor = findMethodDirect {
    val owner = classesUsing("ServerGroup(data=", StringMatchType.StartsWith)
        .only("ServerGroup data class") { it.name }.name
    findMethod {
        matcher {
            declaredClass(owner)
            name = "<init>"
        }
    }.filter { BOOLEAN in it.paramTypeNames && !it.paramTypeNames.last().endsWith("DefaultConstructorMarker") }
        .maxByOrNull { it.paramCount } ?: throw Exception("ServerGroup constructor not found")
}

internal val getFilterButtonsFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings(listOf("availableTypes", "selectedType"), StringMatchType.Equals) }
    }.filter { it.returnTypeName in LIST_TYPES && it.paramCount == 5 }
        .only("getFilterButtons") { it.descriptor }
}

internal val profileTypeEnum = findClassDirect {
    findClass {
        matcher {
            superClass("java.lang.Enum")
            usingStrings(listOf("Standard", "SecureCore", "P2P", "Gateway"), StringMatchType.Equals)
        }
    }.only("ProfileType enum") { it.name }
}

internal val standardProfileStateConstructor = findMethodDirect {
    val owner = classesUsing("Standard(availableTypes=", StringMatchType.StartsWith)
        .only("TypeAndLocationScreenState.Standard") { it.name }.name
    findMethod {
        matcher {
            declaredClass(owner)
            name = "<init>"
        }
    }.filter { it.paramTypeNames.firstOrNull() == LIST }
        .maxByOrNull { it.paramCount } ?: throw Exception("Standard screen state constructor not found")
}

internal val upgradeOnboardingLaunchFingerprint = findMethodDirect {
    val owner = classByConstructorParams("upgradeDialogLauncher") { ctor ->
        ctor.paramCount == 1 && ctor.declaredClass!!.methods.any { m ->
            m.usingStrings.containsAll(listOf("upgradeSource", "upgradeTrigger"))
        }
    }.name
    findMethod {
        matcher {
            declaredClass(owner)
            returnType = "void"
            paramTypes("android.content.Context")
        }
    }.only("launchOnboarding") { it.descriptor }
}

private fun DexKitBridge.serverManager2(): ClassData =
    classByConstructorParams("serverManager", "getSmartProtocols") { ctor ->
        ctor.paramCount == 2 && ctor.paramTypeNames.first() == SERVER_MANAGER
    }

private fun DexKitBridge.freeCountriesGetter(): MethodData =
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER)
            paramCount = 0
            addInvoke(IS_FREE_SERVER)
        }
    }.filter { getter ->
        getter.returnTypeName in LIST_TYPES &&
            getter.invokes.any { it.className == SERVER_MANAGER && it.returnTypeName in LIST_TYPES }
    }
        .only("ServerManager.freeCountries") { it.descriptor }

private fun DexKitBridge.vpnCountriesGetter(): MethodData =
    freeCountriesGetter().invokes
        .filter { it.className == SERVER_MANAGER && it.returnTypeName in LIST_TYPES && it.paramCount == 0 }
        .distinctBy { it.descriptor }
        .only("ServerManager.getVpnCountries") { it.descriptor }

private fun DexKitBridge.serverManager2Wrapper(getter: MethodData, what: String): MethodData {
    val owner = serverManager2().name
    return findMethod {
        matcher {
            declaredClass(owner)
            paramCount = 1
            addInvoke(getter.descriptor)
        }
    }.filter { wrapper ->
        wrapper.returnTypeName in OBJECT_TYPES && wrapper.invokes.none { it.name == "size" && it.className == LIST }
    }
        .only(what) { it.descriptor }
}

internal val serverManager2GetVpnCountriesFingerprint = findMethodDirect {
    serverManager2Wrapper(vpnCountriesGetter(), "ServerManager2.getVpnCountries")
}

internal val serverManager2GetFreeCountriesFingerprint = findMethodDirect {
    serverManager2Wrapper(freeCountriesGetter(), "ServerManager2.getFreeCountries")
}

internal val profileCountriesFingerprint = findMethodDirect {
    val getVpnCountries = serverManager2GetVpnCountriesFingerprint(this)
    val owner = getVpnCountries.className
    getVpnCountries.callers
        .filter { it.className != owner && !it.className.startsWith("$owner\$") }
        // newer builds flatten inner lambdas of the owner into sibling classes of the same package
        .filter { it.className.substringBeforeLast('.', "") != owner.substringBeforeLast('.', "") }
        .distinctBy { it.descriptor }
        .only("ProfilesServerDataAdapter.countries") { it.descriptor }
}

internal val serverManager2GetRandomServerFingerprint = findMethodDirect {
    val owner = serverManager2().name
    val vpnUser = vpnUserClass(this).name
    findMethod {
        matcher {
            declaredClass(owner)
            paramCount = 3
        }
    }.filter { it.returnTypeName in OBJECT_TYPES && it.paramTypeNames.take(2) == listOf(vpnUser, PROTOCOL_SELECTION) }
        .only("ServerManager2.getRandomServer") { it.descriptor }
}

internal val serverManager2ServerManagerField = findFieldDirect {
    serverManager2().fields.filter { it.typeName == SERVER_MANAGER }
        .only("ServerManager2.serverManager field") { it.descriptor }
}

private fun DexKitBridge.changeServerViewStateFlow(): ClassData =
    classByConstructorParams("changeServerConfigFlow", "changeServerPrefs", "changeServerManager")

internal val changeServerViewStateFlowCollectFingerprint = findMethodDirect {
    val owner = changeServerViewStateFlow().name
    findMethod {
        matcher {
            declaredClass(owner)
            returnType = OBJECT
            paramCount = 2
        }
    }.filter { !it.isStatic() && !it.isConstructor }.only("ChangeServerViewStateFlow.collect") { it.descriptor }
}

internal val freeUserChangeServerStateField = findFieldDirect {
    val owner = changeServerViewStateFlow()
    val collect = changeServerViewStateFlowCollectFingerprint(this)

    val stateField: FieldData = collect.instructions
        .firstNotNullOfOrNull { insn ->
            insn.fieldRef?.takeIf { insn.opcodeEnum == Opcode.IGET_OBJECT && it.className == owner.name }
        } ?: throw Exception("collect() does not read a Flow field of ${owner.name}")

    val constructor = owner.methods.single { it.isConstructor && it.paramCount > 0 }
    val insns = constructor.instructions
    val storeIndex = insns.indexOfFirst {
        it.opcodeEnum == Opcode.IPUT_OBJECT && it.fieldRef?.descriptor == stateField.descriptor
    }
    if (storeIndex < 0) throw Exception("stateFlow store not found in ${owner.name}.<init>")
    val previousStore = insns.subList(0, storeIndex)
        .indexOfLast { it.opcodeEnum == Opcode.IPUT_OBJECT && it.fieldRef?.className == owner.name }

    // The flow field assigned right before the collected one (VPN-state-gated change-server flow).
    if (previousStore < 0) throw Exception("no field store before the state flow store")
    insns[previousStore].fieldRef!!.also {
        if (it.descriptor == stateField.descriptor) throw Exception("previous store is the state field itself")
    }
}

private const val VPN_COUNTRY_GET_SERVER_LIST = "Lcom/protonvpn/android/models/vpn/VpnCountry;->getServerList()Ljava/util/List;"

val profileServerListReadersFingerprints = findMethodListDirect {
    val adapter = profileCountriesFingerprint(this).className
    findMethod {
        matcher {
            declaredClass(adapter)
            addInvoke(VPN_COUNTRY_GET_SERVER_LIST)
        }
    }.ifEmpty { throw Exception("No ProfilesServerDataAdapter method reads VpnCountry.serverList") }
}

internal val vpnCountryGetServerListFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(VPN_COUNTRY)
            name = "getServerList"
            paramCount = 0
        }
    }.only("VpnCountry.getServerList") { it.descriptor }
}
