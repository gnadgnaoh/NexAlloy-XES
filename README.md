<div align="center">
  <h1>SexAlloy</h1>
  <a href="https://t.me/unofficalrevancedchat"><img alt="Telegram Channel" src="https://img.shields.io/badge/Telegram_Channel-blue.svg?logo=telegram&logoColor=white"></a>
  <a href="https://github.com/gnadgnaoh/SexAlloy"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/gnadgnaoh/SexAlloy"></a>  
  <br>
</div>


>[!IMPORTANT]  
> - This is **NOT an official Morphe or ReVanced project**, do not ask their developers for help.  
> - **Root access** is strictly **required** to use this module!
> - **Having issues?** Check the **[FAQ](https://github.com/NexAlloy/NexAlloy/wiki/Frequently-Asked-Questions)** before reporting.

## Downloads
- **Release build**: [Download](https://github.com/gnadgnaoh/SexAlloy/releases/latest)

## Supported Applications & Patches Overview

| Application | Package Name | Patches Applied |
| :--- | :--- | :--- |
| **AllTrails** | `com.alltrails.alltrails` | Enable Peak membership |
| **Facebook** | `com.facebook.katana` | Hide feed ads, Hide profile timeline ads, Hide search ads |
| **Gmail** | `com.google.android.gm` | Hide inbox ads |
| **Google (Discover)** | `com.google.android.googlequicksearchbox` | Filter Discover feed ads |
| **Google Photos** | `com.google.android.apps.photos` | Spoof Pixel XL (unlimited backup), Enable DCIM folder backup control |
| **Instagram** | `com.instagram.android` | Hide ads, Sanitize tracking links, Block network telemetry, Ghost mode (story, live, DM read receipts, typing status, screenshot notification block, keep ephemeral media, permanent view), Allow screenshots, Anti-revoke (save deleted DMs) |
| **Photomath** | `com.microblink.photomath` | Unlock Plus |
| **Proton VPN** | `ch.protonvpn.android` | Unlock VPN Plus UI, Remove change server delay, Disable telemetry |
| **Reddit** | `com.reddit.frontpage` | Hide ads, Sanitize sharing links |
| **SoundCloud** | `com.soundcloud.android` | Enable SoundCloud Go+ (HQ audio & ad-free), Disable analytics, Disable consent popup |
| **Strava** | `com.strava` | Unlock subscription features, Disable subscription suggestions |
| **Threads** | `com.instagram.barcelona` | Hide ads, Block ads & analytics, Sanitize tracking links |
| **TikTok** | `com.zhiliaoapp.musically` | Remove feed ads, Hide promoted music videos, Disable screen capture detection, Disable login requirement, Fix Google login, Hide CAPTCHA popups, Remove download watermark |
| **TikTok (Asia)** | `com.ss.android.ugc.trill` | Remove feed ads, Hide promoted music videos, Disable screen capture detection, Disable login requirement, Fix Google login, Hide CAPTCHA popups, Remove download watermark |
| **Twitter / X** | `com.twitter.android` | Remove ads, Hide recommendations, Direct URLs (unshorten `t.co`), Remove Premium upsell, Force enable translate, Show poll results, Hide banner, Disable blur effects |
| **YouTube** | `com.google.android.youtube` | Remove ads, SponsorBlock, Background playback, Sanitize share links, Navigation bar, Swipe controls, Video quality options, Custom playback speed, Copy video URL, External downloader integration, Hide layout components & action buttons, Disable Shorts resuming, Force original audio, Disable video codecs, Auto captions toggle, Alternative thumbnails (DeArrow), Bypass image region restrictions, In-app settings |
| **YouTube Music** | `com.google.android.apps.youtube.music` | Remove music ads, Background playback, Enable exclusive audio playback, Hide upgrade button & Premium labels, Sanitize sharing links, Media session crash fix, In-app settings |
| **Zalo** | `com.zing.zalo` | Hide feed ads (ZInstant), Skip feed ads binding, Filter feed ads, Hide story ads, Hide Zalo Video ads, Block Adtima ad & video ad requests, Disable ads tracking |

---

## Detailed Patches Breakdown

### AllTrails (`com.alltrails.alltrails`)
- **Enable Peak membership**: Unlocks Peak subscription features, offline trail maps, and navigation guides.

### Facebook (`com.facebook.katana`)
- **Hide Facebook ads**: Hides sponsored ad cards in main feed.
- **Hide profile timeline ads**: Removes sponsored posts when browsing user profile timelines.
- **Hide search ads**: Removes sponsored results from search page.

### Gmail (`com.google.android.gm`)
- **Hide ads**: Removes sponsored conversation rows and promotions rendered in inbox.

### Google (Discover) (`com.google.android.googlequicksearchbox`)
- **Filter Discover ads**: Removes promoted ad cards and sponsored articles from Google Discover feed.

### Google Photos (`com.google.android.apps.photos`)
- **Spoof features**: Spoofs device identity to Google Pixel XL to unlock unlimited original-quality Google Photos cloud storage backup.
- **Enable DCIM folders backup control**: Separates sub-folders within DCIM directory (e.g., screenshots) for individual backup toggle control.

### Instagram (`com.instagram.android`)
- **Hide ads**: Removes sponsored feed posts, story ads, and shopping promotions.
- **Sanitize tracking links**: Strips tracking parameters from shared URLs.
- **Block ads and analytics**: Blocks analytics tracking, logging, and telemetry endpoints.
- **Ghost mode**:
  - View stories anonymously without sending story-seen receipts.
  - Watch live streams anonymously without appearing in viewer list or sending heartbeat pings.
  - Read direct messages without triggering "seen" status.
  - Hide typing status indicator while composing messages.
  - Block screenshot notifications on disappearing photos/videos.
  - Keep ephemeral media from expiring and disappearing locally.
  - Force permanent view on view-once media.
- **Screenshot permission**: Allows taking screenshots in secure / protected views.
- **Save deleted messages**: Anti-revoke hook preserving deleted/unsent DMs in chat thread.

### Photomath (`com.microblink.photomath`)
- **Unlock Plus**: Unlocks Photomath Plus features including step-by-step problem solutions and textbook explanations.

### Proton VPN (`ch.protonvpn.android`)
- **Unlock VPN Plus**: Spoofs highest plan tier for UI unlocks while routing connections through free servers for server-side compatibility, skipping upgrade onboarding dialog.
- **Remove delay**: Removes cooldown delay when switching VPN servers.
- **Disable telemetry**: Blocks telemetry workers, event logging, and observability beacons.

### Reddit (`com.reddit.frontpage`)
- **Hide ads**: Removes promoted posts, banner promotions, and comment ads.
- **Sanitize sharing links**: Removes tracking parameters from shared post links.

### SoundCloud (`com.soundcloud.android`)
- **Enable SoundCloud Go+**: Enables high-quality streaming (HQ audio) and ad-free playback.
- **Disable analytics**: Blocks internal telemetry and analytic tracking handlers.
- **Disable consent popup**: Suppresses intrusive GDPR / cookie consent dialogs.

### Strava (`com.strava`)
- **Unlock subscription**: Unlocks subscription features including routes, segment efforts, and training analysis.
- **Disable subscription suggestions**: Removes upsell dialogs and subscription suggestions.

### Threads (`com.instagram.barcelona`)
- **Hide ads**: Hides sponsored posts from timeline.
- **Block ads and analytics**: Blocks telemetry, event logging, and tracking endpoints.
- **Sanitize tracking links**: Strips tracking parameters from shared post links.

### TikTok & TikTok (Asia) (`com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`)
- **Remove feed ads**: Removes sponsored video ads from "For You" and "Following" feeds.
- **Hide promoted-music videos**: Filters out sponsored music track promotions.
- **Disable screen capture detection**: Blocks app detection when taking screenshots or recording screen.
- **Disable login requirement**: Allows browsing, searching, and viewing profiles without login requirement prompt.
- **Fix Google login**: Fixes Google account authentication under patched environments.
- **Hide CAPTCHA popups**: Suppresses intrusive CAPTCHA challenges.
- **Remove download watermark**: Downloads videos without TikTok branding and author watermarks.

### Twitter / X (`com.twitter.android`)
- **Remove ads**: Removes sponsored posts and ads from timeline.
- **Hide recommendation items**: Hides recommended accounts, topics, and lists from feed.
- **No shortened URL**: Unshortens `t.co` URLs to load and copy direct links.
- **Remove premium upsell**: Removes Grok, subscription prompts, and X Premium upsells.
- **Force enable translate**: Always keeps tweet translation button enabled.
- **Show poll results**: Reveals poll results without requiring vote submission.
- **Hide banner**: Hides promotional and verification banners.
- **Disable blur effects**: Removes blur overlay on sensitive or NSFW media.

### YouTube (`com.google.android.youtube`)
- **Remove ads**: Removes home feed ads, search ads, banner ads, and in-video promotions.
- **SponsorBlock**: Skips sponsor segments, intros, outros, previews, and reminders.
- **Remove background playback restrictions**: Enables background and screen-off playback.
- **Sanitize sharing links**: Strips tracking query parameters (`si`, `feature`, etc.) from shared video links.
- **Navigation bar**: Customizes or hides navigation bar tabs (e.g. Shorts, Subscriptions).
- **Swipe controls**: Swipe gestures on player overlay for brightness and volume adjustments.
- **Video quality**: Remembers chosen video quality across playback sessions, provides quick quality button and advanced quality menu.
- **Playback speed**: Remembers custom playback speed presets with dedicated dialog button.
- **Copy video link**: Adds quick player button to copy video URL with or without timestamp.
- **Downloads**: Integrates external downloader apps (e.g. NewPipe, Seal) via player overlay button.
- **Hide layout components**: Hides comment sections, community posts, shorts shelf, related videos, info panels, and buttons.
- **Hide video action buttons**: Configurable toggles to hide like, dislike, share, download, or remix buttons.
- **Disable Shorts resuming on startup**: Prevents automatic Shorts playback on app launch.
- **Disable video codecs**: Option to disable VP9 or AV1 hardware codecs.
- **Auto captions**: Prevents automatic activation of captions on video launch.
- **Force original audio**: Disables forced auto-dubbed audio tracks and restores original audio track.
- **Alternative thumbnails**: Integrates DeArrow custom thumbnails and titles.
- **Bypass image region restrictions**: Bypasses image CDN blocking.
- **In-app settings**: Dedicated configuration menu integrated inside YouTube settings.

### YouTube Music (`com.google.android.apps.youtube.music`)
- **Remove ads**: Removes audio, video, and interstitial advertisements.
- **Remove background playback restrictions**: Keeps playback active when app minimized or screen turned off.
- **Enable exclusive audio playback**: Enables audio-only stream mode.
- **Hide upgrade button**: Removes "Upgrade" navigation button and "Get Music Premium" promotions.
- **Sanitize sharing links**: Strips tracking queries from shared track URLs.
- **Media session crash fix**: Prevents `MediaMetadata` large bitmap crashes on Android 12+.
- **In-app settings**: In-app preference menu for configuration.

### Zalo (`com.zing.zalo`)
- **Hide feed ads**: Hides ZInstant promoted cards in timeline.
- **Skip feed ads binding**: Prevents sponsored ad components from inflating into feed views.
- **Filter feed ads**: Drops sponsored content objects prior to UI rendering.
- **Hide story ads**: Blocks ads appearing between friend stories.
- **Hide Zalo Video ads**: Removes video promotions from short video feed.
- **Block Adtima ad requests**: Blocks outbound network requests to Adtima ad server endpoints.
- **Block Adtima video ad requests**: Blocks Adtima video ad calls.
- **Disable ads tracking**: Disables tracking telemetry and ad impression reporting.

---

## Supports
<a href="https://t.me/unofficalrevancedchat"><img alt="Telegram Channel" src="https://img.shields.io/badge/Telegram_Channel-blue.svg?logo=telegram&logoColor=white"></a>
or [Create an issue](https://github.com/NexAlloy/NexAlloy/issues/new/choose)

## ⭐ Credits

[DexKit](https://luckypray.org/DexKit/en/): a high-performance dex runtime parsing library.  
[Morphe](https://morphe.software): Transform Your Android Apps  
[ReVanced](https://revanced.app): Continuing the legacy of Vanced at [revanced.app](https://revanced.app)
