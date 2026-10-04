package io.github.nexalloy.morphe.tiktok.share

import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch

private const val TAG = "[TikTok share links]"

val SanitizeShareLinks = patch(
    name = "Sanitize sharing links",
    description = "Removes the tracking parameters (utm_*, share_*, u_code, _r, _t, ...) from TikTok " +
        "links before they are shared or copied.",
) {
    val builders = ::shareLinkBuilderFingerprints.dexMethodList.realMatches()
    check(builders.isNotEmpty()) { "share link builder not found" }

    builders.forEach { builder ->
        builder.toMethod().hookMethod {
            after { param ->
                val link = param.result as? String ?: return@after
                val clean = runCatching { ShareLinkSanitizer.sanitize(link) }.getOrNull() ?: return@after
                if (clean != link) param.result = clean
            }
        }
    }
    Logger.printInfo { "$TAG hooked ${builders.size} builder(s)" }
}
