package io.github.nexalloy.morphe.twitter.timeline.banner

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Constructor

/** Properties of UrtShowInstructionsState that make the "new posts" pill appear. */
private val PILL_PROPERTIES = listOf("isEligibleToShowPill", "isPillCurrentlyVisible")

/** 12.2x - 12.30 layout (List, Z, Z, Z, J, Z): used only if runtime probing is impossible. */
private val LEGACY_SHAPE = listOf(
    List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
)
private val LEGACY_INDICES = mapOf("isEligibleToShowPill" to 1, "isPillCurrentlyVisible" to 5)

private fun defaultArg(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    Short::class.javaPrimitiveType -> 0.toShort()
    Byte::class.javaPrimitiveType -> 0.toByte()
    Char::class.javaPrimitiveType -> '\u0000'
    String::class.java, CharSequence::class.java -> ""
    else -> when {
        type.isAssignableFrom(ArrayList::class.java) -> emptyList<Any?>()
        type.isAssignableFrom(LinkedHashSet::class.java) -> emptySet<Any?>()
        type.isAssignableFrom(LinkedHashMap::class.java) -> emptyMap<Any?, Any?>()
        else -> null
    }
}

/** Kotlin primary constructor: not synthetic, no trailing DefaultConstructorMarker. */
private fun Class<*>.primaryConstructor(): Constructor<*> =
    declaredConstructors
        .filterNot { it.isSynthetic }
        .filterNot { ctor ->
            ctor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        .maxByOrNull { it.parameterCount }
        ?: throw Exception("$name has no primary constructor")

/**
 * Resolves which constructor argument feeds each property by building throw-away instances with a
 * single boolean set to true and reading the data-class `toString()` back. Independent of field
 * order, obfuscated names and the bytecode layout of toString.
 */
private fun probeBooleanArgs(ctor: Constructor<*>, properties: List<String>): Map<String, Int> {
    ctor.isAccessible = true
    val types = ctor.parameterTypes
    val found = mutableMapOf<String, Int>()

    types.forEachIndexed { index, type ->
        if (type != Boolean::class.javaPrimitiveType) return@forEachIndexed
        val args = Array(types.size) { defaultArg(types[it]) }
        args[index] = true
        val text = ctor.newInstance(*args).toString()
        properties.forEach { property ->
            if (Regex("""[(\s]${Regex.escape(property)}=true[,)]""").containsMatchIn(text)) {
                if (found.put(property, index) != null) {
                    throw Exception("$property matched more than one constructor argument")
                }
            }
        }
    }

    val missing = properties - found.keys
    if (missing.isNotEmpty()) throw Exception("Could not locate $missing in ${ctor.declaringClass.name}")
    return found
}

val HideBanner = patch(
    name = "Hide Banner",
    description = "Hides the \"new posts\" banner shown at the top of the timeline.",
) {
    val stateClass = ShowInstructionsStateToStringFingerprint.declaredClass
    val ctor = stateClass.primaryConstructor()

    val indices = runCatching { probeBooleanArgs(ctor, PILL_PROPERTIES) }.getOrElse { probeError ->
        if (ctor.parameterTypes.toList() != LEGACY_SHAPE) {
            throw Exception("HideBanner: probing failed and constructor shape changed", probeError)
        }
        Logger.printInfo(
            { "HideBanner: probing failed, using legacy indices" },
            probeError as? Exception ?: Exception(probeError),
        )
        LEGACY_INDICES
    }.values.toIntArray()

    Logger.printInfo { "HideBanner: ${stateClass.name} pill args=${indices.joinToString()}" }

    ctor.hookMethod {
        before { param -> indices.forEach { param.args[it] = false } }
    }
}
