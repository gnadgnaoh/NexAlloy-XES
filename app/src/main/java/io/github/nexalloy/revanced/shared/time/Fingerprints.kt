package io.github.nexalloy.revanced.shared.time

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Modifier

internal const val CONTEXT = "android.content.Context"
internal const val STRING = "java.lang.String"
private const val RESOURCES = "android.content.res.Resources"
private const val MONTH_DAY = "MMMM d"

internal fun DexKitBridge.relativeTimeFormatter(): ClassData = findMethod {
    matcher { returnType = STRING; usingEqStrings(MONTH_DAY) }
}.filter { m ->
    Modifier.isStatic(m.modifiers) && m.paramTypeNames.firstOrNull() == RESOURCES && m.paramTypeNames.count { it == "double" } >= 2
}.map { it.declaredClass!! }.distinctBy { it.name }.single()

val relativeTimeFormatterMethodsFingerprint = findMethodListDirect {
    relativeTimeFormatter().methods.filter { m ->
        !Modifier.isStatic(m.modifiers) && m.returnTypeName == STRING &&
            (m.paramTypeNames == listOf(CONTEXT, "double") || m.paramTypeNames == listOf(CONTEXT, "long"))
    }
}
