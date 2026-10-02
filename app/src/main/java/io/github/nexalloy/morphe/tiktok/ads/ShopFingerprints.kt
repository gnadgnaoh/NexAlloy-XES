package io.github.nexalloy.morphe.tiktok.ads

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/*
 * TikTok Shop cards in the For You feed ("EC card": product, price, voucher, "Not interested" /
 * "Claim" buttons). Verified with DexKit against TikTok Asia 47.0.3 (base + df_a_dex) and TikTok
 * Global 47.1.4.
 *
 * How such a card gets into the feed:
 *  1. Before each For You request, the card-insert platform asks every provider which card types
 *     it wants. The Shop provider is IFeedEcCardService: it returns CardTypeRequest(4) - the
 *     native EC card - plus the Lynx EC card types listed in the `feed_ec_lynx_card_config`
 *     setting.
 *  2. The server answers with the card inside FeedItemList.items: an Aweme whose
 *     `cardInsertInfo.cardType` is the requested type.
 *
 * Anchors: the two service interfaces are looked up by class through ServiceManager / SPI, so
 * their names are kept; FeedCardInsertData, CardTypeRequest, Aweme.getCardInsertInfo() and
 * CardInsertInfo.getCardType() are Gson / proto models. Method names (LIZ, LIZJ, ...) and the
 * implementation classes are not relied on: implementations are found through the interface.
 */

internal const val FEED_EC_CARD_SERVICE =
    "com.ss.android.ugc.aweme.ecommerce.ug.feedeccard.service.IFeedEcCardService"
internal const val EC_SEARCH_FEED_CARD_SERVICE = "com.ss.android.ugc.aweme.feedcard.IEcSearchFeedCardService"
internal const val FEED_CARD_INSERT_DATA_CLASS = "com.ss.android.ugc.feed.platform.cardinsert.data.FeedCardInsertData"

/** `CardTypeRequest(4, ...)`, added by IFeedEcCardService on every request it lets through. */
internal const val NATIVE_EC_CARD_TYPE = 4

private val MethodData.isConcreteMethod
    get() = !isConstructor && modifiers and (Modifier.ABSTRACT or Modifier.STATIC) == 0

/**
 * The Shop provider's request builder, on every IFeedEcCardService implementation: the method
 * that takes the card-insert state (FeedCardInsertData) and returns the CardTypeRequests to send
 * with the next For You request. Recognised by that input type and the List result, so the
 * argument order does not matter.
 */
val ecCardRequestFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                declaredClass { addInterface(FEED_EC_CARD_SERVICE) }
                returnType = "java.util.List"
            }
        }.filter { it.isConcreteMethod && FEED_CARD_INSERT_DATA_CLASS in it.paramTypeNames }
    }
}

/**
 * The card types of Shop *search* cards ("keyword + magnifier" cards), read from the
 * `ec_feed_preload_image_card_list` setting: the only no-argument List getter of
 * IEcSearchFeedCardService (the other no-argument method returns a Map).
 */
val ecSearchCardTypeGetterFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                declaredClass { addInterface(EC_SEARCH_FEED_CARD_SERVICE) }
                returnType = "java.util.List"
                paramCount = 0
            }
        }.filter { it.isConcreteMethod }
    }
}
