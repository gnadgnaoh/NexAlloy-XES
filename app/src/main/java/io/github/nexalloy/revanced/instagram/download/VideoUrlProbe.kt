package io.github.nexalloy.revanced.instagram.download

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Resolves which constructor argument of `VideoUrlImpl` is the url, and which field keeps it,
 * by building throw-away instances instead of reading obfuscated names or register layouts.
 *
 * - The url argument is the String whose `null` makes the constructor throw its
 *   "... VideoUrl object with null url" error.
 * - The url field is the String field holding a marker value passed as that argument.
 */
internal object VideoUrlProbe {
    private const val MARKER = "https://scontent.cdninstagram.com/nexalloy-probe.mp4"
    private const val OTHER = "nexalloy-probe"

    private fun defaults(types: Array<Class<*>>): Array<Any?> = Array(types.size) { i ->
        when (types[i]) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Boolean::class.javaPrimitiveType -> false
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> '\u0000'
            String::class.java -> OTHER
            else -> null
        }
    }

    fun urlArgIndex(ctor: Constructor<*>): Int {
        ctor.isAccessible = true
        val types = ctor.parameterTypes
        val stringArgs = types.indices.filter { types[it] == String::class.java }
        val rejectingNull = stringArgs.filter { index ->
            val args = defaults(types).also { it[index] = null }
            try {
                ctor.newInstance(*args)
                false
            } catch (e: InvocationTargetException) {
                e.cause?.message?.contains("null url") == true
            }
        }
        return rejectingNull.singleOrNull()
            ?: throw IllegalStateException("${ctor.declaringClass.name}: url argument not identified ($rejectingNull)")
    }

    fun urlField(ctor: Constructor<*>, urlArg: Int): Field {
        val args = defaults(ctor.parameterTypes).also { it[urlArg] = MARKER }
        val instance = ctor.newInstance(*args)
        var cls: Class<*>? = ctor.declaringClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type != String::class.java || Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                if (field.get(instance) == MARKER) return field
            }
            cls = cls.superclass
        }
        throw IllegalStateException("${ctor.declaringClass.name}: url field not found")
    }
}
