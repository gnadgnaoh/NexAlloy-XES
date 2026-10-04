package io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.opcodeEnum
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import io.github.nexalloy.morphe.twitter.utils.X_MODELS_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.X_URT_PACKAGE
import io.github.nexalloy.morphe.twitter.utils.dataClassToString

private val KOTLIN_TO_STRING_FLAGS = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)

internal object CanonicalPostToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = KOTLIN_TO_STRING_FLAGS,
    custom = { dataClassToString("CanonicalPost", X_MODELS_PACKAGE) },
)

internal object AvailablePostToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = KOTLIN_TO_STRING_FLAGS,
    custom = { dataClassToString("AvailablePost", X_URT_PACKAGE) },
)

private const val INT = "int"
private const val BOOLEAN = "boolean"

private const val ACC_SYNTHETIC = 0x1000

private val FIELD_WRITE_OPCODES = setOf(
    Opcode.IPUT,
    Opcode.IPUT_WIDE,
    Opcode.IPUT_OBJECT,
    Opcode.IPUT_BOOLEAN,
    Opcode.IPUT_BYTE,
    Opcode.IPUT_CHAR,
    Opcode.IPUT_SHORT,
)

private const val CANONICAL_POST_SERIAL = "com.x.models.CanonicalPost"

internal const val PROP_IS_TRANSLATABLE = "isTranslatable"
internal const val PROP_IS_POSSIBLY_SENSITIVE = "isPossiblySensitive"

private const val PREMIUM_UPSELL_PACKAGE = "com.x.premium.upsell."

private val ADD_ELEMENT_PARAMS = listOf("java.lang.String", BOOLEAN)

private fun DexKitBridge.serialElementNames(serialName: String): List<String> {
    val candidates = findMethod {
        matcher {
            name = "<clinit>"
            addEqString(serialName)
        }
    }

    val clinit = candidates.singleOrNull()
        ?: candidates.firstOrNull {
            it.instructions.firstOrNull { i -> i.string != null }?.string == serialName
        }
        ?: throw Exception(
            "no serializer <clinit> for $serialName (${candidates.size} candidates: " +
                candidates.joinToString { it.descriptor } + ")",
        )

    val instructions = clinit.instructions
    val start = instructions.indexOfFirst { it.string == serialName }
    if (start < 0) throw Exception("serial name $serialName missing from ${clinit.descriptor}")
    val tail = instructions.drop(start + 1)

    val declaredCount = tail
        .takeWhile { it.methodRef == null }
        .firstNotNullOfOrNull { it.literal }
        ?.toInt()

    val names = mutableListOf<String>()
    var pending: String? = null
    for (insn in tail) {
        val string = insn.string
        if (string != null) {
            pending = string
            continue
        }
        val ref = insn.methodRef ?: continue
        if (pending != null && ref.returnTypeName == "void" && ref.paramTypeNames == ADD_ELEMENT_PARAMS) {
            names += pending
        }
        pending = null
    }

    if (declaredCount != null && declaredCount != names.size) {
        throw Exception(
            "$serialName declares $declaredCount elements but ${names.size} were read: $names",
        )
    }
    return names
}

private fun ClassData.primaryConstructor(): MethodData {
    val constructors = methods.filter { it.isConstructor }

    val candidates = constructors
        .filterNot { (it.modifiers and ACC_SYNTHETIC) != 0 }
        .filterNot { ctor -> ctor.invokes.any { it.isConstructor && it.className == name } }
        .filterNot { it.paramTypeNames.firstOrNull() == INT }

    return candidates.maxByOrNull { it.paramCount }
        ?: throw Exception(
            "no primary constructor on $name; candidates had " +
                constructors.joinToString { "${it.paramCount} params" },
        )
}

private fun MethodData.assignedFieldNames(owner: String): List<String> =
    instructions
        .filter { it.opcodeEnum in FIELD_WRITE_OPCODES }
        .mapNotNull { it.fieldRef }
        .filter { it.declaredClassName == owner }
        .map { it.name }
        .distinct()

private fun DexKitBridge.canonicalPostBooleanField(property: String): FieldData {
    val canonicalPost = CanonicalPostToStringFingerprint.run().declaredClass!!

    val properties = serialElementNames(CANONICAL_POST_SERIAL)
    val index = properties.indexOf(property)
    if (index < 0) {
        throw Exception("$CANONICAL_POST_SERIAL has no property '$property'; it has $properties")
    }

    val fieldNames = canonicalPost.primaryConstructor().assignedFieldNames(canonicalPost.name)
    if (fieldNames.size != properties.size) {
        throw Exception(
            "${canonicalPost.name}: constructor assigns ${fieldNames.size} fields but " +
                "$CANONICAL_POST_SERIAL has ${properties.size} properties",
        )
    }
    val name = fieldNames.getOrNull(index) ?: throw Exception(
        "$property is property #$index but ${canonicalPost.name}'s constructor assigns only " +
            "${fieldNames.size} fields",
    )

    return canonicalPost.fields.singleOrNull { it.name == name && it.typeName == BOOLEAN }
        ?: throw Exception("$property resolved to non-boolean field '$name' on ${canonicalPost.name}")
}


internal val canonicalPostConstructorFingerprint = findMethodDirect {
    CanonicalPostToStringFingerprint.run().declaredClass!!.primaryConstructor()
}

internal val availablePostConstructorFingerprint = findMethodDirect {
    AvailablePostToStringFingerprint.run().declaredClass!!.primaryConstructor()
}

internal val canonicalPostIsTranslatableField = findFieldDirect {
    canonicalPostBooleanField(PROP_IS_TRANSLATABLE)
}

internal val canonicalPostIsPossiblySensitiveField = findFieldDirect {
    canonicalPostBooleanField(PROP_IS_POSSIBLY_SENSITIVE)
}

internal val availablePostPremiumUpsellField = findFieldDirect {
    val availablePost = AvailablePostToStringFingerprint.run().declaredClass!!
    val matches = availablePost.fields.filter { it.typeName.startsWith(PREMIUM_UPSELL_PACKAGE) }

    matches.singleOrNull() ?: throw Exception(
        "expected one $PREMIUM_UPSELL_PACKAGE field on ${availablePost.name}, found " +
            "${matches.size}: ${matches.joinToString { it.descriptor }}",
    )
}
