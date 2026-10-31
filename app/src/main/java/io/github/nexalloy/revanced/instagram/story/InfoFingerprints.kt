package io.github.nexalloy.revanced.instagram.story

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val STORY_MEDIA = "com.instagram.feed.media.Media"

private fun DexKitBridge.mediaKeyGetter(key: String): MethodData {
    fun usable(m: MethodData) = !Modifier.isStatic(m.modifiers) && m.isMethod && m.returnTypeName != "void"
    val byHash = findMethod { matcher { declaredClass(STORY_MEDIA); paramCount = 0; usingNumbers(key.hashCode()) } }.filter(::usable)
    val found = byHash.ifEmpty {
        findMethod { matcher { declaredClass(STORY_MEDIA); paramCount = 0; usingEqStrings(key) } }.filter(::usable)
    }
    return found.minByOrNull { it.name.length } ?: error("no Media getter for $key")
}

val storyTakenAtFingerprint = findMethodDirect { mediaKeyGetter("taken_at") }
val storyExpiringAtFingerprint = findMethodDirect { mediaKeyGetter("expiring_at") }
val storyEditedAtFingerprint = findMethodDirect { mediaKeyGetter("story_edited_at") }
val storyIsEditedFingerprint = findMethodDirect { mediaKeyGetter("is_story_edited") }
val storyMusicMetadataFingerprint = findMethodDirect { mediaKeyGetter("music_metadata") }
val storyMusicStickersFingerprint = findMethodDirect { mediaKeyGetter("story_music_stickers") }
val storyMusicLyricStickersFingerprint = findMethodDirect { mediaKeyGetter("story_music_lyric_stickers") }
val storyLocationsFingerprint = findMethodDirect { mediaKeyGetter("story_locations") }
val storyHashtagsFingerprint = findMethodDirect { mediaKeyGetter("story_hashtags") }
val storyResharedAuthorFingerprint = findMethodDirect { mediaKeyGetter("reshared_story_media_author") }
val storyFeedMediaFingerprint = findMethodDirect { mediaKeyGetter("story_feed_media") }
val storyAppAttributionFingerprint = findMethodDirect { mediaKeyGetter("story_app_attribution") }
val storyAltTextFingerprint = findMethodDirect { mediaKeyGetter("accessibility_caption") }
val storyOriginalWidthFingerprint = findMethodDirect { mediaKeyGetter("original_width") }
val storyOriginalHeightFingerprint = findMethodDirect { mediaKeyGetter("original_height") }
val storyHasAudioFingerprint = findMethodDirect { mediaKeyGetter("has_audio") }
val storyCountdownsFingerprint = findMethodDirect { mediaKeyGetter("story_countdowns") }
val storyQuestionsFingerprint = findMethodDirect { mediaKeyGetter("story_questions") }
val storyQuizzesFingerprint = findMethodDirect { mediaKeyGetter("story_quizs") }
val storyPollsFingerprint = findMethodDirect { mediaKeyGetter("story_polls") }
val storySlidersFingerprint = findMethodDirect { mediaKeyGetter("story_sliders") }
val storyPromptsFingerprint = findMethodDirect { mediaKeyGetter("story_prompts") }
val storySponsorTagsFingerprint = findMethodDirect { mediaKeyGetter("sponsor_tags") }
val storyPaidPartnershipFingerprint = findMethodDirect { mediaKeyGetter("is_paid_partnership") }
val storyCoauthorsFingerprint = findMethodDirect { mediaKeyGetter("coauthor_producers") }
