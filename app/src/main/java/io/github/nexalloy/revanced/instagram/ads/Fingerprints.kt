package io.github.nexalloy.revanced.instagram.ads

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

val adInsertGateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            usingStrings("injection_orchestrator_position_passed_with_insertion_but_not_impression_")
        }
    }.single()
}

private const val MEDIA = "com.instagram.feed.media.Media"
private const val USER_SESSION = "com.instagram.common.session.UserSession"
private const val LIST = "java.util.List"

val reelsPageCallbackFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 1
            usingStrings(listOf("ClipsItemsNetworkCallback_onSuccess"), StringMatchType.Contains)
        }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val reelsPageSourceMethodsFingerprint = findMethodListDirect {
    val callback = reelsPageCallbackFingerprint()
    val pageInterface = callback.paramTypeNames.single()
    val names = callback.instructions.mapNotNull { it.methodRef }
        .filter { it.declaredClassName == pageInterface && it.paramTypeNames == listOf(USER_SESSION) && it.returnTypeName == LIST }
        .map { it.name }.toSet()
    check(names.isNotEmpty()) { "callback asks $pageInterface for no page" }
    findClass { matcher { addInterface(pageInterface) } }.flatMap { cls ->
        cls.methods.filter { it.name in names && it.paramTypeNames == listOf(USER_SESSION) && it.returnTypeName == LIST }
    }.filter { !Modifier.isAbstract(it.modifiers) }
}

val mediaPaidPartnershipGetterFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(MEDIA); paramCount = 0; returnType = "java.lang.Boolean"; usingNumbers("is_paid_partnership".hashCode()) }
    }.single()
}

val mediaBoostedStatusGetterFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(MEDIA); paramCount = 0; returnType = "java.lang.String"; usingNumbers("boosted_status".hashCode()) }
    }.single()
}
