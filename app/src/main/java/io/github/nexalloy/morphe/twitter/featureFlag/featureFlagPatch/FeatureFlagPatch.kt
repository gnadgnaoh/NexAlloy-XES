package io.github.nexalloy.morphe.twitter.featureFlag.featureFlagPatch

import io.github.nexalloy.patch

internal val featureFlagOverrides = mutableMapOf<String, Any>()

val FeatureFlagHook = patch(name = "<FeatureFlagHook>") {
    FeatureSwitchValueFingerprint.hookMethod {
        before { param ->
            val key = param.args.getOrNull(0) as? String ?: return@before
            featureFlagOverrides[key]?.let { param.result = it }
        }
    }
}
