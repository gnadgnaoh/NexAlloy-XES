package io.github.nexalloy.hoodles.morphe.protonvpn.upselling

import io.github.nexalloy.hoodles.morphe.protonvpn.premium.vpnUserClass
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findFieldFromToString
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

private const val SETTINGS_UI = "com.protonvpn.android.redesign.settings.ui"
private const val API_NOTIFICATION = "Lcom/protonvpn/android/promooffers/data/ApiNotification;"
private const val CONSTRAINT_LAYOUT = "androidx.constraintlayout.widget.ConstraintLayout"
private const val INVOKE_VIRTUAL = 0x6e
private const val MOVE_RESULT = 0x0a
private const val IF_NEZ = 0x39
private const val DIV_INT_LIT8 = 0xdb
private const val MUL_INT_LIT8 = 0xda

internal val settingRowWithIconFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings(listOf("$SETTINGS_UI.SettingRowWithIcon ("), StringMatchType.StartsWith)
        }
    }.filter { it.paramCount >= 5 && it.paramTypeNames[4] == "java.lang.Integer" }
        .only("SettingRowWithIcon") { it.descriptor }
}

internal val settingsValueItemFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 6
            usingStrings(listOf("$SETTINGS_UI.SettingsValueItem ("), StringMatchType.StartsWith)
        }
    }.only("SettingsValueItem") { it.descriptor }
}

internal val settingViewStateIsRestricted = findMethodDirect {
    val state = settingsValueItemFingerprint(this).paramTypeNames.first()
    findMethod {
        matcher {
            declaredClass(state)
            returnType = "boolean"
            paramCount = 0
        }
    }.filter { !Modifier.isStatic(it.modifiers) }.only("SettingViewState.isRestricted") { it.descriptor }
}

internal val activeNotificationsFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.util.List"
            paramTypes("long", "java.util.List")
            addInvoke("$API_NOTIFICATION->getStartTime()J")
            addInvoke("$API_NOTIFICATION->getEndTime()J")
        }
    }.only("active notifications filter") { it.descriptor }
}

internal val serverGroupBannerClass = findClassDirect {
    dataClass("Banner(type=")
}

internal val freeConnectionsSetupViewsFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount(3..4)
            usingStrings(listOf("getRoot(...)", "inflate(...)"), StringMatchType.Equals)
            addInvoke("Landroid/view/LayoutInflater;->from(Landroid/content/Context;)Landroid/view/LayoutInflater;")
        }
    }.filter { m ->
        m.paramTypeNames[1] == "android.content.Context" && m.paramTypeNames.last() == "java.util.List" &&
            m.invokes.any { it.returnTypeName == CONSTRAINT_LAYOUT }
    }
        .only("FreeConnectionsInfoBinding.setupViews") { it.descriptor }
}

internal val freeConnectionsUpsellBannerRoot = findMethodDirect {
    freeConnectionsSetupViewsFingerprint(this).invokes
        .filter { it.returnTypeName == CONSTRAINT_LAYOUT && it.paramCount == 0 }
        .distinctBy { it.descriptor }
        .only("upsell banner getRoot()") { it.descriptor }
}

internal val freeConnectionsUpsellBannerField = findFieldDirect {
    val bindingClass = freeConnectionsSetupViewsFingerprint(this).paramTypeNames.first()
    val bannerBinding = freeConnectionsUpsellBannerRoot(this).className
    findClass { matcher { className(bindingClass) } }.single().fields
        .filter { it.typeName == bannerBinding }
        .only("upsellBanner field") { it.descriptor }
}

internal val vpnUserIsFreeUserFingerprint = findMethodDirect {
    val vpnUser = vpnUserClass(this)
    firstOf(
        "VpnUser.isFreeUser",
        {
            findMethod {
                matcher {
                    declaredClass(vpnUser.name)
                    returnType = "boolean"
                    paramCount = 0
                    addInvoke("Ljava/lang/Integer;->intValue()I")
                    opCodes(listOf(INVOKE_VIRTUAL, MOVE_RESULT, IF_NEZ), OpCodeMatchType.Contains)
                }
            }.only("VpnUser.isFreeUser (opcodes)") { it.descriptor }
        },
        {
            val toString = vpnUser.methods.single { it.name == "toString" && it.paramCount == 0 }
            val maxTier = toString.findFieldFromToString("maxTier=")
            vpnUser.methods.filter { m ->
                m.returnTypeName == "boolean" && m.paramCount == 0 && !Modifier.isStatic(m.modifiers) &&
                    m.usingFields.any { it.field.descriptor == maxTier.descriptor } &&
                    m.usingNumbers.all { it.longValue() in 0L..1L }
            }.only("VpnUser.isFreeUser (maxTier)") { it.descriptor }
        },
    )
}

internal val upsellCarouselStateClass = findClassDirect {
    dataClass("UpsellCarouselState(roundedServerCount=")
}

val upgradeCarouselFingerprints = findMethodListDirect {
    firstOf(
        "upgrade carousel",
        {
            val state = upsellCarouselStateClass(this)
            state.methods.filter { it.isConstructor }.flatMap { it.callers }
                .filter { it.className != state.name && !it.isConstructor }
                .distinctBy { it.descriptor }
                .ifEmpty { throw Exception("No UpsellCarouselState producer") }
        },
        {
            val isFreeUser = vpnUserIsFreeUserFingerprint(this)
            findMethod {
                matcher {
                    name = "invokeSuspend"
                    usingNumbers(100)
                    opCodes(listOf(DIV_INT_LIT8, MUL_INT_LIT8), OpCodeMatchType.Contains)
                }
            }.filter { m -> m.invokes.any { it.descriptor == isFreeUser.descriptor } }
                .ifEmpty { throw Exception("No carousel coroutine") }
        },
    )
}

internal val accountSettingsViewStateConstructor = findMethodDirect {
    dataClass("AccountSettingsViewState(userId=").primaryConstructor().also {
        if (it.paramTypeNames.getOrNull(5) != "boolean") throw Exception("Unexpected constructor ${it.descriptor}")
    }
}

private const val UPGRADE_ONBOARDING_ACTIVITY = "com.protonvpn.android.ui.planupgrade.UpgradeOnboardingDialogActivity"
private const val CONST_CLASS = 0x1c

val upgradeOnboardingStartFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            paramTypes("android.content.Context")
            addInvoke("Landroid/content/Context;->startActivity(Landroid/content/Intent;)V")
        }
    }.filter { m ->
        m.instructions.any { it.opcode == CONST_CLASS && it.classRef?.name == UPGRADE_ONBOARDING_ACTIVITY }
    }.also { if (it.isEmpty()) throw Exception("No UpgradeOnboardingDialogActivity launcher found") }
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
