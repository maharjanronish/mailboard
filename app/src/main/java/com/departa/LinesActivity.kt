package com.departa

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.departa.data.BoardRepository
import com.departa.data.Line
import com.departa.ui.LineDialogs
import com.departa.util.Prefs

/**
 * Line manager: create, rename, show/hide. Deleting a calendar is
 * deliberately left to Google Calendar's own UI (destructive, syncs away
 * with all its events).
 */
class LinesActivity : Activity() {

    private lateinit var repo: BoardRepository
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private var lines: List<Line> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lines)
        repo = BoardRepository(this)
        list = findViewById(R.id.lines_list)
        emptyView = findViewById(R.id.lines_empty)
        list.adapter = LinesAdapter()

        findViewById<View>(R.id.btn_new_line).setOnClickListener {
            LineDialogs.showCreate(this) { reload() }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            renameDialog(lines[position])
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread {
            val loaded = repo.loadLines()
            runOnUiThread {
                lines = loaded
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                emptyView.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun renameDialog(line: Line) {
        val input = EditText(this).apply {
            setText(line.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("RENAME LINE")
            .setView(input)
            .setPositiveButton("RENAME") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == line.name) return@setPositiveButton
                Thread {
                    val ok = repo.renameLine(line, name)
                    if (ok) repo.nudgeSync(line)
                    runOnUiThread { reload() }
                }.start()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private inner class LinesAdapter : BaseAdapter() {

        override fun getCount(): Int = lines.size
        override fun getItem(position: Int): Any = lines[position]
        override fun getItemId(position: Int): Long = lines[position].calId
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_line, parent, false)
            val line = lines[position]
            v.findViewById<TextView>(R.id.dot).setTextColor(line.color)
            v.findViewById<TextView>(R.id.name).text = line.name
            v.findViewById<TextView>(R.id.account).text =
                if (line.isLocal) "LOCAL · THIS DEVICE" else line.accountName
            val vis = v.findViewById<TextView>(R.id.vis)
            vis.text = if (line.hidden) "SHOW" else "HIDE"
            vis.setOnClickListener {
                Prefs.setHidden(this@LinesActivity, line.calId, !line.hidden)
                reload()
            }
            return v
        }
    }
}
