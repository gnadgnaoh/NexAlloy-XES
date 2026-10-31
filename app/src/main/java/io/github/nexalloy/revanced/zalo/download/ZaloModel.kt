package io.github.nexalloy.revanced.zalo.download

import java.lang.reflect.Modifier

internal object ZaloModel {
    private fun fields(type: Class<*>) = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filter { !Modifier.isStatic(it.modifiers) }

    fun valueOfType(host: Any, type: Class<*>): Any? = fields(host.javaClass)
        .firstOrNull { type.isAssignableFrom(it.type) }
        ?.let { runCatching { it.isAccessible = true; it.get(host) }.getOrNull() }

    fun holdsInt(host: Any, value: Int): Boolean = fields(host.javaClass)
        .filter { it.type == Int::class.javaPrimitiveType }
        .any { runCatching { it.isAccessible = true; it.getInt(host) == value }.getOrDefault(false) }

    fun reachable(host: Any, type: Class<*>): Any? {
        if (type.isInstance(host)) return host
        for (f in fields(host.javaClass)) {
            if (f.type.isPrimitive || f.type.name.startsWith("java.") || f.type.name.startsWith("android.")) continue
            val value = runCatching { f.isAccessible = true; f.get(host) }.getOrNull() ?: continue
            if (type.isInstance(value)) return value
            value.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType == type }
                ?.let { m -> runCatching { m.invoke(value) }.getOrNull()?.let { return it } }
        }
        return null
    }

    fun find(host: Any, type: Class<*>, depth: Int = 3): Any? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun go(node: Any, left: Int): Any? {
            if (type.isInstance(node)) return node
            if (left == 0 || !seen.add(node)) return null
            node.javaClass.declaredMethods.firstOrNull { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == type }
                ?.let { m -> runCatching { m.isAccessible = true; m.invoke(node) }.getOrNull()?.let { return it } }
            for (f in fields(node.javaClass)) {
                val name = f.type.name
                if (f.type.isPrimitive || name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) continue
                val value = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                go(value, left - 1)?.let { return it }
            }
            return null
        }
        return go(host, depth)
    }
}
