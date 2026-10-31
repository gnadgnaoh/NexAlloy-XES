package io.github.nexalloy.revanced.threads.ads

import io.github.nexalloy.morphe.findMethodDirect

val adFetchSponsoredContentFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("onFetchSponsoredContent")
        }
    }.single()
}

val permalinkAdResultFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = "com.instagram.barcelona.sponsored.permalink.ThreadsPermalinkAdFetcher"
            returnType = "java.lang.Object"
            usingStrings("text_feed/threads_permalink_ads/")
        }
    }.single().invokes.single { it.isConstructor && it.paramTypeNames == listOf("java.util.List", "int") }
}
