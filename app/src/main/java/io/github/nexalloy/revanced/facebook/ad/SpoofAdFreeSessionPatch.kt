package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookForceBoolean

val SpoofAdFreeSession = patch(
    name = "Spoof ad-free session",
    description = "Makes Facebook believe an ad-free session is active, so the server stops sending sponsored content (including sponsored Stories tray circles).",
) {
    // Chưa thấy dex feed → ném lỗi → KatanaDexGate thử lại.
    ::sponsoredPoolAddMethodFingerprint.method

    val getters = ::adFreeSessionGettersFingerprint.dexMethodList
    check(getters.isNotEmpty()) { "adFreeSessionGettersFingerprint: no getter found" }
    getters.forEach { dm -> hookForceBoolean(dm.toMethod(), true) }
}
