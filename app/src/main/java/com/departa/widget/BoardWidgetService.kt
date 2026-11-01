package com.departa.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.departa.EditActivity
import com.departa.R
import com.departa.data.BoardRepository
import com.departa.util.Fmt

class BoardWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

        private var rows: List<com.departa.data.Departure> = emptyList()

        override fun onCreate() {}

        override fun onDestroy() {}

        /**
         * Runs on the main thread of our process — by design: the query is a
         * single indexed SQLite scan for ~8 rows (a few ms), cheaper than
         * spawning a thread per widget refresh.
         */
        override fun onDataSetChanged() {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                rows = emptyList()
                return
            }
            val now = System.currentTimeMillis()
            val repo = BoardRepository(ctx)
            rows = repo.loadDepartures(now, now + 24L * 60L * 60L * 1000L, repo.loadLines())
                .take(WIDGET_ROWS)
        }

        override fun getCount(): Int = rows.size

        override fun getItemId(position: Int): Long =
            rows.getOrNull(position)?.eventId ?: 0L

        override fun hasStableIds(): Boolean = true

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
            val d = rows.getOrNull(position) ?: return rv
            rv.setTextViewText(R.id.w_time, if (d.allDay) "ALL" else Fmt.hm(d.begin))
            rv.setTextViewText(R.id.w_dot, "●")
            rv.setTextColor(R.id.w_dot, d.line.color)
            rv.setTextViewText(R.id.w_title, d.title.ifBlank { "(untitled)" }.uppercase())
            rv.setTextViewText(R.id.w_status, Fmt.countdown(System.currentTimeMillis(), d.begin))
            val fill = Intent()
            fill.putExtra(EditActivity.EXTRA_EVENT_ID, d.eventId)
            rv.setOnClickFillInIntent(R.id.w_row, fill)
            return rv
        }

        override fun getLoadingView(): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_item)

        override fun getViewTypeCount(): Int = 1

        companion object {
            private const val WIDGET_ROWS = 8
        }
    }
}
