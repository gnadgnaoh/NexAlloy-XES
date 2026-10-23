package io.github.nexalloy.revanced.messenger.ads

import io.github.nexalloy.patch

val HideInboxPromotions = patch(
    name = "Hide inbox promotions",
    description = "Hides promotion banners in your chat list.",
) {
    ::inboxPromotionGatesFingerprint.dexMethodList.forEach { it.hookMethod { before { p -> p.result = false } } }
}

val HideChatPromotions = patch(
    name = "Hide chat promotions",
    description = "Hides promotion banners inside conversations.",
) {
    ::chatPromotionGatesFingerprint.dexMethodList.forEach { it.hookMethod { before { p -> p.result = false } } }
}

val HideAdBannersInBusinessChats = patch(
    name = "Hide ad banners in business chats",
    description = "In a chat you started from a business's ad, hides the banner that shows that ad.",
) {
    ::adContextBannerGatesFingerprint.dexMethodList.forEach { it.hookMethod { before { p -> p.result = false } } }
}
