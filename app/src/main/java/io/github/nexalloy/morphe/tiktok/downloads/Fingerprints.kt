package io.github.nexalloy.morphe.tiktok.downloads

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

/** The server's per-video share/download verdicts (Gson model, every member keeps its name). */
internal const val AWEME_ACL_SHARE_CLASS = "com.ss.android.ugc.aweme.feed.model.AwemeACLShare"

/**
 * AwemeACLShare's download verdicts: getDownloadGeneral (share panel "Save video", "Save photo",
 * GIF, Live Photo), getDownloadMaskPanel (the same actions in the long-press panel) and
 * getDownloadSharePanel. Every other share action reads shareGeneral / shareThirdPlatform or the
 * action lists, which are left alone.
 */
internal const val DOWNLOAD_ACL_GETTER_PREFIX = "getDownload"

/** ACLCommonShare.code: 0 means the action is allowed, anything else names the refusal. */
internal const val ACL_ALLOWED = 0

/** ACLCommonShare.showType: 2 shows the action as a normal, enabled button. */
internal const val ACL_SHOW_ENABLED = 2

/**
 * Every caller of the download verdict getters and of Aweme.isPreventDownload, to be deoptimized:
 * those getters are one-line field reads that ART inlines into compiled callers, where a hook on
 * the getter itself is never reached (same reason as the watermark patch's readers). The getters
 * are resolved exactly first and their callers taken from DexKit's call graph, so no method body
 * of the app is scanned.
 */
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
