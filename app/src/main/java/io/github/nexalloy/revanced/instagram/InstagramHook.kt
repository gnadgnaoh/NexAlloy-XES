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
    // ── Ghost Mode ───────────────────────────────────────────────────────────
    GhostInterceptor,             // blocks network: screenshot, viewOnce, storySeen
    GhostScreenshot,              // blocks screenshot notification
    GhostSeenState,               // blocks DM read receipts
    GhostTypingStatus,            // hides typing indicator
    // GhostViewOnce,                // prevents view-once consumption
    GhostViewStory,               // blocks story-seen pings
    GhostEphemeralKeep,           // blocks local deletion + server ping + expiry timer
    GhostPermanentView,           // view_mode → "permanent" (Piko logic, expireAt guard)
    // GhostReplayLimit,             // blocks replay counter + local store commit
    GhostViewLiveAnonymously,     // blocks live heartbeat endpoint
    // GhostViewStoryMentions,       // shows hidden story mentions
    ScreenshotPermission,
    // ── Mark As Read ──────────────────────────────────────────
    // GhostChannelMarkAsRead,       // manual read receipt for broadcast channels
    // GhostDMMarkAsRead,            // manual read receipt for DMs
    // ── Direct Messages ───────────────────────────────────────
    SaveDeletedMessages,          // anti-revoke: keeps unsent messages in the thread
    // ── Media download ────────────────────────────────────────
    ReelDownload,                 // Download entry in the reel overflow menu
    PostDownload,                 // Download entry in a post's three-dots menu
    StoryDownload,                // download button in the story viewer
    ProfilePictureDownload,       // full-resolution avatar
    SaveInstants,                 // long-press a received instant to save it
    UploadInstants,               // send a gallery picture as an instant
    CopyMediaLink,                // Copy link entry beside Download
    DownloadIntoUsernameFolders,  // one sub-folder per account
    TimestampDownloadedFilenames, // keeps both copies when saving a post twice
)
