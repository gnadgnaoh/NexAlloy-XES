package io.github.nexalloy.morphe.tiktok.externalbrowser

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val SPARK_THIRD_CONTEXT_CLASS = "com.bytedance.hybrid.spark.third.router.SparkThirdContext"
internal const val INTERACT_STICKER_CLASS = "com.ss.android.ugc.aweme.sticker.data.InteractStickerStruct"

/*
 * Ported from HushFeed "Open external links directly" (from icysymmetra / lyyako, GPL-3.0), with
 * the SparkThird redirect also present in kveld "In-App Browser Privacy Guard".
 *
 * Both repos match these by their exact parameter list. Here the strings and return type find the
 * method and the parameter is only required to be *somewhere* in the list, so a reordered or an
 * extra parameter does not lose the match; the hooks pick their arguments by type, not position.
 * Verified with DexKit on TikTok Asia 47.0.3 and Global 47.1.4 (base + df_a_dex split).
 */

/**
 * The third-party web router's open: takes the SparkThirdContext (url, seclinkConfig, defaultParams
 * keep their names) and starts the in-app browser activity (LX/040b.LIZIZ on Asia, LX/044I.LIZIZ
 * on Global). Its sibling for the popup container carries "ContainerId" but not the lancet string.
 */
val sparkThirdRouterOpenFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("ContainerId", "Context_startActivity_1"), StringMatchType.Equals)
                returnType = "void"
            }
        }.filter { SPARK_THIRD_CONTEXT_CLASS in it.paramTypeNames }
    }
}

/**
 * Tapping a story's link sticker: logs the external-website warning sheet and shows it
 * (LX/1DIH.LIZIZ on Asia, LX/1J8C.LIZLLL on Global). The log key is used nowhere else.
 */
val storyLinkSheetFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("external_website_security_pop_up_window_show"), StringMatchType.Equals)
                returnType = "void"
            }
        }.filter { INTERACT_STICKER_CLASS in it.paramTypeNames }
    }
}
