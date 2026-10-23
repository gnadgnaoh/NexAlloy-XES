package io.github.nexalloy.revanced.instagram.network

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val STRING = "java.lang.String"
private const val URI = "android.net.Uri"
private const val SCREEN_CONFIG = "com.instagram.bloks.hosting.IgBloksScreenConfig"

internal const val ERROR_PING_ENDPOINT = "https://b-www.facebook.com/mobile/extra_data_collector/"

private const val OP_IGET_BOOLEAN = 0x55
private const val OP_IF_EQZ = 0x38
private const val OP_MOVE_RESULT_OBJECT = 0x0c
private val OP_PUT_OBJECT = setOf(0x5b, 0x69)

val analyticsEndpointBuilderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = STRING
            paramTypes(STRING, "boolean")
            usingEqStrings("https://", "/pigeon_nest", "/logging_client_events")
        }
    }.single { Modifier.isStatic(it.modifiers) }
}

val lacrimaPingSendersFingerprint = findMethodListDirect {
    findMethod {
        matcher { name = "run"; returnType = "void"; paramCount = 0; usingEqStrings(ERROR_PING_ENDPOINT) }
    }
}

val reportAddressBuilderFingerprint = findMethodDirect {
    findMethod {
        matcher { returnType = URI; paramTypes("java.lang.String[]"); usingEqStrings("https", "b-www.facebook.com") }
    }.single { Modifier.isStatic(it.modifiers) }
}

val reportAddressReadersFingerprint = findMethodListDirect {
    val builder = reportAddressBuilderFingerprint()
    val fields = findMethod { matcher { addInvoke(builder.descriptor) } }.flatMap { caller ->
        val code = caller.instructions
        code.indices.filter { code[it].methodRef?.descriptor == builder.descriptor }.mapNotNull { at ->
            val text = code.getOrNull(at + 3)?.takeIf {
                code.getOrNull(at + 1)?.opcode == OP_MOVE_RESULT_OBJECT &&
                    code.getOrNull(at + 2)?.methodRef?.name == "toString" && it.opcode == OP_MOVE_RESULT_OBJECT
            }?.register(0) ?: return@mapNotNull null
            (at + 4 until minOf(code.size, at + 12)).map { code[it] }
                .firstOrNull { it.opcode in OP_PUT_OBJECT && it.register(0) == text }
                ?.fieldRef?.takeIf { it.typeName == STRING }
        }
    }.distinctBy { it.descriptor }
    fields.flatMap { field ->
        findMethod { matcher { addUsingField(field.descriptor, UsingType.Read) } }
    }.filter { it.isMethod }.distinctBy { it.descriptor }
}

val mqttSettingsConstructorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            paramTypes("org.json.JSONObject")
            usingEqStrings("php_sandbox_host_name", "mqtt-mini.facebook.com")
        }
    }.single()
}

private fun DexKitBridge.eventSettingsClass(): String =
    findMethod { matcher { name = "<init>"; usingEqStrings("non-streamable events", "stateful events") } }
        .map { it.declaredClassName }.distinct().single()

private fun MethodData.firstSwitchRead(config: String): Int? =
    instructions.indexOfFirst { it.opcode == OP_IGET_BOOLEAN && it.fieldRef?.declaredClassName == config }.takeIf { it >= 0 }

val eventStreamSwitchFieldFingerprint = findFieldDirect {
    val config = eventSettingsClass()
    val found = findMethod { matcher { usingEqStrings("event.streaming.eligible") } }.single()
    val step = if (found.firstSwitchRead(config) != null) found
    else findMethod { matcher { addInvoke(found.descriptor) } }.single()
    val code = step.instructions
    val read = step.firstSwitchRead(config) ?: error("no switch of $config read in ${step.descriptor}")
    val branch = code.getOrNull(read + 1)
    check(branch?.opcode == OP_IF_EQZ && branch.register(0) == code[read].register(0)) {
        "${step.descriptor} doesn't branch on $config's switch right after reading it"
    }
    code[read].fieldRef!!
}

val eventSettingsConstructorsFingerprint = findMethodListDirect {
    val config = eventSettingsClass()
    findMethod { matcher { declaredClass(config); name = "<init>" } }
}

val bloksScreenOpenersFingerprint = findMethodListDirect {
    val opener = findMethod { matcher { usingEqStrings("BKDataFetcher.fetch") } }
        .map { it.declaredClassName }.distinct().single()
    findMethod {
        matcher { declaredClass(opener); returnType = "void"; paramTypes("android.content.Context", SCREEN_CONFIG) }
    }.filter { !Modifier.isStatic(it.modifiers) }
}

val bloksScreenPresenterFingerprint = findMethodDirect {
    findMethod {
        matcher { returnType = "void"; usingEqStrings("FragmentActivity is required to open CDS bottom sheet") }
    }.single { m ->
        val p = m.paramTypeNames
        Modifier.isStatic(m.modifiers) && p.size == 6 && p[0] == "android.content.Context" && p[2] == SCREEN_CONFIG && p[5] == "int"
    }
}
