package io.github.nexalloy.revanced.instagram.download

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Resolves Instagram's legacy and modern media dictionary models independently. */
internal object MediaModelResolver {

    const val MUTABLE_DICT_CLASS = "com.instagram.feed.media.MutableMediaDictIntf"
    const val LIVE_TREE_DICT_CLASS = "com.instagram.feed.media.LiveTreeMediaDict"

    internal class Result(
        @JvmField val mutableDictClass: Class<*>?,
        @JvmField val liveTreeDictClass: Class<*>?,
        @JvmField val listCandidates: List<Method>,
    )

    fun resolve(classLoader: ClassLoader, discoveredDictClass: Class<*>? = null): Result {
        val mutable = tryLoad(classLoader, MUTABLE_DICT_CLASS)
        var liveTree = tryLoad(classLoader, LIVE_TREE_DICT_CLASS)
        if (liveTree == null) liveTree = discoveredDictClass
        val candidates = ArrayList<Method>()
        val seen = HashSet<String>()

        if (mutable != null) {
            addListMethods(mutable, candidates, seen)
            for (superInterface in mutable.interfaces) {
                if (isInstagramModelClass(superInterface.name)) {
                    addListMethods(superInterface, candidates, seen)
                }
            }
        }

        // Never nest this lookup under MutableMediaDictIntf. Recent Instagram versions
        // can remove the legacy interface while retaining the concrete Pando model.
        if (liveTree != null) addListMethods(liveTree, candidates, seen)
        return Result(mutable, liveTree, candidates)
    }

    fun findDictionary(media: Any?, model: Result?, maxDepth: Int): Any? {
        if (media == null || model == null || maxDepth < 0) return null
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val found = findObjectOfType(media, model.liveTreeDictClass, maxDepth, visited)
        if (found != null) return found
        visited.clear()
        return findObjectOfType(media, model.mutableDictClass, maxDepth, visited)
    }

    fun findObjectOfType(root: Any?, target: Class<*>?, maxDepth: Int): Any? {
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        return findObjectOfType(root, target, maxDepth, visited)
    }

    private fun findObjectOfType(
        obj: Any?,
        target: Class<*>?,
        depth: Int,
        visited: MutableSet<Any>,
    ): Any? {
        if (obj == null || target == null || depth < 0 || !visited.add(obj)) return null
        if (target.isInstance(obj)) return obj

        if (obj is Map<*, *>) {
            if (depth == 0) return null
            for (value in obj.values) {
                val nested = findObjectOfType(value, target, depth - 1, visited)
                if (nested != null) return nested
            }
            return null
        }
        if (obj is Iterable<*>) {
            if (depth == 0) return null
            for (value in obj) {
                val nested = findObjectOfType(value, target, depth - 1, visited)
                if (nested != null) return nested
            }
            return null
        }
        if (obj is Array<*>) {
            if (depth == 0) return null
            for (value in obj) {
                val nested = findObjectOfType(value, target, depth - 1, visited)
                if (nested != null) return nested
            }
            return null
        }

        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field: Field in cls.declaredFields) {
                try {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val value = field.get(obj) ?: continue
                    if (target.isInstance(value)) return value
                    val traversable = isInstagramModelClass(value.javaClass.name) ||
                        value is Iterable<*> || value is Map<*, *> || value is Array<*>
                    if (depth == 0 || !traversable) continue
                    val nested = findObjectOfType(value, target, depth - 1, visited)
                    if (nested != null) return nested
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }

        // Some Pando models expose their backing node only through a no-arg getter.
        // Probe model-returning getters after the field walk, with the same depth limit.
        if (depth > 0 && isInstagramModelClass(obj.javaClass.name)) {
            cls = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (method in cls.declaredMethods) {
                    if (method.parameterCount != 0 || Modifier.isStatic(method.modifiers)) continue
                    val returnType = method.returnType
                    if (returnType.isPrimitive || returnType == String::class.java ||
                        returnType == Class::class.java
                    ) continue
                    if (!target.isAssignableFrom(returnType) &&
                        !isInstagramModelClass(returnType.name) &&
                        !Collection::class.java.isAssignableFrom(returnType) &&
                        !Map::class.java.isAssignableFrom(returnType)
                    ) continue
                    try {
                        method.isAccessible = true
                        val value = method.invoke(obj)
                        val nested = findObjectOfType(value, target, depth - 1, visited)
                        if (nested != null) return nested
                    } catch (ignored: Throwable) {
                    }
                }
                cls = cls.superclass
            }
        }
        return null
    }

    private fun tryLoad(classLoader: ClassLoader, name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (ignored: Throwable) {
        null
    }

    private fun addListMethods(type: Class<*>, out: MutableList<Method>, seen: MutableSet<String>) {
        for (method in type.declaredMethods) {
            if (method.parameterCount != 0 ||
                !List::class.java.isAssignableFrom(method.returnType)
            ) continue
            val key = method.declaringClass.name + '#' + method.name +
                ':' + method.returnType.name
            if (!seen.add(key)) continue
            try {
                method.isAccessible = true
            } catch (ignored: Throwable) {
            }
            out.add(method)
        }
    }

    private fun isInstagramModelClass(name: String): Boolean =
        name.startsWith("X.") ||
            name.startsWith("com.instagram.") ||
            name.startsWith("com.facebook.") ||
            name.startsWith("io.github.nexalloy.revanced.instagram.download.")
}
