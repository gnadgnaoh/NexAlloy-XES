package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private val SHOP_CTAS = setOf("AFFILIATE_BANNER", "AFFILIATE_EYEBROW", "PRODUCT_TAGGING", "PRODUCT_TAGGING_V2", "STOREFRONT", "SHOP_SIMILAR")

val HideAffiliateLinks = patch(
    name = "Hide affiliate product links",
    description = "Removes the shopping cards that affiliate links add to reels, feed posts and comments, " +
        "plus Shop now cards on reels. The \"Commission eligible\" label stays.",
) {
    ::affiliateReelCardCheckFingerprint.method.hookMethod { after { param -> if (param.result == true) param.result = false } }
    ::affiliateFooterIdFingerprint.method.hookMethod { after { param -> param.result = null } }
    ::affiliateCommentCardFingerprint.method.hookMethod { after { param -> param.result = false } }
    ::affiliateCtaFilterFingerprint.method.hookMethod {
        after { param ->
            val shown = (param.result ?: param.args[0]) as? List<*> ?: return@after
            val kept = shown.filterNot { it is Enum<*> && it.name in SHOP_CTAS }
            if (kept.size != shown.size) param.result = kept
        }
    }
}
