package io.github.nexalloy.revanced.zalo.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.fingerprint
import java.lang.reflect.Modifier

private const val SHOW_ONLINE_SETTING = "PRIVACY_SETTINGS_SHOW_ONLINE_STATUS_"
private const val ONLINE_FRIENDS_SETTING = "SETTING_SHOW_ONLINE_FRIEND_WITH_STATUS"
private const val MAIN_TAB_VIEW = "com.zing.zalo.ui.maintab.MainTabView"

val seenAckSocketFingerprint = fingerprint {
    strings("ackMsgList", "seen", "data")
    returns("V")
}

val seenAckRepositoryFingerprint = fingerprint {
    strings("ACK receive msg | ", " | seen=")
    returns("V")
}

val typingSocketFingerprint = fingerprint {
    classMatcher { usingStrings("ackMsgList", "seen", "data") }
    parameters("Ljava/lang/String;", "I", "Z", "Z")
    returns("V")
}

val typingRepositoryFingerprint = fingerprint {
    classMatcher { usingStrings("ACK receive msg | ") }
    parameters("Ljava/lang/String;", "I", "Z", "Z")
    returns("V")
}

val showOnlineSettingFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings(SHOW_ONLINE_SETTING); returnType = "boolean"; paramCount = 0 }
    }.single { Modifier.isStatic(it.modifiers) }
}

val onlineStatusReadersFingerprint = findMethodListDirect {
    showOnlineSettingFingerprint().callers.filter { m ->
        (m.returnTypeName == "long" && m.paramTypeNames == listOf("java.lang.String")) ||
            ONLINE_FRIENDS_SETTING in m.usingStrings ||
            (m.paramCount == 0 && m.returnTypeName == "void" && m.invokes.any { it.declaredClassName == MAIN_TAB_VIEW })
    }.distinctBy { it.descriptor }.also { check(it.size >= 3) { "expected the online status readers, found ${it.size}" } }
}

val lastActiveRuleFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings("ENABLE_LAST_ACTIVE_NEW_RULE"); returnType = "int"; paramCount = 0 }
    }.single { Modifier.isStatic(it.modifiers) }
}
