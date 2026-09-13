package io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

internal var forceTranslateEnabled = false
internal var showSensitiveMediaEnabled = false
internal var removePremiumUpsellStateEnabled = false

internal var logPostModelHooks = false

private const val CP_IS_TRANSLATABLE = 14
private const val CP_IS_POSSIBLY_SENSITIVE = 31

private const val AP_PREMIUM_UPSELL_ARGS = 38

val TweetInfoHook = patch(name = "<TweetInfoHook>") {
    CanonicalPostConstructorFingerprint.hookMethod {
        before { param ->
            if (logPostModelHooks) {
                Logger.printInfo {
                    "[Twitter] TweetInfoHook/CanonicalPost: " +
                        "isTranslatable=${param.args[CP_IS_TRANSLATABLE]} " +
                        "isPossiblySensitive=${param.args[CP_IS_POSSIBLY_SENSITIVE]}"
                }
            }
            if (!forceTranslateEnabled && !showSensitiveMediaEnabled) return@before

            if (forceTranslateEnabled) {
                param.args[CP_IS_TRANSLATABLE] = true
            }
            if (showSensitiveMediaEnabled) {
                param.args[CP_IS_POSSIBLY_SENSITIVE] = false
            }
        }
    }

    AvailablePostConstructorFingerprint.hookMethod {
        before { param ->
            if (logPostModelHooks) {
                Logger.printInfo {
                    "[Twitter] TweetInfoHook/AvailablePost: premiumUpsell=${param.args[AP_PREMIUM_UPSELL_ARGS]}"
                }
            }
            if (!removePremiumUpsellStateEnabled) return@before

            param.args[AP_PREMIUM_UPSELL_ARGS] = null
        }
    }
}
