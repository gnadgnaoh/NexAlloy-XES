package io.github.nexalloy.morphe.tiktok.ghostmode

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

internal const val SS_HTTP_CALL_CLASS = "com.bytedance.retrofit2.SsHttpCall"

internal const val STORY_API_INTERFACE = "com.ss.android.ugc.aweme.story.api.IStoryApi"
internal const val PROFILE_VIEWER_API_INTERFACE = "com.ss.android.ugc.profile.business.ci.viewer.api.IProfileViewerApi"

internal val REPORTERS = mapOf(
    STORY_API_INTERFACE to listOf("reportStoryViewed", "reportUserInteraction", "reportStoryReveal"),
    PROFILE_VIEWER_API_INTERFACE to listOf("reportView"),
)

internal val REPORTER_WRAPPERS = listOf(
    "com.ss.android.ugc.aweme.story.api.StoryApi",
    "com.ss.android.ugc.profile.business.ci.viewer.api.ProfileViewerApiService",
)

internal val KNOWN_REPORT_PATHS = mapOf(
    "reportStoryViewed" to "/tiktok/story/view/report/v1",
    "reportUserInteraction" to "/tiktok/story/interaction/report/v1",
    "reportStoryReveal" to "/tiktok/story/reveal/report/v1",
    "reportView" to "/tiktok/user/profile/view_record/add/v1",
)

internal const val TYPING_STATUS_SENDER_CLASS = "com.ss.android.ugc.aweme.im.typingindicator.timer.TypingStatusSenderTimer"

internal val STORY_AWEME_TYPES = setOf(40, 45)

val awemeStatsSenderFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("aweme_stats_monitor"), StringMatchType.Equals)
                returnType = "void"
                modifiers(Modifier.STATIC)
            }
        }.filter { AWEME_CLASS in it.paramTypeNames }
    }
}
