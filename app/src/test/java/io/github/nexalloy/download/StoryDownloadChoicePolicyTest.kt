package io.github.nexalloy.revanced.instagram.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StoryDownloadChoicePolicyTest {

    @Test
    fun asksWhenPhotoAndMusicVideoAreBothAvailable() {
        assertEquals(
            StoryDownloadChoicePolicy.Decision.ASK,
            StoryDownloadChoicePolicy.decide(true, true, true)
        )
    }

    @Test
    fun downloadsPlainPhotoDirectly() {
        assertEquals(
            StoryDownloadChoicePolicy.Decision.DOWNLOAD_PHOTO,
            StoryDownloadChoicePolicy.decide(true, false, false)
        )
    }

    @Test
    fun downloadsVideoDirectlyWhenNoPhotoVariantExists() {
        assertEquals(
            StoryDownloadChoicePolicy.Decision.DOWNLOAD_VIDEO,
            StoryDownloadChoicePolicy.decide(false, true, true)
        )
    }

    @Test
    fun neverSilentlyTreatsVideoCoverAsVideo() {
        assertEquals(
            StoryDownloadChoicePolicy.Decision.ASK,
            StoryDownloadChoicePolicy.decide(true, false, true)
        )
    }

    @Test
    fun reportsMissingMedia() {
        assertEquals(
            StoryDownloadChoicePolicy.Decision.NOT_FOUND,
            StoryDownloadChoicePolicy.decide(false, false, false)
        )
    }
}
