package io.github.nexalloy.morphe.tiktok.externalbrowser

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val SPARK_THIRD_CONTEXT_CLASS = "com.bytedance.hybrid.spark.third.router.SparkThirdContext"
internal const val INTERACT_STICKER_CLASS = "com.ss.android.ugc.aweme.sticker.data.InteractStickerStruct"

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
