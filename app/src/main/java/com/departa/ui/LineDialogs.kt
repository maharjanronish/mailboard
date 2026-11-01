package com.departa.ui

import android.app.Activity
import android.app.AlertDialog
import android.provider.CalendarContract
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.departa.R
import com.departa.data.BoardRepository
import com.departa.data.Line

/**
 * Shared "new line" dialog used by both the editor and the lines screen.
 * Google-account lines are created as real calendars via the sync-adapter
 * insert pattern; with no Google account present a local line is created.
 */
object LineDialogs {

    fun showCreate(activity: Activity, onCreated: (Line) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_line, null)
        val nameField = view.findViewById<EditText>(R.id.line_name)
        val colorRow = view.findViewById<LinearLayout>(R.id.color_row)
        var chosenIndex = 0

        val dots = palette(activity).map { color ->
            TextView(activity).apply {
                text = "●"
                textSize = 22f
                setTextColor(color)
                alpha = 0.25f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        dots.forEachIndexed { index, dot ->
            dot.setOnClickListener {
                chosenIndex = index
                dots.forEach { it.alpha = 0.25f }
                dot.alpha = 1f
            }
        }
        dots.firstOrNull()?.alpha = 1f
        dots.forEach { colorRow.addView(it) }

        AlertDialog.Builder(activity)
            .setTitle("NEW LINE")
            .setView(view)
            .setPositiveButton("CREATE") { _, _ ->
                val name = nameField.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "NAME REQUIRED", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Thread {
                    val repo = BoardRepository(activity)
                    val account = repo.loadLines()
                        .filter { !it.isLocal }
                        .map { it.accountName to it.accountType }
                        .distinct()
                        .firstOrNull()
                    val accountName = account?.first ?: "departa-local"
                    val accountType = account?.second ?: CalendarContract.ACCOUNT_TYPE_LOCAL
                    val color = palette(activity)[chosenIndex]
                    val id = repo.createLine(name, color, accountName, accountType)
                    val line = if (id != null) repo.loadLines().firstOrNull { it.calId == id } else null
                    if (line == null) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "FAILED TO CREATE LINE", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        repo.nudgeSync(line)
                        activity.runOnUiThread {
                            if (!activity.isFinishing) onCreated(line)
                        }
                    }
                }.start()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun palette(activity: Activity): IntArray = intArrayOf(
        activity.getColor(R.color.line_1),
        activity.getColor(R.color.line_2),
        activity.getColor(R.color.line_3),
        activity.getColor(R.color.line_4),
        activity.getColor(R.color.line_5),
        activity.getColor(R.color.line_6),
        activity.getColor(R.color.line_7),
        activity.getColor(R.color.line_8),
        activity.getColor(R.color.line_9),
        activity.getColor(R.color.line_10)
    )
}
