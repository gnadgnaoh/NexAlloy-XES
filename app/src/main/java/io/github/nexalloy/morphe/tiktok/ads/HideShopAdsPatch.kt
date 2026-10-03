package io.github.nexalloy.morphe.tiktok.ads

import app.morphe.extension.shared.Logger
import io.github.nexalloy.getIntFieldOrNull
import io.github.nexalloy.patch

private const val TAG = "[TikTok Shop]"

/**
 * Hides the TikTok Shop cards TikTok inserts into the For You feed (product, price, voucher,
 * "Not interested" / "Claim" buttons). They are not sponsored videos - Aweme.isAd() is false -
 * so [RemoveFeedAds] keeps them; this patch can be switched on and off on its own.
 *
 * Two independent layers (see the "TikTok Shop" region of Fingerprints.kt for how a card reaches
 * the feed):
 *  1. Request: the Shop provider's request builder returns no card type, so TikTok does not ask
 *     the server for Shop cards in the first place.
 *  2. Feed: every feed hook of [TikTokFeedFilterHooks] (For You response, inserted items, cold-start
 *     cache) drops an Aweme whose cardInsertInfo has a Shop card type. This catches cards the
 *     server sends anyway, e.g. through a server-side card rule or the previous session's cache.
 */
val HideShopAds = patch(
    name = "Hide TikTok Shop ads",
    description = "Hides the TikTok Shop product cards (price, voucher, \"Not interested\" and " +
            "\"Claim\" buttons) inserted into the For You feed. Independent of Remove feed ads, " +
            "which only removes sponsored videos.",
) {
    AwemeAdFilter.hideShopCards = true
    dependsOn(TikTokFeedFilterHooks)

    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    optional("ecCardRequest") {
        val builders = ::ecCardRequestFingerprints.dexMethodList.realMatches()
        check(builders.isNotEmpty()) { "IFeedEcCardService request builder not found" }
        builders.forEach {
            it.hookMethod {
                // `after`, not `before`: the original still runs so the card types it would have
                // requested (native + Lynx EC cards) are learned for the feed filter.
                after { param ->
                    val requests = param.result as? List<*> ?: return@after
                    requests.forEach { request ->
                        request?.getIntFieldOrNull("cardType")?.let(AwemeAdFilter.shopCardTypes::add)
                    }
                    if (requests.isNotEmpty()) param.result = ArrayList<Any?>(0)
                }
            }
        }
    }

    optional("ecSearchCardTypes") {
        val getters = ::ecSearchCardTypeGetterFingerprints.dexMethodList.realMatches()
        check(getters.isNotEmpty()) { "IEcSearchFeedCardService card type getter not found" }
        getters.forEach {
            it.hookMethod {
                after { param ->
                    (param.result as? List<*>)?.forEach { type ->
                        val cardType = when (type) {
                            is Number -> type.toInt()
                            is String -> type.trim().toIntOrNull()
                            else -> null
                        }
                        cardType?.let(AwemeAdFilter.shopCardTypes::add)
                    }
                }
            }
        }
    }

    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
