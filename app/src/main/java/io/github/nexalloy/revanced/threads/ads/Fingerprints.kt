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

val adContentDeliveredFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("onSponsoredContentDelivered")
        }
    }.single()
}

val paidPartnershipLabelFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("feed.post.ui.PaidPartnershipLabel (PaidPartnershipLabel.kt:")
        }
    }.single()
}

val adMetadataFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("sponsored.ui.AdMetadata (AdMetadata.kt:")
        }
    }.single()
}

val sponsoredLabelInHeaderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("sponsored.ui.SponsoredLabelInHeader (SponsoredLabel.kt:")
        }
    }.single()
}

val spoolAdInjectorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = "com.instagram.barcelona.feed.data.cache.BarcelonaSpoolFeedCacheHandler"
            returnType = "boolean"
            paramCount = 2
        }
    }.single()
}
