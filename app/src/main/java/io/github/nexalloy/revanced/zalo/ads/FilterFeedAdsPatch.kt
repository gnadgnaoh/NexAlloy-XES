package io.github.nexalloy.revanced.zalo.ads

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.ZaloFeedKeys
import org.json.JSONObject

private fun JSONObject.isSponsoredFeedItem(): Boolean {
    if (has(ZaloFeedKeys.TRACK_ADS)) return true

    val photos = optJSONArray(ZaloFeedKeys.LIST_PHOTOS) ?: return false
    for (i in 0 until photos.length()) {
        val photo = photos.optJSONObject(i) ?: continue
        if (photo.optString(ZaloFeedKeys.ADS_DATA).isNotEmpty()) return true
        if (photo.optString(ZaloFeedKeys.ADS_ACTION).isNotEmpty()) return true
    }
    return false
}

val FilterFeedAds = patch(
    name = "Filter feed ads before parsing",
    description = "Drops sponsored items from the Nhật ký feed at the JSON parse layer, " +
            "before any view is created. Experimental — turn this off first if posts go " +
            "missing from your feed.",
    use = false,
) {
    ::feedItemPreCheckFingerprint.hookMethod {
        after { param ->
            if (param.result != true) return@after

            val item = param.args.getOrNull(0) as? JSONObject ?: return@after
            if (item.isSponsoredFeedItem()) {
                param.result = false
                Logger.printDebug { "[Zalo] dropped sponsored feed item" }
            }
        }
    }
}
