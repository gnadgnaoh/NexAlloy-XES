package io.github.nexalloy.revanced.instagram.download

/** Pure decision policy for Story download variants. */
internal object StoryDownloadChoicePolicy {

    enum class Decision {
        NOT_FOUND,
        DOWNLOAD_PHOTO,
        DOWNLOAD_VIDEO,
        ASK,
    }

    fun decide(hasImage: Boolean, hasVideo: Boolean, modelSaysVideo: Boolean): Decision {
        if (hasImage && hasVideo) return Decision.ASK
        if (hasVideo) return Decision.DOWNLOAD_VIDEO

        // If Instagram identifies the Story as video but only a cover was resolved,
        // require an explicit photo choice instead of silently saving it as an MP4.
        if (hasImage) return if (modelSaysVideo) Decision.ASK else Decision.DOWNLOAD_PHOTO
        return Decision.NOT_FOUND
    }
}
