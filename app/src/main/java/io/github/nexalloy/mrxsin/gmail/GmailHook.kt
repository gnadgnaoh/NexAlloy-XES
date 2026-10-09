package io.github.nexalloy.mrxsin.gmail

import io.github.nexalloy.Patch
import io.github.nexalloy.mrxsin.gmail.ads.HideAds

const val GMAIL_PACKAGE_NAME = "com.google.android.gm"

val GmailPatches = arrayOf<Patch>(
    HideAds,
)
