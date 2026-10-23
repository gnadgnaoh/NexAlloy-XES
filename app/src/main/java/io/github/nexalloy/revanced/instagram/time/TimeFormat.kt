package io.github.nexalloy.revanced.instagram.time

import android.content.Context
import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal object TimeFormat {
    fun exact(ctx: Context, at: Long): String {
        val locale = ctx.resources.configuration.locales[0]
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(at))
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return time
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        if (sameYear) flags = flags or DateUtils.FORMAT_NO_YEAR
        return DateUtils.formatDateTime(ctx, at, flags) + ", " + time
    }
}
