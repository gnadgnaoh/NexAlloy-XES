package io.github.nexalloy.morphe.tiktok.watermark

import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val ACL_COMMON_SHARE_CLASS = "com.ss.android.ugc.aweme.feed.model.ACLCommonShare"
internal const val TRANSCODE_GETTER = "getTranscode"

internal const val TRANSCODE_NO_WATERMARK = 1

internal object AclCommonShareTranscodeFingerprint : Fingerprint(
    name = TRANSCODE_GETTER,
    returnType = "I",
    parameters = emptyList(),
    custom = { declaredClass("ACLCommonShare", StringMatchType.EndsWith) },
)

val transcodeReaderFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            addInvoke {
                declaredClass("ACLCommonShare", StringMatchType.EndsWith)
                name = TRANSCODE_GETTER
            }
        }
    }.distinctBy { it.descriptor }
}

internal const val VIDEO_CLASS = "com.ss.android.ugc.aweme.feed.model.Video"
internal const val CLEAN_ADDR_GETTER = "getDownloadNoWatermarkAddr"


internal val CLEAN_ADDR_FALLBACK_FIELDS = listOf("h264PlayAddr", "playAddr")

val cleanAddrReaderFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            addInvoke {
                declaredClass(VIDEO_CLASS)
                name = CLEAN_ADDR_GETTER
            }
        }
    }.distinctBy { it.descriptor }
}
