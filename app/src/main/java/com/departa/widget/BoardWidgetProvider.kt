package com.departa.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.departa.BoardActivity
import com.departa.EditActivity
import com.departa.R

/**
 * 4x2 home-screen board: header opens the app, rows open the event.
 * Refreshes every 30 minutes (system minimum) plus instantly whenever
 * the app changes something.
 */
class BoardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val rv = RemoteViews(context.packageName, R.layout.widget_board)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, BoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_header, open)

            // Collection template must be mutable: the system injects per-row extras.
            val template = PendingIntent.getActivity(
                context, 1,
                Intent(context, EditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            rv.setPendingIntentTemplate(R.id.widget_list, template)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)
            rv.setRemoteAdapter(R.id.widget_list, Intent(context, BoardWidgetService::class.java))

            appWidgetManager.updateAppWidget(id, rv)
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        /** Push-refresh all widgets after a data change. Cheap no-op if none installed. */
        fun refresh(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            val ids = awm.getAppWidgetIds(ComponentName(context, BoardWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                awm.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }
    }
}
