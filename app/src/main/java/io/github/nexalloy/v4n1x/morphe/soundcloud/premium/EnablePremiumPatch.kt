package io.github.nexalloy.v4n1x.morphe.soundcloud.premium

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.enumValueOf
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.v4n1x.morphe.soundcloud.shared.singletonInstance

private val PREMIUM_FEATURES = setOf("offline_sync", "no_audio_ads", "hq_audio")

private const val UPSELL_TYPE_NONE_CLASS = "com.soundcloud.android.upsell.UpsellType\$None"

private fun zeroValueOf(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Byte::class.javaPrimitiveType -> 0.toByte()
    Short::class.javaPrimitiveType -> 0.toShort()
    Char::class.javaPrimitiveType -> 0.toChar()
    Float::class.javaPrimitiveType -> 0f
    Long::class.javaPrimitiveType -> 0L
    Double::class.javaPrimitiveType -> 0.0
    else -> null
}

val EnablePremium = patch(
    name = "Enable SoundCloud Go+",
    description = "Enables SoundCloud Go+ premium features, offline listening, HQ audio, and disables audio/visual ads.",
) {
    FeatureConstructorFingerprint.hookMethod {
        before { param ->
            val feature = param.args[0] as? String ?: return@before
            if (feature in PREMIUM_FEATURES) param.args[1] = true
        }
    }

    UserConsumerPlanConstructorFingerprint.hookMethod {
        before { param ->
            param.args[0] = "high_tier"
            param.args[4] = "go-plus"
            param.args[5] = "SoundCloud Go+"
        }
    }

    val getDowngradeTier = GetDowngradeTierFingerprint.method
    val highTier = getDowngradeTier.returnType.enumValueOf("HIGH")
        ?: error("Tier.HIGH not found")
    getDowngradeTier.hookMethod(XC_MethodReplacement.returnConstant(highTier))

    val noUpsell = classLoader.loadClass(UPSELL_TYPE_NONE_CLASS).singletonInstance()
    MapToPlanFingerprint.hookMethod(XC_MethodReplacement.returnConstant(noUpsell))

    ::adPlacementConfigurationConstructorsFingerprint.dexMethodList.forEach { dexMethod ->
        val ctor = dexMethod.toConstructor()
        val parameterTypes = ctor.parameterTypes
        val offset = if (parameterTypes.firstOrNull() == Int::class.javaPrimitiveType) 1 else 0
        val indices = (offset until offset + 3).filter { it < parameterTypes.size }
        if (indices.isEmpty()) return@forEach

        ctor.hookMethod {
            before { param ->
                indices.forEach { index ->
                    param.args[index] = zeroValueOf(parameterTypes[index])
                }
            }
        }
    }
}
