package io.github.nexalloy.hoodles.morphe.protonvpn.freeservers

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

private const val INTEGER = "java.lang.Integer"
private const val LIST = "java.util.List"

internal val serverFilterTypeEnum = findClassDirect {
    findClass {
        matcher {
            superClass("java.lang.Enum")
            usingStrings(listOf("All", "SecureCore", "P2P", "Tor"), StringMatchType.Equals)
        }
    }.only("ServerFilterType enum") { it.name }
}

internal val serverGroupsMainScreenStateConstructor = findMethodDirect {
    dataClass("ServerGroupsMainScreenState(selectedFilter=").primaryConstructor().also {
        if (it.paramTypeNames.drop(1) != listOf(LIST, LIST)) throw Exception("Unexpected constructor ${it.descriptor}")
    }
}

internal val serverGroupsMainScreenSaveStateConstructor = findMethodDirect {
    dataClass("ServerGroupsMainScreenSaveState(selectedFilter=").primaryConstructor()
}

internal val citiesScreenSaveStateConstructor = findMethodDirect {
    dataClass("CitiesScreenSaveState(countryId=").primaryConstructor()
}

internal val serversScreenSaveStateConstructor = findMethodDirect {
    dataClass("ServersScreenSaveState(countryId=").primaryConstructor()
}

internal val serverGroupHeaderConstructor = findMethodDirect {
    dataClass("Header(labelRes=").primaryConstructor().also {
        if (it.paramTypeNames.take(2) != listOf("int", "int")) throw Exception("Unexpected constructor ${it.descriptor}")
    }
}

internal val serverGroupUiItemConstructor = findMethodDirect {
    dataClass("ServerGroup(data=").primaryConstructor().also {
        if (it.paramTypeNames.drop(1).firstOrNull() != "boolean") throw Exception("Unexpected constructor ${it.descriptor}")
    }
}

private val serverGroupItemToStateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(listOf("filterType"), StringMatchType.Equals)
        }
    }.filter { it.paramCount >= 2 && it.paramTypeNames[1] == INTEGER }
        .only("ServerGroupItemData.toState") { it.descriptor }
}

internal val serverGroupItemTierGetter = findMethodDirect {
    val itemData = firstOf(
        "ServerGroupItemData class",
        { serverGroupUiItemConstructor(this).paramTypeNames.first() },
        { serverGroupItemToStateFingerprint(this).paramTypeNames.first() },
    )
    findMethod {
        matcher {
            declaredClass(itemData)
            returnType = "int"
            paramCount = 0
        }
    }.filter { Modifier.isAbstract(it.modifiers) }.only("ServerGroupItemData.tier") { it.descriptor }
}

internal val searchResultsConstructor = findMethodDirect {
    dataClass("SearchResults(countries=").primaryConstructor().also {
        if (it.paramTypeNames.any { type -> type != LIST }) throw Exception("Unexpected constructor ${it.descriptor}")
    }
}

private fun <T> List<T>.only(what: String, describe: (T) -> String): T =
    singleOrNull() ?: throw Exception("Expected one $what, found $size: ${joinToString { describe(it) }}")

private fun DexKitBridge.dataClass(prefix: String): ClassData =
    findMethod {
        matcher {
            name = "toString"
            returnType = "java.lang.String"
            paramCount = 0
            usingStrings(listOf(prefix), StringMatchType.Equals)
        }
    }.map { it.declaredClass!! }.distinctBy { it.name }.only("data class '$prefix'") { it.name }

private const val ACC_SYNTHETIC = 0x1000

private fun ClassData.primaryConstructor(): MethodData =
    methods.filter { it.isConstructor && it.accessFlags and ACC_SYNTHETIC == 0 }
        .maxByOrNull { it.paramCount } ?: throw Exception("No constructor in $name")

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

// endregion
