package com.departa.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Board formatting: always 24h railway time, minimal allocations. */
object Fmt {

    fun hm(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun dateLabel(ms: Long): String =
        SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date(ms))
            .uppercase(Locale.getDefault())

    fun dayKey(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun dayLabel(ms: Long, now: Long): String {
        val diff = (dayKey(ms) - dayKey(now)) / 86_400_000L
        return when (diff) {
            0L -> "TODAY"
            1L -> "TOMORROW"
            else -> dateLabel(ms)
        }
    }

    fun countdown(now: Long, begin: Long): String {
        val d = ((begin - now) / 60_000L).toInt()
        return when {
            d <= 0 -> "NOW"
            d < 60 -> "in ${d}m"
            else -> "in ${d / 60}h ${d % 60}m"
        }
    }

    /** Milliseconds until the next minute boundary (+20ms guard). */
    fun millisToNextMinute(): Long = 60_000L - (System.currentTimeMillis() % 60_000L) + 20L
}
