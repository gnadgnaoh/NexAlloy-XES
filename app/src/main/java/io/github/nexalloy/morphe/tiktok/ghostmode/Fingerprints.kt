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

internal const val AWEME_STATS_API_CLASS = "com.ss.android.ugc.aweme.feed.api.AwemeStatsApi"
internal const val AWEME_STATS_SERVICE_CLASS = "$AWEME_STATS_API_CLASS\$AwemeStatsService"

internal const val STATS_REPORT_METHOD = "awemeStatsReport"
internal const val FAST_STATS_REPORT_METHOD = "awemeFastStatsReport"

internal val KNOWN_STATS_PATHS = mapOf(
    STATS_REPORT_METHOD to "/aweme/v1/aweme/stats/",
    FAST_STATS_REPORT_METHOD to "/aweme/v1/fast/stats/",
)

internal val FOLLOWING_STATUSES = setOf(1, 2)

internal const val PLAY_DELTA_FIELD = "play_delta"
internal val FOLLOW_STATUS_FIELDS = listOf("follow_status", "origin_follow_status")

private const val STATS_BATCH_SETTING = "basic_vv_batch_size"

val awemeStatsDispatchFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf(STATS_BATCH_SETTING), StringMatchType.Equals)
                returnType = "void"
                paramCount = 1
                modifiers(Modifier.STATIC)
            }
        }.filter { it.paramTypeNames.single() != "java.util.List" }
    }
}

val awemeStatsParamsFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf(PLAY_DELTA_FIELD) + FOLLOW_STATUS_FIELDS, StringMatchType.Equals)
                returnType = "java.util.HashMap"
                paramCount = 0
            }
        }
    }
}
