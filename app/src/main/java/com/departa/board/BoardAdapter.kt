package com.departa.board

import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.departa.R
import com.departa.data.Departure
import com.departa.util.Fmt

/** Row model of the board: departures and the NOW divider. Day context
 *  lives in the header (big date + rail), so there are no day rows anymore. */
sealed class Row {
    object Now : Row()
    data class DepartureRow(val d: Departure) : Row()
}

class BoardAdapter(private val ctx: Context) : BaseAdapter() {

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(ctx)

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is Row.DepartureRow -> r.d.instanceId
        is Row.Now -> -1L
    }

    override fun hasStableIds(): Boolean = true
    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.DepartureRow -> TYPE_DEPARTURE
        is Row.Now -> TYPE_NOW
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val r = rows[position]) {
            is Row.DepartureRow -> departureView(convertView, parent, r.d)
            is Row.Now -> nowView(convertView, parent)
        }

    private fun departureView(convertView: View?, parent: ViewGroup, d: Departure): View {
        val v = convertView ?: inflater.inflate(R.layout.item_departure, parent, false)
        val h = (v.tag as? DepHolder) ?: DepHolder(v).also { v.tag = it }

        val now = System.currentTimeMillis()
        val departed = d.end < now
        val active = !departed && d.begin <= now

        h.time.text = if (d.allDay) "--:--" else Fmt.hm(d.begin)
        h.dot.setTextColor(d.line.color)
        h.title.text = (if (d.title.isBlank()) "(untitled)" else d.title).uppercase()

        val sub = StringBuilder(d.line.name.uppercase())
        if (!d.location.isNullOrBlank()) sub.append("  ·  ").append(d.location.uppercase())
        h.sub.text = sub.toString()

        h.status.text = when {
            departed -> "GONE"
            d.allDay -> "ALL DAY"
            active -> "NOW"
            else -> Fmt.countdown(now, d.begin)
        }

        val alpha = if (departed) 0.35f else 1f
        h.time.alpha = alpha
        h.dot.alpha = alpha
        h.title.alpha = alpha
        h.sub.alpha = alpha
        h.status.alpha = alpha

        h.title.paintFlags = if (departed)
            h.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else
            h.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        h.status.setTextColor(ctx.getColor(if (departed) R.color.text_dim else R.color.amber))
        return v
    }

    private fun nowView(convertView: View?, parent: ViewGroup): View =
        convertView ?: inflater.inflate(R.layout.item_now, parent, false)

    private class DepHolder(v: View) {
        val time: TextView = v.findViewById(R.id.time)
        val dot: TextView = v.findViewById(R.id.dot)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val status: TextView = v.findViewById(R.id.status)
    }

    companion object {
        private const val TYPE_DEPARTURE = 0
        private const val TYPE_NOW = 1
    }
}
