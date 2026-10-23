package io.github.nexalloy.morphe.tiktok.share

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

val shareLinkBuilderFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("utm_campaign", "share_link_id"), StringMatchType.Equals)
                returnType = "java.lang.String"
                modifiers(Modifier.STATIC)
            }
        }
    }
}
