package io.github.nexalloy.morphe.twitter.misc.blur

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.opcodeEnum
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.InstructionData
import org.luckypray.dexkit.result.MethodData

private const val HAZE_PACKAGE = "dev.chrisbanes.haze"
private const val RESOLVED_BLUR_STYLE_MARKER = "ResolvedHazeBlurStyle("
private const val BOOLEAN_TYPE = "boolean"
private const val CONSTRUCTOR = "<init>"
private const val TO_STRING = "toString"

private class HazeFingerprintException(message: String) : Exception(message)

private fun DexKitBridge.findHazeClassesUsing(marker: String, match: StringMatchType): List<ClassData> {
    val scoped = findClass {
        searchPackages(HAZE_PACKAGE)
        matcher { usingStrings(listOf(marker), match) }
    }
    return scoped.ifEmpty {
        findClass { matcher { usingStrings(listOf(marker), match) } }
    }
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

private fun MethodData.safeInstructions(): List<InstructionData>? =
    runCatching { instructions }.getOrNull()

private fun MethodData.firstBooleanParamTargetField(owner: String): String? {
    if (!isConstructor || paramTypeNames.firstOrNull() != BOOLEAN_TYPE) return null
    val instructions = safeInstructions() ?: return null

    val stores = instructions.filter { it.opcodeEnum == Opcode.IPUT_BOOLEAN && it.registerCount >= 2 }
        .mapNotNull { insn ->
            val receiver = insn.register(1)
            insn.booleanFieldAccess(
                opcode = Opcode.IPUT_BOOLEAN,
                owner = owner,
                receiverRegister = receiver,
                valueRegister = receiver + 1,
            )
        }
        .distinct()

    return stores.singleOrNull()
}

private fun ClassData.toStringReadsBooleanField(fieldDescriptor: String): Boolean {
    val toString = methods.firstOrNull {
        it.name == TO_STRING && it.paramTypeNames.isEmpty() && it.returnTypeName == "java.lang.String"
    } ?: return false
    val instructions = toString.safeInstructions() ?: return false
    return instructions.any {
        it.booleanFieldAccess(opcode = Opcode.IGET_BOOLEAN, owner = name) == fieldDescriptor
    }
}

private fun DexKitBridge.resolvedBlurStyleConstructorOrNull(): MethodData? {
    val styleClasses = findHazeClassesUsing(RESOLVED_BLUR_STYLE_MARKER, StringMatchType.Contains)
    if (styleClasses.isEmpty()) return null

    val candidates = styleClasses.flatMap { cls ->
        val ctors = findMethod {
            matcher {
                declaredClass(cls.name)
                name = CONSTRUCTOR
            }
        }
        ctors.filter { ctor ->
            val field = ctor.firstBooleanParamTargetField(cls.name) ?: return@filter false
            cls.toStringReadsBooleanField(field)
        }
    }.distinctBy { it.descriptor }

    if (candidates.size != 1) {
        throw HazeFingerprintException(
            "Expected one ResolvedHazeBlurStyle primary constructor, found ${candidates.size} " +
                "(classes: ${styleClasses.joinToString { it.name }}): " +
                candidates.joinToString { it.descriptor },
        )
    }
    return candidates.single()
}

internal val hazeBlurEnabledFingerprint = findMethodDirect {
    resolvedBlurStyleConstructorOrNull()
        ?: throw HazeFingerprintException("ResolvedHazeBlurStyle not present")
}
