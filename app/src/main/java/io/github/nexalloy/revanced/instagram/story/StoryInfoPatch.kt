package io.github.nexalloy.revanced.instagram.story

import android.app.AndroidAppHelper
import io.github.nexalloy.FindMethodFunc
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.CarouselIndex
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.InstantUploadHook
import io.github.nexalloy.revanced.instagram.download.MediaDownloadCore
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import io.github.nexalloy.revanced.instagram.download.ModuleLog
import io.github.nexalloy.revanced.instagram.download.StoryDownloadHook
import io.github.nexalloy.revanced.instagram.download.storyOptionBuilderMethods
import io.github.nexalloy.revanced.instagram.download.storyOptionClickMethods
import kotlin.reflect.KProperty0

val StoryInfoMenu = patch(
    name = "Story info, quiz answers and poll results",
    description = "Adds Story info to a story's three-dots menu, plus Quiz answer, Poll results and Slider results " +
        "on stories that have them. Reading them sends nothing, so it does not count as a vote or an answer.",
) {
    dependsOn(MediaDownloadCore)
    val keys: List<Pair<String, KProperty0<FindMethodFunc>>> = listOf(
        "taken_at" to ::storyTakenAtFingerprint,
        "expiring_at" to ::storyExpiringAtFingerprint,
        "story_edited_at" to ::storyEditedAtFingerprint,
        "is_story_edited" to ::storyIsEditedFingerprint,
        "music_metadata" to ::storyMusicMetadataFingerprint,
        "story_music_stickers" to ::storyMusicStickersFingerprint,
        "story_music_lyric_stickers" to ::storyMusicLyricStickersFingerprint,
        "story_locations" to ::storyLocationsFingerprint,
        "story_hashtags" to ::storyHashtagsFingerprint,
        "reshared_story_media_author" to ::storyResharedAuthorFingerprint,
        "story_feed_media" to ::storyFeedMediaFingerprint,
        "story_app_attribution" to ::storyAppAttributionFingerprint,
        "accessibility_caption" to ::storyAltTextFingerprint,
        "original_width" to ::storyOriginalWidthFingerprint,
        "original_height" to ::storyOriginalHeightFingerprint,
        "has_audio" to ::storyHasAudioFingerprint,
        "story_countdowns" to ::storyCountdownsFingerprint,
        "story_questions" to ::storyQuestionsFingerprint,
        "story_quizs" to ::storyQuizzesFingerprint,
        "story_polls" to ::storyPollsFingerprint,
        "story_sliders" to ::storySlidersFingerprint,
        "story_prompts" to ::storyPromptsFingerprint,
        "sponsor_tags" to ::storySponsorTagsFingerprint,
        "is_paid_partnership" to ::storyPaidPartnershipFingerprint,
        "coauthor_producers" to ::storyCoauthorsFingerprint,
    )
    keys.forEach { (key, fp) ->
        runCatching { fp.method.apply { isAccessible = true } }
            .onSuccess { StoryInfo.getters[key] = it }
            .onFailure { ModuleLog.line("(NA|StoryInfo) no getter for $key") }
    }
    check(StoryInfo.getters.isNotEmpty()) { "no story getters" }

    fun labels(): List<Pair<Int, String>> {
        val app = AndroidAppHelper.currentApplication()
        return listOf(R.string.ig_btn_story_info, R.string.ig_btn_quiz_answer, R.string.ig_btn_poll_results, R.string.ig_btn_slider_results)
            .map { it to I18n.t(app, it) }
    }

    ::storyOptionBuilderMethods.dexMethodList.filter { it.isMethod }.map { it.toMethod() }
        .filter { StoryDownloadHook.buildsOptionLabels(it) }
        .forEach { builder ->
            builder.hookMethod {
                after { param ->
                    runCatching {
                        val all = labels()
                        if (all.any { it.second.isEmpty() }) return@runCatching
                        val media = MediaResolver.mediaOfReelItem(StoryDownloadHook.findReelItem(param))
                        val add = ArrayList<String>()
                        add += all[0].second
                        if (media != null) {
                            if (StoryInfo.hasQuiz(media)) add += all[1].second
                            if (StoryInfo.hasPoll(media)) add += all[2].second
                            if (StoryInfo.hasSlider(media)) add += all[3].second
                        }
                        @Suppress("UNCHECKED_CAST")
                        when (val original = param.result) {
                            is Array<*> -> {
                                if (original.any { it != null && it !is CharSequence }) return@runCatching
                                if (original.any { it is CharSequence && all[0].second.contentEquals(it) }) return@runCatching
                                param.result = (original.toList() + add).map { it as CharSequence? }.toTypedArray()
                            }
                            is MutableList<*> -> {
                                if (original.any { it != null && it !is CharSequence }) return@runCatching
                                if (original.any { it is CharSequence && all[0].second.contentEquals(it) }) return@runCatching
                                (original as MutableList<Any?>).addAll(add)
                            }
                        }
                    }.onFailure { ModuleLog.line("(NA|StoryInfo) menu failed: $it") }
                }
            }
        }

    ::storyOptionClickMethods.dexMethodList.filter { it.isMethod }.map { it.toMethod() }
        .filter { it.parameterCount > 0 }
        .forEach { click ->
            click.hookMethod {
                before { param ->
                    val tapped = (param.args.lastOrNull { it is CharSequence } as CharSequence?)?.toString() ?: return@before
                    val which = labels().indexOfFirst { it.second == tapped }
                    if (which < 0) return@before
                    param.result = null
                    val found = StoryDownloadHook.findContext(param)
                    val ctx = CarouselIndex.activityOf(found) ?: InstantUploadHook.currentActivity() ?: found ?: return@before
                    val media = MediaResolver.mediaOfReelItem(StoryDownloadHook.findReelItem(param))
                    if (media == null) {
                        ModuleLog.line("(NA|StoryInfo) media not found")
                        return@before
                    }
                    MediaSaver.ui {
                        when (which) {
                            0 -> StoryInfo.showInfo(ctx, media)
                            1 -> StoryInfo.showQuiz(ctx, media)
                            2 -> StoryInfo.showPolls(ctx, media)
                            else -> StoryInfo.showSliders(ctx, media)
                        }
                    }
                }
            }
        }
}
