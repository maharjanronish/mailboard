package com.departa.data

import android.provider.CalendarContract

/**
 * A "line" on the departure board — one calendar in the system provider.
 * Google-account calendars sync both ways; ACCOUNT_TYPE_LOCAL ones never
 * leave the device. The board treats both identically.
 */
data class Line(
    val calId: Long,
    val name: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    val hidden: Boolean
) {
    val isLocal: Boolean get() = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
}

/**
 * A "departure" — one instance of an event, already joined with its line.
 */
data class Departure(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val line: Line
)
