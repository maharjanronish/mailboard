package com.departa.data

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.departa.util.Prefs
import java.util.TimeZone

/**
 * The ONLY place that touches storage. Everything goes through the system
 * CalendarContract provider: the Android sync adapter (not this app) keeps
 * Google Calendar in sync, so Departa never opens a socket.
 */
class BoardRepository(private val ctx: Context) {

    private val cr: ContentResolver = ctx.contentResolver

    // ------------------------------------------------------------------ lines

    fun loadLines(): List<Line> {
        val hidden = Prefs.hiddenLines(ctx)
        val out = mutableListOf<Line>()
        cr.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.CALENDAR_COLOR,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE
            ),
            null, null,
            "${Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(0)
                out += Line(
                    calId = calId,
                    name = c.getString(1) ?: "—",
                    color = c.getInt(2) or (0xFF shl 24),
                    accountName = c.getString(3) ?: "",
                    accountType = c.getString(4) ?: "",
                    hidden = hidden.contains(calId.toString())
                )
            }
        }
        return out
    }

    /** Create a line (a real calendar). Google-account lines sync upstream. */
    fun createLine(name: String, color: Int, accountName: String, accountType: String): Long? {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, "departa-${System.currentTimeMillis()}")
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            if (accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) {
                put(Calendars.OWNER_ACCOUNT, accountName)
            }
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val uri = asSyncAdapter(Calendars.CONTENT_URI, accountName, accountType)
        return try {
            cr.insert(uri, values)?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun renameLine(line: Line, newName: String): Boolean =
        try {
            val uri = asSyncAdapter(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, line.calId),
                line.accountName, line.accountType
            )
            val values = ContentValues().apply { put(Calendars.CALENDAR_DISPLAY_NAME, newName) }
            cr.update(uri, values, "${Calendars._ID}=?", arrayOf(line.calId.toString())) > 0
        } catch (_: Exception) {
            false
        }

    /** Ask the system sync adapter to run now (no-op for local lines). */
    fun nudgeSync(line: Line) {
        if (line.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) return
        val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) }
        ContentResolver.requestSync(Account(line.accountName, line.accountType), CalendarContract.AUTHORITY, extras)
    }

    // ------------------------------------------------------------- departures

    /**
     * Windowed query on the (already expanded, already merged across all
     * lines) Instances table. SQLite does the k-way merge via ORDER BY.
     */
    fun loadDepartures(from: Long, to: Long, lines: List<Line>): List<Departure> {
        val visible = lines.filter { !it.hidden }
        if (visible.isEmpty()) return emptyList()
        val lineById = HashMap<Long, Line>(visible.size * 2)
        visible.forEach { lineById[it.calId] = it }
        val idCsv = visible.joinToString(",") { it.calId.toString() }

        val uri = Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
            it.build()
        }

        val out = mutableListOf<Departure>()
        cr.query(
            uri,
            arrayOf(
                Instances._ID, Instances.EVENT_ID, Instances.TITLE,
                Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.CALENDAR_ID, Instances.EVENT_LOCATION
            ),
            "${Instances.CALENDAR_ID} IN ($idCsv)",
            null,
            "${Instances.BEGIN} ASC, ${Instances.CALENDAR_ID} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val line = lineById[c.getLong(6)] ?: continue
                out += Departure(
                    instanceId = c.getLong(0),
                    eventId = c.getLong(1),
                    title = c.getString(2) ?: "",
                    begin = c.getLong(3),
                    end = c.getLong(4),
                    allDay = c.getInt(5) == 1,
                    location = c.getString(7),
                    line = line
                )
            }
        }
        return out
    }

    // ----------------------------------------------------------------- events

    data class EventRecord(
        val eventId: Long,
        val calId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String?,
        val rrule: String?
    )

    fun loadEvent(eventId: Long): EventRecord? {
        cr.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(
                Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND,
                Events.ALL_DAY, Events.EVENT_LOCATION, Events.RRULE
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                return EventRecord(
                    eventId = eventId,
                    calId = c.getLong(0),
                    title = c.getString(1) ?: "",
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5),
                    rrule = c.getString(6)
                )
            }
        }
        return null
    }

    fun insertEvent(calId: Long, title: String, begin: Long, end: Long, location: String?, rrule: String?): Long? {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            put(Events.DTSTART, begin)
            put(Events.DTEND, end)
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(Events.EVENT_LOCATION, location)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            if (rrule != null) put(Events.RRULE, rrule)
        }
        return try {
            cr.insert(Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun updateEvent(eventId: Long, title: String, begin: Long, end: Long, location: String?, rrule: String?): Boolean {
        val values = ContentValues().apply {
            put(Events.TITLE, title)
            put(Events.DTSTART, begin)
            put(Events.DTEND, end)
            put(Events.EVENT_LOCATION, location)
            if (rrule == null) putNull(Events.RRULE) else put(Events.RRULE, rrule)
        }
        return try {
            cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Move = delete + re-insert on the target line (works for regular apps). */
    fun moveEvent(oldEventId: Long, newCalId: Long, title: String, begin: Long, end: Long, location: String?, rrule: String?): Long? {
        try {
            cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, oldEventId), null, null)
        } catch (_: Exception) {
        }
        return insertEvent(newCalId, title, begin, end, location, rrule)
    }

    fun deleteEvent(eventId: Long): Boolean =
        try {
            cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
        } catch (_: Exception) {
            false
        }

    // ---------------------------------------------------------------- helpers

    /**
     * The standard pattern for writing calendar-table rows as a regular app:
     * mark the URI as sync-adapter so the provider accepts it, then the
     * account's own sync adapter uploads the change.
     */
    private fun asSyncAdapter(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
            .build()
}
