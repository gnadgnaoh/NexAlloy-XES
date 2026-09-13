package io.github.nexalloy.revanced.zalo.ads

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.revanced.zalo.ZaloFeedKeys
import org.luckypray.dexkit.query.enums.StringMatchType

val feedAdsBindFingerprint = fingerprint {
    strings("zinstantMediaType")
    returns("V")
}

val feedAdsLayoutHeightFingerprint = fingerprint {
    classFingerprint(feedAdsBindFingerprint)
    name("getZInstantLayoutHeight")
    returns("I")
}

val feedItemPreCheckFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(listOf(ZaloFeedKeys.PRE_CHECK), StringMatchType.Equals)
            returnType = "boolean"
            paramTypes(listOf("org.json.JSONObject"))
        }
    }.filter { candidate ->
        candidate.invokes.any { it.className == "org.json.JSONArray" && it.name == "get" }
    }.single()
}

val storyAdsBindFingerprint = fingerprint {
    strings("click_story_ad_cta", "click_name_story_ad", "send_message_story_ad")
    returns("V")
}

val outstreamAdsLayoutFingerprint = fingerprint {
    strings("outstream_ads_close", "outstream_ads_skip", "skip_ads_second")
    returns("V")
}

val adsTemplateLayoutFingerprint = fingerprint {
    strings("cta_ad_show")
    returns("V")
}

val adsNativeLayoutFingerprint = fingerprint {
    name("getStartTimeShow")
    returns("J")
    parameters()
    classMatcher { className(".AdsNativeLayout", StringMatchType.EndsWith) }
}

val advertisingItemFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            name = "getAdvertisingContent"
            returnType = "com.zing.zalo.shortvideo.domain.entity.content.Content"
            paramCount = 0
        }
    }
}
