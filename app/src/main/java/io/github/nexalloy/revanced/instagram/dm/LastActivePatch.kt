package io.github.nexalloy.revanced.instagram.dm

import android.app.AndroidAppHelper
import android.content.res.Resources
import android.text.format.DateUtils
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

val ShowLastActive = patch(
    name = "Show when someone was last active",
    description = "Shows the exact time someone was last on, like Active at 3:42 PM, instead of Active 3h ago. " +
        "Active now stays.",
) {
    val text = ::presenceTextMethod.method
    val lastActive = ::presenceLastActiveField.field.apply { isAccessible = true }
    val activeCheck = ::presenceActiveCheckMethod.method.apply { isAccessible = true }
    val window = ::presenceActiveWindowField.field.apply { isAccessible = true }

    text.hookMethod {
        after { param ->
            if (param.result !is String) return@after
            runCatching {
                val item = (param.args[1] as? List<*>)?.firstOrNull() ?: return@runCatching
                val last = lastActive.getLong(item).takeIf { it > 0 } ?: return@runCatching
                val now = System.currentTimeMillis()
                // Cùng điều kiện "Đang hoạt động" của Instagram → để nguyên.
                if (activeCheck.invoke(null, item, now) == true || now - last <= window.getLong(null)) return@runCatching
                val res = param.args[0] as Resources
                param.result = exact(res, last) ?: return@runCatching
            }
        }
    }
}

private fun exact(res: Resources, at: Long): String? {
    val app = AndroidAppHelper.currentApplication() ?: return null
    val locale = res.configuration.locales[0]
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(at))
    val today = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val stamp = if (sameDay) time
    else DateUtils.formatDateTime(app, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH) + ", " + time
    return I18n.t(app, R.string.ig_active_at, stamp).takeIf { it.isNotEmpty() }
}
