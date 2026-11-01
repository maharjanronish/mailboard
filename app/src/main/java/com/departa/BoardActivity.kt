package com.departa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import com.departa.board.BoardAdapter
import com.departa.board.Row
import com.departa.data.BoardRepository
import com.departa.data.Departure
import com.departa.util.Fmt
import com.departa.widget.BoardWidgetProvider
import java.util.concurrent.Executors

/**
 * The departure board. One ListView, three row types, a minute-aligned
 * ticker, a debounced ContentObserver and an endlessly extendable query
 * window. No services, no wake locks, no network.
 */
class BoardActivity : Activity() {

    private lateinit var repo: BoardRepository
    private lateinit var adapter: BoardAdapter
    private lateinit var list: ListView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var token = 0
    private var windowEnd = 0L
    private var extending = false
    private var lastExtendAt = 0L

    private val debouncedReload = Runnable { reload() }

    private val ticker = Runnable {
        reload()
        BoardWidgetProvider.refresh(this@BoardActivity)
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

        findViewById<View>(R.id.btn_add).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
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
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                if (totalCount > 0 && firstVisible + visibleCount >= totalCount - LOAD_AHEAD) {
                    extendWindow()
                }
            }
        })
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
        windowEnd = System.currentTimeMillis() + INITIAL_WINDOW_MS
        reload()
        scheduleTick()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(ticker)
        main.removeCallbacks(debouncedReload)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Lazy window extension: grow the horizon one day at a time, capped. */
    private fun extendWindow() {
        if (extending) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastExtendAt < EXTEND_COOLDOWN_MS) return
        if (windowEnd - System.currentTimeMillis() > MAX_LOOKAHEAD_MS) return
        extending = true
        lastExtendAt = now
        windowEnd += DAY_MS
        reload()
    }

    private fun reload() {
        if (!hasPermission()) return
        val myToken = ++token
        val from = System.currentTimeMillis() - PAST_WINDOW_MS
        val to = windowEnd
        io.execute {
            val lines = repo.loadLines()
            val departures = repo.loadDepartures(from, to, lines)
            val rows = buildRows(departures, System.currentTimeMillis())
            main.post {
                if (myToken == token) {
                    adapter.rows = rows
                    extending = false
                }
            }
        }
    }

    /**
     * Single pass over the time-sorted departures: day separators on day
     * changes, NOW divider before the first future departure.
     */
    private fun buildRows(departures: List<Departure>, now: Long): List<Row> {
        val out = mutableListOf<Row>()
        val todayKey = Fmt.dayKey(now)
        var prevDayKey = todayKey
        var insertedNow = false
        for (d in departures) {
            val key = Fmt.dayKey(d.begin)
            if (key != prevDayKey) {
                out += Row.Day(Fmt.dayLabel(d.begin, now))
                prevDayKey = key
            }
            if (!insertedNow && d.begin >= now) {
                out += Row.Now(now)
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
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val PAST_WINDOW_MS = 30L * 60L * 1000L
        private const val INITIAL_WINDOW_MS = 36L * 60L * 60L * 1000L
        private const val MAX_LOOKAHEAD_MS = 60L * DAY_MS
        private const val RELOAD_DEBOUNCE_MS = 350L
        private const val EXTEND_COOLDOWN_MS = 400L
        private const val LOAD_AHEAD = 6
    }
}
