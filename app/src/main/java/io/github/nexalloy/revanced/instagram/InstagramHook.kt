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
)
