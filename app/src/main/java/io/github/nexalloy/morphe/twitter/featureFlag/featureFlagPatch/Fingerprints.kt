package io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * FeatureSwitchesRepositoryImpl.getFeatureSwitchValue(key, logImpression): Any?
 *
 * Every typed accessor (getBoolean/getInt/getString/..., peek*, getFeatureSwitches) funnels
 * through this one method, so overriding its result covers all flag reads.
 * X currently keeps the names of the whole `com.x.featureswitches` package (12.30.0-prod.01).
 */
internal object FeatureSwitchValueFingerprint : Fingerprint(
    definingClass = "Lcom/x/featureswitches/FeatureSwitchesRepositoryImpl;",
    name = "getFeatureSwitchValue",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Z"),
)

private const val FEATURE_SWITCHES_PACKAGE = "com.x.featureswitches"

/** getBoolean/getInt/getLong/getFloat/getDouble/getString/getList + peek* variants = 14 today. */
private const val MIN_TYPED_ACCESSORS = 6

/**
 * Structural fallback for when R8 starts renaming the class or method:
 * an instance `(String, boolean) -> Object` method that many sibling typed accessors call.
 */
private fun DexKitBridge.structuralFeatureSwitchValue(): MethodData {
    fun search(scoped: Boolean) = findMethod {
        if (scoped) searchPackages(FEATURE_SWITCHES_PACKAGE)
        matcher {
            paramTypes("java.lang.String", "boolean")
            returnType("java.lang.Object")
        }
    }.filter { method ->
        if (Modifier.isStatic(method.modifiers)) return@filter false
        val typedAccessors = method.callers.filter { caller ->
            caller.className == method.className &&
                caller.paramTypeNames.firstOrNull() == "java.lang.String"
        }
        typedAccessors.size >= MIN_TYPED_ACCESSORS
    }

    val matches = search(scoped = true).ifEmpty { search(scoped = false) }
    return matches.singleOrNull() ?: throw Exception(
        "Expected one feature-switch value resolver, found ${matches.size}: " +
            matches.joinToString { it.descriptor },
    )
}

internal val featureSwitchValueFingerprint = findMethodDirect {
    runCatching { FeatureSwitchValueFingerprint.run() }.getOrNull()
        ?: structuralFeatureSwitchValue()
}
