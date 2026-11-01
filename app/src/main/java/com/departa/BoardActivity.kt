package com.departa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.departa.board.BoardAdapter
import com.departa.board.DateRail
import com.departa.board.Row
import com.departa.data.BoardRepository
import com.departa.data.Departure
import com.departa.util.Fmt
import com.departa.widget.BoardWidgetProvider
import java.util.concurrent.Executors

/**
 * The departure board.
 *
 * The header owns all time context now: a second-aligned live clock, the
 * selected day in big bold type, and the DateRail scope dial — one finger
 * slides the days under a fixed amber reticle and the board below re-queries
 * to exactly that day. Day-scoping killed the old endlessly-extendable
 * window machinery: one bounded [dayStart, dayEnd) query per reload.
 *
 * Still zero services, zero wake locks, zero network. The observer is only
 * registered while resumed; the clock is one TextView per second.
 */
class BoardActivity : Activity() {

    private lateinit var repo: BoardRepository
    private lateinit var adapter: BoardAdapter
    private lateinit var list: ListView
    private lateinit var rail: DateRail
    private lateinit var clock: TextView
    private lateinit var dateDow: TextView
    private lateinit var dateNum: TextView
    private lateinit var dateMon: TextView
    private lateinit var btnToday: TextView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var token = 0

    private var todayKey = 0L        // local midnight of today (re-anchored at midnight rollover)
    private var selectedDayKey = 0L  // the day under the rail reticle

    private val debouncedReload = Runnable { reload() }
    private val dayReload = Runnable { reload() }

    /** The only per-second work in the app: one TextView's text. */
    private val clockTick = Runnable {
        clock.text = Fmt.hms(System.currentTimeMillis())
        main.postDelayed(clockTick, Fmt.millisToNextSecond())
    }

    private val ticker = Runnable {
        onMinute()
        scheduleTick()
    }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(debouncedReload)
            main.postDelayed(debouncedReload, RELOAD_DEBOUNCE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_board)
        repo = BoardRepository(this)
        adapter = BoardAdapter(this)
        list = findViewById(R.id.board)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)

        rail = findViewById(R.id.rail)
        clock = findViewById(R.id.clock)
        dateDow = findViewById(R.id.date_dow)
        dateNum = findViewById(R.id.date_num)
        dateMon = findViewById(R.id.date_mon)
        btnToday = findViewById(R.id.btn_today)

        todayKey = Fmt.dayKey(System.currentTimeMillis())
        selectedDayKey = todayKey
        rail.setDays(
            Fmt.addDays(todayKey, -RAIL_PAST_DAYS),
            RAIL_PAST_DAYS + RAIL_FUTURE_DAYS + 1,
            todayKey,
            todayKey
        )
        rail.onSelected = { day -> onDaySelected(day) }
        btnToday.setOnClickListener { rail.selectDay(todayKey, smooth = true) }
        renderDateHeader()

        findViewById<View>(R.id.btn_add).setOnClickListener {
            startActivity(
                Intent(this, EditActivity::class.java)
                    .putExtra(EditActivity.EXTRA_DAY_HINT, selectedDayKey)
            )
        }
        findViewById<View>(R.id.btn_lines).setOnClickListener {
            startActivity(Intent(this, LinesActivity::class.java))
        }
        findViewById<View>(R.id.btn_grant).setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_PERM
            )
        }
        list.setOnItemClickListener { _, _, position, _ ->
            (adapter.getItem(position) as? Row.DepartureRow)?.let { row ->
                startActivity(
                    Intent(this, EditActivity::class.java)
                        .putExtra(EditActivity.EXTRA_EVENT_ID, row.d.eventId)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasPermission()) {
            findViewById<View>(R.id.perm_gate).visibility = View.VISIBLE
            return
        }
        findViewById<View>(R.id.perm_gate).visibility = View.GONE
        contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, observer)

        // The process may have slept across one or more midnights.
        val nowToday = Fmt.dayKey(System.currentTimeMillis())
        if (nowToday != todayKey) {
            todayKey = nowToday
            rebuildRail()
        }
        reload()
        clockTick.run()
        scheduleTick()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(ticker)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(debouncedReload)
        main.removeCallbacks(dayReload)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ date header

    private fun onDaySelected(dayKey: Long) {
        selectedDayKey = dayKey
        renderDateHeader()
        // Coalesce a fast fling across many days into one re-query.
        main.removeCallbacks(dayReload)
        main.postDelayed(dayReload, DAY_RELOAD_DEBOUNCE_MS)
    }

    private fun renderDateHeader() {
        dateDow.text = Fmt.dowAbbr(selectedDayKey)
        dateNum.text = Fmt.dayNum(selectedDayKey).toString()
        dateMon.text = Fmt.monthAbbr(selectedDayKey)
        btnToday.setTextColor(
            getColor(if (selectedDayKey == todayKey) R.color.text_dim else R.color.amber)
        )
    }

    private fun rebuildRail() {
        val first = Fmt.addDays(todayKey, -RAIL_PAST_DAYS)
        val count = RAIL_PAST_DAYS + RAIL_FUTURE_DAYS + 1
        // After a very long sleep the old selection can fall out of range.
        if (selectedDayKey < first || selectedDayKey >= Fmt.addDays(first, count)) {
            selectedDayKey = todayKey
        }
        rail.setDays(first, count, todayKey, selectedDayKey)
        renderDateHeader()
    }

    // ----------------------------------------------------------------- minute

    /** Minute tick: midnight rollover guard + board refresh + widget push. */
    private fun onMinute() {
        val nowToday = Fmt.dayKey(System.currentTimeMillis())
        if (nowToday != todayKey) {
            val wasOnToday = selectedDayKey == todayKey
            todayKey = nowToday
            if (wasOnToday) selectedDayKey = nowToday
            rebuildRail()
        }
        reload()
        BoardWidgetProvider.refresh(this)
    }

    // ------------------------------------------------------------------ board

    /** One bounded query: exactly the selected day (30-min past tail when it's today). */
    private fun reload() {
        if (!hasPermission()) return
        val myToken = ++token
        val isToday = selectedDayKey == todayKey
        val from = if (isToday) System.currentTimeMillis() - PAST_WINDOW_MS else selectedDayKey
        val to = Fmt.addDays(selectedDayKey, 1)
        io.execute {
            val lines = repo.loadLines()
            val departures = repo.loadDepartures(from, to, lines)
            val rows = buildRows(departures, System.currentTimeMillis(), isToday)
            main.post {
                if (myToken == token) adapter.rows = rows
            }
        }
    }

    /**
     * Single pass over the time-sorted departures of ONE day: NOW divider
     * before the first future departure (today only — other days have no
     * "now"). Day context lives in the header, so there are no day rows.
     */
    private fun buildRows(departures: List<Departure>, now: Long, isToday: Boolean): List<Row> {
        val out = ArrayList<Row>(departures.size + 2)
        var insertedNow = false
        for (d in departures) {
            if (isToday && !insertedNow && d.begin >= now) {
                out += Row.Now
                insertedNow = true
            }
            out += Row.DepartureRow(d)
        }
        return out
    }

    private fun scheduleTick() {
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, Fmt.millisToNextMinute())
    }

    companion object {
        const val REQ_PERM = 7
        private const val PAST_WINDOW_MS = 30L * 60L * 1000L
        private const val RELOAD_DEBOUNCE_MS = 350L
        private const val DAY_RELOAD_DEBOUNCE_MS = 150L
        private const val RAIL_PAST_DAYS = 60
        private const val RAIL_FUTURE_DAYS = 545
    }
}
