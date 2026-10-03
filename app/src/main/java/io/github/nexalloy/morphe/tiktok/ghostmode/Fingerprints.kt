package io.github.nexalloy.morphe.tiktok.ghostmode

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

/*
 * Ported from HushFeed "Ghost mode" (GPL-3.0; built on icysymmetra, follows eduardo3677-ai).
 * Verified with DexKit on TikTok Asia 47.0.3 and Global 47.1.4 (base + df_a_dex split).
 */

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

/** The bytedance Retrofit call: execute()/enqueue(Callback)/request() keep their names. */
internal const val SS_HTTP_CALL_CLASS = "com.bytedance.retrofit2.SsHttpCall"

/** Retrofit interfaces and the report methods on them (interface method names are kept). */
internal const val STORY_API_INTERFACE = "com.ss.android.ugc.aweme.story.api.IStoryApi"
internal const val PROFILE_VIEWER_API_INTERFACE = "com.ss.android.ugc.profile.business.ci.viewer.api.IProfileViewerApi"

internal val REPORTERS = mapOf(
    STORY_API_INTERFACE to listOf("reportStoryViewed", "reportUserInteraction", "reportStoryReveal"),
    PROFILE_VIEWER_API_INTERFACE to listOf("reportView"),
)

/** The kept-name classes wrapping those interfaces, where a suspend reporter is stopped. */
internal val REPORTER_WRAPPERS = listOf(
    "com.ss.android.ugc.aweme.story.api.StoryApi",
    "com.ss.android.ugc.profile.business.ci.viewer.api.ProfileViewerApiService",
)

/**
 * The paths those methods were annotated with on 47.0.3 / 47.1.4, used only when the annotations
 * cannot be read (the live annotations win, so a v1 -> v2 bump is followed automatically).
 */
internal val KNOWN_REPORT_PATHS = mapOf(
    "reportStoryViewed" to "/tiktok/story/view/report/v1",
    "reportUserInteraction" to "/tiktok/story/interaction/report/v1",
    "reportStoryReveal" to "/tiktok/story/reveal/report/v1",
    "reportView" to "/tiktok/user/profile/view_record/add/v1",
)

internal const val TYPING_STATUS_SENDER_CLASS = "com.ss.android.ugc.aweme.im.typingindicator.timer.TypingStatusSenderTimer"

/** Aweme.getAwemeType() of a story, and of a story shared into a chat. */
internal val STORY_AWEME_TYPES = setOf(40, 45)

/**
 * TikTok's play report sender (/aweme/v1/aweme/stats/), static and void. Every story view also sends
 * one with the story's aid and play_delta=1, which on its own is enough to put you on the viewer
 * list (HushFeed #39). LX/09gS.LIZIZ on 47.0.3, LX/09b8.LIZIZ on 47.1.3 per HushFeed; found by its
 * monitor string and an Aweme parameter, at any position.
 */
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
