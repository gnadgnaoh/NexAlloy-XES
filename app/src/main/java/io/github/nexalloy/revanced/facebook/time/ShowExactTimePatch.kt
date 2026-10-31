package io.github.nexalloy.revanced.facebook.time

import android.content.Context
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.time.TimeFormat
import io.github.nexalloy.revanced.shared.time.exactFromSeconds
import java.util.concurrent.ConcurrentHashMap

private const val HOUR = 3_600_000L
private const val EARLIEST = 1_075_000_000_000L

val ShowExactTime = patch(
    name = "Show exact time",
    description = "Shows the exact date and time instead of \"2h\" or \"3 days ago\" on comments, reels and videos. " +
        "The time at the top of a post comes from Facebook's servers and stays as it is.",
) {
    val method = ::timeStyleFormatFingerprint.method
    val relative = ConcurrentHashMap<Int, Boolean>()

    fun original(owner: Any?, style: Int, at: Long): String? =
        runCatching { XposedBridge.invokeOriginalMethod(method, owner, arrayOf<Any?>(style, at)) as? String }.getOrNull()

    fun isRelative(owner: Any?, style: Int): Boolean = relative.getOrPut(style) {
        val now = System.currentTimeMillis()
        val two = original(owner, style, now - 2 * HOUR)
        two != null && two != original(owner, style, now - 3 * HOUR) && two != original(owner, style, now - 26 * HOUR)
    }

    method.hookMethod {
        after { param ->
            if (param.result !is String) return@after
            val style = param.args[0] as? Int ?: return@after
            val at = param.args[1] as? Long ?: return@after
            if (at !in EARLIEST..(System.currentTimeMillis() + HOUR)) return@after
            if (!isRelative(param.thisObject, style)) return@after
            param.result = TimeFormat.exact(appContext, at)
        }
    }

    ::secondsAgoFormatFingerprint.method.hookMethod {
        after { param ->
            if (param.result !is String) return@after
            val ctx = param.args[0] as? Context ?: return@after
            exactFromSeconds(ctx, param.args[2])?.let { param.result = it }
        }
    }
}
