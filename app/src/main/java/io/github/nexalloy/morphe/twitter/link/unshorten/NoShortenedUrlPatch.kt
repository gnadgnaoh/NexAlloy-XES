package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private inline fun safely(block: () -> Unit) {
    runCatching(block)
}

val NoShortenedUrl = patch(
    name = "No shortened URL",
    description = "Gets rid of t.co short urls by showing the expanded URL instead.",
) {
    safely {
        var hooked = 0

        for (constructor in UrlEntityToStringFingerprint.declaredClass.declaredConstructors) {
            val stringIndices = constructor.parameterTypes
                .mapIndexedNotNull { index, type -> index.takeIf { type == String::class.java } }
            if (stringIndices.size != 3) continue

            val (displayIdx, expandedIdx, urlIdx) = stringIndices
            constructor.hookMethod {
                before { param -> unshortenArgs(param, displayIdx, expandedIdx, urlIdx) }
            }
            hooked++
        }

        check(hooked > 0) { "No UrlEntity constructor with exactly 3 String parameters" }
    }

    for (fingerprint in listOf(
        OpenExternalUrlFingerprint,
        OpenExternalBrowserFingerprint,
        OpenUrlInAppFingerprint,
    )) {
        safely {
            val method = fingerprint.method
            val urlIndex = method.parameterTypes.indexOfFirst { it == String::class.java }
            check(urlIndex >= 0) { "${method.name} has no String parameter" }

            method.hookMethod {
                before { param -> unshortenArgAt(param, urlIndex) }
            }
        }
    }

    for (fingerprint in listOf(
        LinkWithPostDetailArgsToStringFingerprint,
        WebViewArgsToStringFingerprint,
    )) {
        safely {
            for (constructor in fingerprint.declaredClass.declaredConstructors) {
                val urlIndex = constructor.parameterTypes.indexOfFirst { it == String::class.java }
                if (urlIndex < 0) continue

                constructor.hookMethod {
                    before { param -> unshortenArgAt(param, urlIndex) }
                }
            }
        }
    }
}
