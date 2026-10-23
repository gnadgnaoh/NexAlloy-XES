package io.github.nexalloy.revanced.instagram

import io.github.nexalloy.revanced.instagram.ads.HideAds
import io.github.nexalloy.revanced.instagram.ads.HideBoostedReels
import io.github.nexalloy.revanced.instagram.ads.HidePaidPartnershipReels
import io.github.nexalloy.revanced.instagram.dm.DownloadVoiceMessages
import io.github.nexalloy.revanced.instagram.dm.ShowLastActive
import io.github.nexalloy.revanced.instagram.ghost.HideActiveStatus
import io.github.nexalloy.revanced.instagram.network.SkipSetupScreens
import io.github.nexalloy.revanced.instagram.privacy.OpenLinksInBrowser
import io.github.nexalloy.revanced.instagram.profile.FollowStatusChip
import io.github.nexalloy.revanced.instagram.profile.FollowsYou
import io.github.nexalloy.revanced.instagram.profile.SavedOnProfile
import io.github.nexalloy.revanced.instagram.comment.SaveCommentPhoto
import io.github.nexalloy.revanced.instagram.time.ShowStoryTime
import io.github.nexalloy.revanced.instagram.time.ShowPostTime
import io.github.nexalloy.revanced.instagram.time.ShowReelTime
import io.github.nexalloy.revanced.instagram.network.BlockNetwork
import io.github.nexalloy.revanced.instagram.tracking.SanitizeTrackingLinks
import io.github.nexalloy.revanced.instagram.ghost.GhostInterceptor
import io.github.nexalloy.revanced.instagram.ghost.GhostScreenshot
import io.github.nexalloy.revanced.instagram.ghost.GhostSeenState
import io.github.nexalloy.revanced.instagram.ghost.GhostTypingStatus
import io.github.nexalloy.revanced.instagram.ghost.GhostViewOnce
import io.github.nexalloy.revanced.instagram.ghost.GhostViewStory
import io.github.nexalloy.revanced.instagram.ghost.GhostEphemeralKeep
import io.github.nexalloy.revanced.instagram.ghost.GhostPermanentView
import io.github.nexalloy.revanced.instagram.ghost.ScreenshotPermission
import io.github.nexalloy.revanced.instagram.ghost.GhostViewLiveAnonymously
import io.github.nexalloy.revanced.instagram.story.StoryMentions
import io.github.nexalloy.revanced.instagram.story.UploadStoriesFullQuality
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
    HidePaidPartnershipReels,
    HideBoostedReels,
    SanitizeTrackingLinks,
    OpenLinksInBrowser,
    BlockNetwork,
    SkipSetupScreens,
    GhostInterceptor,
    GhostScreenshot,
    GhostSeenState,
    GhostTypingStatus,
    GhostViewOnce,
    GhostViewStory,
    GhostEphemeralKeep,
    GhostPermanentView,
    GhostViewLiveAnonymously,
    HideActiveStatus,
    StoryMentions,
    UploadStoriesFullQuality,
    ScreenshotPermission,
    SaveDeletedMessages,
    ShowLastActive,
    DownloadVoiceMessages,
    FollowsYou,
    FollowStatusChip,
    SavedOnProfile,
    SaveCommentPhoto,
    ShowPostTime,
    ShowReelTime,
    ShowStoryTime,
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
