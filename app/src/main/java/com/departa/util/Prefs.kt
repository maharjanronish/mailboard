package com.departa.util

import android.content.Context

/** App-local board preferences (line visibility filter). */
object Prefs {

    private const val FILE = "board"
    private const val KEY_HIDDEN = "hidden_lines"

    fun hiddenLines(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun setHidden(ctx: Context, calId: Long, hidden: Boolean) {
        val cur = hiddenLines(ctx).toMutableSet()
        if (hidden) cur.add(calId.toString()) else cur.remove(calId.toString())
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_HIDDEN, cur)
            .apply()
    }
}
