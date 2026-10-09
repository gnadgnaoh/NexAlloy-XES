package io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

internal var forceTranslateEnabled = false
internal var showSensitiveMediaEnabled = false
internal var removePremiumUpsellStateEnabled = false

internal var logPostModelHooks = false

val TweetInfoHook = patch(name = "<TweetInfoHook>") {
    val isTranslatable = ::canonicalPostIsTranslatableField.field
    val isPossiblySensitive = ::canonicalPostIsPossiblySensitiveField.field
    val premiumUpsellArgs = ::availablePostPremiumUpsellField.field

    ::canonicalPostConstructorFingerprint.hookMethod {
        after { param ->
            val post = param.thisObject ?: return@after

            if (logPostModelHooks) {
                Logger.printInfo {
                    "[Twitter] TweetInfoHook/CanonicalPost: " +
                        "$PROP_IS_TRANSLATABLE=${isTranslatable.getBoolean(post)} " +
                        "$PROP_IS_POSSIBLY_SENSITIVE=${isPossiblySensitive.getBoolean(post)}"
                }
            }

            if (forceTranslateEnabled) {
                isTranslatable.setBoolean(post, true)
            }
            if (showSensitiveMediaEnabled) {
                isPossiblySensitive.setBoolean(post, false)
            }
        }
    }

    ::availablePostConstructorFingerprint.hookMethod {
        after { param ->
            val post = param.thisObject ?: return@after

            if (logPostModelHooks) {
                Logger.printInfo {
                    "[Twitter] TweetInfoHook/AvailablePost: premiumUpsell=${premiumUpsellArgs.get(post)}"
                }
            }
            if (!removePremiumUpsellStateEnabled) return@after

            premiumUpsellArgs.set(post, null)
        }
    }
}
