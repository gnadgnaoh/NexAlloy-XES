package io.github.nexalloy.revanced.instagram.download

object FeatureFlags {
    @JvmField var enableReelDownload = false
    @JvmField var enablePostDownload = false
    @JvmField var enableStoryDownload = false
    @JvmField var enableProfileDownload = false
    @JvmField var saveInstants = false
    @JvmField var uploadInstants = false
    @JvmField var copyMediaLink = false
    @JvmField var downloaderCustomPath: String = ""
    @JvmField var downloaderUsernameFolder = false
    @JvmField var downloaderAddTimestamp = false
}
