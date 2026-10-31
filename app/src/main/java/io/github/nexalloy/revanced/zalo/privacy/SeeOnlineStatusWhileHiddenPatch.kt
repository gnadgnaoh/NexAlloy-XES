package io.github.nexalloy.revanced.zalo.privacy

import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.callStaticMethod
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Member

val SeeOnlineStatusWhileHidden = patch(
    name = "See online status while yours is hidden",
    description = "Turn off \"Hiện trạng thái truy cập\" in Zalo's privacy settings so nobody sees when you were " +
        "last online, even right after you send a message. This keeps showing other people's \"Truy cập\" status " +
        "to you, which Zalo hides once yours is off.",
) {
    val setting = ::showOnlineSettingFingerprint.method
    val rule = ::lastActiveRuleFingerprint.method
    val readers = ::onlineStatusReadersFingerprint.dexMethodList.map { it.toMethod() }
    val lastOnline = readers.single { it.returnType == Long::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
    val inside = ThreadLocal.withInitial { ArrayDeque<Member>() }
    readers.forEach { reader ->
        XposedBridge::class.java.callStaticMethod("deoptimizeMethod", reader as Member)
        reader.hookMethod {
            before { inside.get().addLast(reader) }
            after { inside.get().removeLastOrNull() }
        }
    }
    setting.hookMethod { after { param -> if (inside.get().isNotEmpty()) param.result = true } }
    rule.hookMethod { after { param -> if (inside.get().lastOrNull() == lastOnline) param.result = 0 } }
}
