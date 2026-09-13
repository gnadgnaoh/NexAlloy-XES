package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

/**
 * Runs [block], swallowing failures instead of aborting the whole patch.
 * A single fingerprint that stops matching after a Twitter update should not
 * take down the hooks that still resolve fine.
 */
private inline fun safely(block: () -> Unit) {
    runCatching(block)
}

val NoShortenedUrl = patch(
    name = "No shortened URL",
    description = "Gets rid of t.co short urls by showing the expanded URL instead.",
) {
    // UrlEntity(displayUrl, expandedUrl, url, startIdx, endIdx).
    // Hook every constructor of the class and derive the argument indices from
    // the constructor shape: the three String parameters always appear in field
    // declaration order (displayUrl, expandedUrl, url). This keeps working when
    // Twitter adds or removes non-String fields.
    //
    // 12.24.0-prod.02:
    //   <init>(int, String, int, String, String)              -> 1, 3, 4
    //   <init>(int, int, int, String, String, String)         -> 3, 4, 5   (serializer)
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

    // ExternalScreenNav entry points that receive a raw url String.
    for (fingerprint in listOf(
        OpenExternalUrlFingerprint,      // openUrl(url, showError)
        OpenExternalBrowserFingerprint,  // openInExternalBrowser(url)
        OpenUrlInAppFingerprint,         // static openUrlInApp(nav, url)
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

    // Navigation argument holders whose first String parameter is the url.
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
