package io.github.nexalloy.morphe.twitter.misc.blur

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.opcodeEnum
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.InstructionData
import org.luckypray.dexkit.result.MethodData

private const val HAZE_PACKAGE = "dev.chrisbanes.haze"
private const val HAZE_UPDATE_EFFECT_MARKER = "HazeEffectNode-updateEffect"
private const val BOOLEAN_TYPE = "boolean"
private const val VOID_TYPE = "void"

private val BOOLEAN_PARAMETERS = listOf(BOOLEAN_TYPE)

private fun DexKitBridge.hazeEffectNodeClass(): ClassData {
    val scoped = findClass {
        searchPackages(HAZE_PACKAGE)
        matcher {
            usingStrings(listOf(HAZE_UPDATE_EFFECT_MARKER), StringMatchType.Equals)
        }
    }

    val matches = scoped.ifEmpty {
        findClass {
            matcher {
                usingStrings(listOf(HAZE_UPDATE_EFFECT_MARKER), StringMatchType.Equals)
            }
        }
    }

    if (matches.size != 1) {
        throw Exception(
            "Expected one Haze node update marker, found ${matches.size}: " +
                matches.joinToString { it.name },
        )
    }

    return matches.single()
}

private fun InstructionData.booleanFieldAccess(
    opcode: Opcode,
    owner: String,
    receiverRegister: Int? = null,
    valueRegister: Int? = null,
): String? {
    if (opcodeEnum != opcode || registerCount < 2) return null
    val field = fieldRef ?: return null
    if (field.className != owner || field.typeName != BOOLEAN_TYPE) return null
    if (receiverRegister != null && register(1) != receiverRegister) return null
    if (valueRegister != null && register(0) != valueRegister) return null
    return field.descriptor
}

private fun MethodData.hasFlag(flag: AccessFlags) = (modifiers and flag.modifier) != 0

private fun isHazeBlurEnabledSetter(method: MethodData, owner: String): Boolean {
    if (
        method.hasFlag(AccessFlags.STATIC) ||
        !method.hasFlag(AccessFlags.PUBLIC) ||
        !method.hasFlag(AccessFlags.FINAL) ||
        method.returnTypeName != VOID_TYPE ||
        method.paramTypeNames != BOOLEAN_PARAMETERS
    ) {
        return false
    }

    val instructions = runCatching { method.instructions }.getOrNull() ?: return false

    val stateReads = instructions.mapNotNull { instruction ->
        instruction
            .booleanFieldAccess(opcode = Opcode.IGET_BOOLEAN, owner = owner)
            ?.let { field -> Triple(field, instruction.register(1), instruction.register(0)) }
    }
    if (stateReads.size != 1) return false

    val (stateFieldDescriptor, receiverRegister, stateRegister) = stateReads.single()
    val inputRegister = receiverRegister + 1

    val inputWriteCount = instructions.count { instruction ->
        instruction.booleanFieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            owner = owner,
            receiverRegister = receiverRegister,
            valueRegister = inputRegister,
        ) == stateFieldDescriptor
    }
    if (inputWriteCount != 1) return false

    val comparesStateWithInput = instructions.any { instruction ->
        val opcode = instruction.opcodeEnum
        if (opcode != Opcode.IF_EQ && opcode != Opcode.IF_NE || instruction.registerCount < 2) {
            return@any false
        }
        val registerA = instruction.register(0)
        val registerB = instruction.register(1)
        (registerA == inputRegister && registerB == stateRegister) ||
            (registerA == stateRegister && registerB == inputRegister)
    }
    if (!comparesStateWithInput) return false

    val invalidationWriteCount = instructions.count { instruction ->
        instruction
            .booleanFieldAccess(
                opcode = Opcode.IPUT_BOOLEAN,
                owner = owner,
                receiverRegister = receiverRegister,
            )
            ?.let { field -> field != stateFieldDescriptor } == true
    }

    return invalidationWriteCount == 1 &&
        instructions.count { it.opcodeEnum == Opcode.RETURN_VOID } == 1
}

internal val hazeBlurEnabledSetterFingerprint = findMethodDirect {
    val nodeClass = hazeEffectNodeClass()
    val owner = nodeClass.name

    val setters = nodeClass.methods.filter { isHazeBlurEnabledSetter(it, owner) }
    if (setters.size != 1) {
        throw Exception(
            "Expected one Haze blur-enabled setter in $owner, found ${setters.size}: " +
                setters.joinToString { it.descriptor },
        )
    }

    setters.single()
}
