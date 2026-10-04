package io.github.nexalloy.revanced.instagram

import io.github.nexalloy.revanced.instagram.ads.HideAds
import io.github.nexalloy.revanced.instagram.network.BlockNetwork
import io.github.nexalloy.revanced.instagram.tracking.SanitizeTrackingLinks
import io.github.nexalloy.revanced.instagram.ghost.GhostInterceptor
import io.github.nexalloy.revanced.instagram.ghost.GhostScreenshot
import io.github.nexalloy.revanced.instagram.ghost.GhostSeenState
import io.github.nexalloy.revanced.instagram.ghost.GhostTypingStatus
// import io.github.nexalloy.revanced.instagram.ghost.GhostViewOnce
import io.github.nexalloy.revanced.instagram.ghost.GhostViewStory
import io.github.nexalloy.revanced.instagram.ghost.GhostEphemeralKeep
import io.github.nexalloy.revanced.instagram.ghost.GhostPermanentView
// import io.github.nexalloy.revanced.instagram.ghost.GhostReplayLimit
import io.github.nexalloy.revanced.instagram.ghost.ScreenshotPermission
import io.github.nexalloy.revanced.instagram.ghost.GhostViewLiveAnonymously
// import io.github.nexalloy.revanced.instagram.ghost.GhostViewStoryMentions
// import io.github.nexalloy.revanced.instagram.ghost.markasread.GhostChannelMarkAsRead
// import io.github.nexalloy.revanced.instagram.ghost.markasread.GhostDMMarkAsRead
import io.github.nexalloy.revanced.instagram.dm.SaveDeletedMessages
import io.github.nexalloy.revanced.instagram.download.CopyMediaLink
import io.github.nexalloy.revanced.instagram.download.DownloadIntoUsernameFolders
import io.github.nexalloy.revanced.instagram.download.PostDownload
import io.github.nexalloy.revanced.instagram.download.ProfilePictureDownload
import io.github.nexalloy.revanced.instagram.download.ReelDownload
import io.github.nexalloy.revanced.instagram.download.SaveInstants
import io.github.nexalloy.revanced.instagram.download.StoryDownload
import io.github.nexalloy.revanced.instagram.download.TimestampDownloadedFilenames
import io.github.nexalloy.revanced.instagram.download.UploadInstants

val InstagramPatches = arrayOf(
    HideAds,
    SanitizeTrackingLinks,
    BlockNetwork,
    GhostInterceptor,
    GhostScreenshot,
    GhostSeenState,
    GhostTypingStatus,
    // GhostViewOnce,
    GhostViewStory,
    GhostEphemeralKeep,
    GhostPermanentView,
    // GhostReplayLimit
    GhostViewLiveAnonymously,
    // GhostViewStoryMentions,
    ScreenshotPermission,
    // GhostChannelMarkAsRead,
    // GhostDMMarkAsRead,
    SaveDeletedMessages
    ReelDownload,
    PostDownload,
    StoryDownload,
    ProfilePictureDownload,
    SaveInstants,
    UploadInstants,
    CopyMediaLink,
    DownloadIntoUsernameFolders,
    TimestampDownloadedFilenames,
)
