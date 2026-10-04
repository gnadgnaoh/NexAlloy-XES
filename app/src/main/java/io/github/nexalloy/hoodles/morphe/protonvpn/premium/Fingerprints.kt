package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
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

internal val haveAccessWithFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = BOOLEAN
            paramTypes(SERVER, "java.lang.Integer")
            addInvoke(GET_TIER)
        }
    }.filter { it.isStatic() }.only("haveAccessWith") { it.descriptor }
}

internal val serverListFilterFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = BOOLEAN
            paramCount(5, 12)
            addInvoke(IS_FREE_SERVER)
        }
    }.filter { SERVER in it.paramTypeNames }.only("server list filter") { it.descriptor }
}

internal val getBestScoreServerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER)
            returnType = SERVER
            paramCount = 4
            usingStrings(listOf("serverList", "smartProtocols"), StringMatchType.Equals)
        }
    }.filter { it.paramTypeNames.firstOrNull() == "java.lang.Iterable" }
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

private fun DexKitBridge.netShieldAvailabilityEnum(): String =
    findClass {
        matcher {
            superClass("java.lang.Enum")
            usingStrings(listOf("AVAILABLE", "UPGRADE_VPN_PLUS"), StringMatchType.Equals)
        }
    }.only("NetShieldAvailability enum") { it.name }.name

internal val getNetShieldAvailabilityFingerprint = findMethodDirect {
    val vpnUser = vpnUserClass(this).name
    findMethod {
        matcher {
            returnType = netShieldAvailabilityEnum()
            paramTypes(vpnUser)
        }
    }.filter { it.isStatic() }.only("getNetShieldAvailability") { it.descriptor }
}

internal val getFilterButtonsFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = LIST
            usingStrings(listOf("availableTypes", "selectedType", "emptyTypes"), StringMatchType.Equals)
        }
    }.only("getFilterButtons") { it.descriptor }
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
            returnType = LIST
            paramCount = 0
            addInvoke(IS_FREE_SERVER)
        }
    }.filter { getter -> getter.invokes.any { it.className == SERVER_MANAGER && it.returnTypeName == LIST } }
        .only("ServerManager.freeCountries") { it.descriptor }

private fun DexKitBridge.vpnCountriesGetter(): MethodData =
    freeCountriesGetter().invokes
        .filter { it.className == SERVER_MANAGER && it.returnTypeName == LIST && it.paramCount == 0 }
        .distinctBy { it.descriptor }
        .only("ServerManager.getVpnCountries") { it.descriptor }

private fun DexKitBridge.serverManager2Wrapper(getter: MethodData, what: String): MethodData {
    val owner = serverManager2().name
    return findMethod {
        matcher {
            declaredClass(owner)
            returnType = OBJECT
            paramCount = 1
            addInvoke(getter.descriptor)
        }
    }.filter { wrapper -> wrapper.invokes.none { it.name == "size" && it.className == LIST } }
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
        .distinctBy { it.descriptor }
        .only("ProfilesServerDataAdapter.countries") { it.descriptor }
}

internal val serverManager2GetRandomServerFingerprint = findMethodDirect {
    val owner = serverManager2().name
    val vpnUser = vpnUserClass(this).name
    findMethod {
        matcher {
            declaredClass(owner)
            returnType = OBJECT
            paramCount = 3
        }
    }.filter { it.paramTypeNames.take(2) == listOf(vpnUser, PROTOCOL_SELECTION) }
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

    val lambdaClasses = insns.subList(previousStore + 1, storeIndex)
        .filter { it.opcodeEnum == Opcode.NEW_INSTANCE }
        .mapNotNull { it.classRef?.name }
        .filter { it.startsWith("${owner.name}\$") }

    val flowFields = owner.fields.filter { it.typeName == stateField.typeName && it.descriptor != stateField.descriptor }
    val accessed = lambdaClasses.flatMap { lambda ->
        findClass { matcher { className(lambda) } }.flatMap { it.methods }.flatMap { it.invokes }
            .filter { it.className == owner.name && it.isStatic() }
            .flatMap { accessor -> accessor.usingFields.map { it.field } }
    }.filter { field -> flowFields.any { it.descriptor == field.descriptor } }
        .distinctBy { it.descriptor }

    accessed.only("freeUserChangeServerState field") { it.descriptor }
}

internal val isFeatureFlagEnabledFingerprint = findMethodDirect {
    val base = findMethod {
        matcher {
            name = "<init>"
            usingStrings(listOf("featureFlagManager", "featureId"), StringMatchType.Equals)
        }
    }.map { it.declaredClass!! }.filter { Modifier.isAbstract(it.modifiers) }
        .distinctBy { it.name }.only("IsFeatureFlagEnabledImpl") { it.name }
    base.methods.filter { !Modifier.isAbstract(it.modifiers) && it.paramTypeNames == listOf(USER_ID) && it.returnTypeName == BOOLEAN }
        .filter { m -> m.invokes.count { it.className == base.name } >= 2 }
        .only("IsFeatureFlagEnabledImpl.invoke") { it.descriptor }
}
