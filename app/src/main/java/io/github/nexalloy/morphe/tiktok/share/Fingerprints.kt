package io.github.nexalloy.morphe.tiktok.share

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

/**
 * The share link builder, `static String (type, url, channel, BaseSharePackage)`: appends
 * utm_source/utm_campaign/utm_medium, share_iid, share_link_id, share_app_id, ugbiz_name, ug_btm
 * (and the share-prop sp_root_* fields) to the link of whatever is shared, for every channel and
 * for "Copy link" (LX/0zr4.LIZ on Asia 47.0.3, LX/19my.LIZ on Global 47.1.4).
 *
 * The two strings meet in one other method on each build, a merged lambda that *reads* them off an
 * opened deep link and returns Object, so the String return type alone makes the match unique.
 * HushFeed (after ReVanced) additionally pins the URL to the second parameter; that is not needed
 * here because the patch rewrites the returned link, not an argument.
 */
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
