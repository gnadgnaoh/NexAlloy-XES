package io.github.nexalloy.revanced.threads

import io.github.nexalloy.revanced.threads.ads.HideAds
import io.github.nexalloy.revanced.threads.download.SaveVideos
import io.github.nexalloy.revanced.threads.network.BlockNetwork
import io.github.nexalloy.revanced.threads.privacy.DisableScreenshotDetection
import io.github.nexalloy.revanced.threads.tracking.SanitizeTrackingLinks

val ThreadsPatches = arrayOf(
  HideAds,  
  BlockNetwork, 
  SanitizeTrackingLinks,
  DisableScreenshotDetection,
  SaveVideos,
)
