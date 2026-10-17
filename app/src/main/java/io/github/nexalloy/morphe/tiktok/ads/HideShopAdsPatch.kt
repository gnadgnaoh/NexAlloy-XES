package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.getIntFieldOrNull
import io.github.nexalloy.patch
import io.github.nexalloy.morphe.tiktok.shared.realMatches

private const val TAG = "[TikTok Shop]"

val HideShopAds = patch(
    name = "Hide TikTok Shop ads",
    description = "Hides the TikTok Shop product cards (price, voucher, \"Not interested\" and " +
            "\"Claim\" buttons) inserted into the For You feed. Independent of Remove feed ads, " +
            "which only removes sponsored videos.",
) {
    AwemeAdFilter.hideShopCards = true
    dependsOn(TikTokFeedFilterHooks)

    run { // ecCardRequest
        val builders = ::ecCardRequestFingerprints.dexMethodList.realMatches()
        check(builders.isNotEmpty()) { "IFeedEcCardService request builder not found" }
        builders.forEach {
            it.hookMethod {
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

    run { // ecSearchCardTypes
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

}
