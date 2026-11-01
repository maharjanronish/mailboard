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

    /** Header clock: HH:MM:SS, the departure-board signature. */
    fun hms(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
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

    /** Day-scoped board helpers: the header's big date. */
    fun dowAbbr(ms: Long): String =
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
            .uppercase(Locale.getDefault()).take(3)

    fun monthAbbr(ms: Long): String =
        SimpleDateFormat("MMM", Locale.getDefault()).format(Date(ms))
            .uppercase(Locale.getDefault()).take(3)

    fun dayNum(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)

    /** DST-safe day arithmetic on day keys (stays local midnight). */
    fun addDays(ms: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis
    }

    fun countdown(now: Long, begin: Long): String {
        val d = ((begin - now) / 60_000L).toInt()
        return when {
            d <= 0 -> "NOW"
            d < 60 -> "in ${d}m"
            d < 1440 -> "in ${d / 60}h ${d % 60}m"
            else -> "in ${d / 1440}d ${d % 1440 / 60}h"
        }
    }

    /** Milliseconds until the next minute boundary (+20ms guard). */
    fun millisToNextMinute(): Long = 60_000L - (System.currentTimeMillis() % 60_000L) + 20L

    /** Milliseconds until the next second boundary (+10ms guard). */
    fun millisToNextSecond(): Long = 1_000L - (System.currentTimeMillis() % 1_000L) + 10L
}
