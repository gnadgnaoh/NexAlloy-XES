package io.github.nexalloy.morphe.tiktok.screencapture

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * ClearModePanelComponent's display listener (`onDisplayAdded(I)V` / `onDisplayRemoved(I)V`), which
 * TikTok uses to notice a screen recorder's virtual display. Found by its log tags inside the
 * kept-name component; a list so that a build without it is cached as "not found".
 */
val clearModeDisplayListenerFingerprints = findMethodListDirect {
    cacheable {
        listOf("[onDisplayAdded]", "[onDisplayRemoved]").flatMap { tag ->
            findMethod {
                matcher {
                    declaredClass("ClearModePanelComponent", StringMatchType.EndsWith)
                    usingStrings(listOf(tag), StringMatchType.Equals)
                    returnType = "void"
                }
            }
        }.distinctBy { it.descriptor }
    }
}

/**
 * The lazy A/B value `circle_search_block` (`Function0.invoke(): Object`, boxing an Int). Any value
 * above 0 makes MainContentSecurityAssem watch touches and keep Circle to Search off the feed; 0
 * leaves it alone. The string has two other users (where the setting is registered and read again),
 * neither of which is a no-argument `invoke`. LX/05It on Asia 47.0.3, its R8 twin on Global 47.1.4.
 *
 * Ported from HushFeed's "Allow screenshots and Circle to Search" (GPL-3.0).
 */
val circleSearchBlockSettingFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("circle_search_block"), StringMatchType.Equals)
                name = "invoke"
                paramCount = 0
                returnType = "java.lang.Object"
            }
        }
    }
}
