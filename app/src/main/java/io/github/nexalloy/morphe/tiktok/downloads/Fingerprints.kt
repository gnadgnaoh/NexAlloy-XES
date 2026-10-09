package io.github.nexalloy.morphe.tiktok.downloads

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

internal const val AWEME_ACL_SHARE_CLASS = "com.ss.android.ugc.aweme.feed.model.AwemeACLShare"

internal const val DOWNLOAD_ACL_GETTER_PREFIX = "getDownload"

internal const val ACL_ALLOWED = 0

internal const val ACL_SHOW_ENABLED = 2

val downloadPermissionReaderFingerprints = findMethodListDirect {
    cacheable {
        val aclGetters = findMethod {
            matcher {
                declaredClass = AWEME_ACL_SHARE_CLASS
                name(DOWNLOAD_ACL_GETTER_PREFIX, StringMatchType.StartsWith)
                paramCount = 0
            }
        }
        val preventDownload = findMethod {
            matcher {
                declaredClass = AWEME_CLASS
                name = "isPreventDownload"
                paramCount = 0
            }
        }
        (aclGetters + preventDownload).flatMap { it.callers }.distinctBy { it.descriptor }
    }
}
