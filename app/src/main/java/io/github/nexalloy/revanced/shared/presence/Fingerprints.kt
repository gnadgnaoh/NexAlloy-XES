package io.github.nexalloy.revanced.shared.presence

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal const val AVAILABLE_FIELD = "makeUserAvailableInForeground"
internal const val CLIENT_SETTINGS_TOPIC = "/set_client_settings"
internal const val AVAILABLE_KEY = "make_user_available_when_in_foreground"

private const val OP_CONST_STRING = 0x1a
private const val OP_CONST_STRING_JUMBO = 0x1b

val clientInfoWriterFingerprint = findMethodDirect {
    val field = findField { matcher { name = AVAILABLE_FIELD; type = "java.lang.Boolean" } }.single()
    val owner = field.declaredClassName
    field.readers.filter { m ->
        m.declaredClassName == owner && m.isMethod && !Modifier.isStatic(m.modifiers) &&
            m.returnTypeName == "void" && m.paramCount == 1
    }.distinctBy { it.descriptor }.single()
}

val clientSettingsPublishFingerprint = findMethodListDirect {
    findMethod {
        matcher { usingStrings(listOf(CLIENT_SETTINGS_TOPIC, AVAILABLE_KEY), StringMatchType.Equals) }
    }.mapNotNull { sender ->
        val code = sender.instructions
        val at = code.indexOfFirst {
            (it.opcode == OP_CONST_STRING || it.opcode == OP_CONST_STRING_JUMBO) && it.string == CLIENT_SETTINGS_TOPIC
        }
        if (at < 0) return@mapNotNull null
        code.drop(at + 1).take(16).firstNotNullOfOrNull { ins ->
            ins.methodRef?.takeIf { m ->
                val p = m.paramTypeNames
                val strings = p.count { it == "java.lang.String" }
                !m.declaredClassName.startsWith("java.") && !m.declaredClassName.startsWith("android.") &&
                    strings >= 1 && (strings >= 2 || "byte[]" in p)
            }
        }
    }.distinctBy { it.descriptor }.also { check(it.isNotEmpty()) { "no $CLIENT_SETTINGS_TOPIC publisher found" } }
}
