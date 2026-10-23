package io.github.nexalloy.morphe.tiktok.screencapture

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType

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
