package com.departa

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.departa.data.BoardRepository
import com.departa.data.Line
import com.departa.ui.LineDialogs
import com.departa.util.Fmt
import com.departa.widget.BoardWidgetProvider
import java.util.Calendar

/**
 * Create / edit a departure. Everything is written through the system
 * provider; recurring events are stored as plain RRULE presets.
 */
class EditActivity : Activity() {

    private lateinit var repo: BoardRepository
    private lateinit var etTitle: EditText
    private lateinit var etDur: EditText
    private lateinit var etLoc: EditText
    private lateinit var tvLine: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView

    private var lines: List<Line> = emptyList()
    private var selectedLine: Line? = null
    private var eventId = -1L
    private var eventCalId = -1L
    private var begin = 0L
    private var repeat = REPEAT_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        repo = BoardRepository(this)
        etTitle = findViewById(R.id.title)
        etDur = findViewById(R.id.dur)
        etLoc = findViewById(R.id.loc)
        tvLine = findViewById(R.id.line_btn)
        tvDate = findViewById(R.id.date_btn)
        tvTime = findViewById(R.id.time_btn)

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)

        tvLine.setOnClickListener { pickLine() }
        tvDate.setOnClickListener { pickDate() }
        tvTime.setOnClickListener { pickTime() }

        for ((id, minutes) in DURATION_CHIPS) {
            findViewById<TextView>(id).setOnClickListener { etDur.setText(minutes.toString()) }
        }
        for ((id, mode) in REPEAT_CHIPS) {
            findViewById<TextView>(id).setOnClickListener { setRepeat(mode) }
        }

        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        Thread {
            val loaded = repo.loadLines()
            val event = if (eventId > 0) repo.loadEvent(eventId) else null
            runOnUiThread {
                lines = loaded
                if (event != null) fillForEdit(event) else fillDefaults()
            }
        }.start()
    }

    // -------------------------------------------------------------- fill state

    private fun fillDefaults() {
        begin = nextHalfHour(System.currentTimeMillis() + 60L * 60L * 1000L)
        selectedLine = lines.firstOrNull { !it.hidden } ?: lines.firstOrNull()
        etDur.setText("60")
        etLoc.setText("")
        setRepeat(REPEAT_NONE)
        refreshLabels()
    }

    private fun fillForEdit(ev: BoardRepository.EventRecord) {
        eventId = ev.eventId
        eventCalId = ev.calId
        begin = ev.begin
        etTitle.setText(ev.title)
        etLoc.setText(ev.location ?: "")
        etDur.setText(((ev.end - ev.begin) / 60_000L).coerceAtLeast(5L).toString())
        selectedLine = lines.firstOrNull { it.calId == ev.calId }
        setRepeat(
            when {
                ev.rrule == null -> REPEAT_NONE
                ev.rrule.contains("DAILY") -> REPEAT_DAILY
                ev.rrule.contains("WEEKLY") -> REPEAT_WEEKLY
                ev.rrule.contains("MONTHLY") -> REPEAT_MONTHLY
                else -> REPEAT_NONE
            }
        )
        findViewById<View>(R.id.btn_delete).visibility = View.VISIBLE
        refreshLabels()
    }

    private fun refreshLabels() {
        tvDate.text = Fmt.dateLabel(begin)
        tvTime.text = Fmt.hm(begin)
        val line = selectedLine
        if (line == null) {
            tvLine.text = "SELECT LINE  ▾"
            tvLine.setTextColor(getColor(R.color.text_dim))
        } else {
            tvLine.text = "●  ${line.name.uppercase()}  ▾"
            tvLine.setTextColor(line.color)
        }
    }

    private fun setRepeat(mode: Int) {
        repeat = mode
        for ((id, m) in REPEAT_CHIPS) {
            val tv = findViewById<TextView>(id)
            if (m == mode) {
                tv.setBackgroundResource(R.drawable.bg_chip_on)
                tv.setTextColor(getColor(R.color.board_bg))
            } else {
                tv.setBackgroundResource(R.drawable.bg_field)
                tv.setTextColor(getColor(R.color.text_dim))
            }
        }
    }

    // ---------------------------------------------------------------- pickers

    private fun pickLine() {
        if (lines.isEmpty()) {
            toast("CREATE A LINE FIRST")
            createLine()
            return
        }
        val names = lines.map { it.name + if (it.hidden) "   · HIDDEN" else "" } + listOf("+  NEW LINE")
        AlertDialog.Builder(this)
            .setTitle("LINE")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == lines.size) {
                    createLine()
                } else {
                    selectedLine = lines[which]
                    refreshLabels()
                }
            }
            .show()
    }

    private fun createLine() {
        LineDialogs.showCreate(this) { line ->
            lines = listOf(line) + lines
            selectedLine = line
            refreshLabels()
        }
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = begin }
        DatePickerDialog(this, { _, year, month, day ->
            val n = Calendar.getInstance().apply {
                timeInMillis = begin
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }
            begin = n.timeInMillis
            refreshLabels()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = begin }
        TimePickerDialog(this, { _, hour, minute ->
            val n = Calendar.getInstance().apply {
                timeInMillis = begin
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            begin = n.timeInMillis
            refreshLabels()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show()
    }

    // ------------------------------------------------------------------ write

    private fun save() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            toast("TITLE REQUIRED")
            return
        }
        val line = selectedLine
        if (line == null) {
            toast("SELECT A LINE")
            return
        }
        val duration = etDur.text.toString().toIntOrNull()?.coerceIn(5, 1440) ?: 60
        val end = begin + duration * 60_000L
        val location = etLoc.text.toString().trim().ifBlank { null }
        val rrule = when (repeat) {
            REPEAT_DAILY -> "FREQ=DAILY"
            REPEAT_WEEKLY -> "FREQ=WEEKLY"
            REPEAT_MONTHLY -> "FREQ=MONTHLY"
            else -> null
        }
        Thread {
            val ok = if (eventId > 0) {
                if (line.calId == eventCalId) {
                    repo.updateEvent(eventId, title, begin, end, location, rrule)
                } else {
                    repo.moveEvent(eventId, line.calId, title, begin, end, location, rrule) != null
                }
            } else {
                repo.insertEvent(line.calId, title, begin, end, location, rrule) != null
            }
            repo.nudgeSync(line)
            runOnUiThread {
                if (ok) {
                    BoardWidgetProvider.refresh(this)
                    finish()
                } else {
                    toast("SAVE FAILED")
                }
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("DELETE EVENT?")
            .setPositiveButton("DELETE") { _, _ ->
                Thread {
                    val ok = repo.deleteEvent(eventId)
                    lines.firstOrNull { it.calId == eventCalId }?.let { repo.nudgeSync(it) }
                    runOnUiThread {
                        if (ok) {
                            BoardWidgetProvider.refresh(this)
                            finish()
                        } else {
                            toast("DELETE FAILED")
                        }
                    }
                }.start()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun nextHalfHour(base: Long): Long {
        val half = 30L * 60L * 1000L
        return base + half - (base % half)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2
        const val REPEAT_MONTHLY = 3
        private val DURATION_CHIPS = listOf(
            R.id.chip_30 to 30, R.id.chip_45 to 45, R.id.chip_60 to 60,
            R.id.chip_90 to 90, R.id.chip_120 to 120
        )
        private val REPEAT_CHIPS = listOf(
            R.id.chip_once to REPEAT_NONE, R.id.chip_daily to REPEAT_DAILY,
            R.id.chip_weekly to REPEAT_WEEKLY, R.id.chip_monthly to REPEAT_MONTHLY
        )
    }
}
