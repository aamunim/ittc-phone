package org.fossify.phone.helpers

import android.content.Context
import android.os.SystemClock

/**
 * 🎓 ITTC 3.13: how long Call History took to load (shown small at the bottom of the side panel), so
 * a slow phone's numbers can be compared before and after a change.
 *   first = the first load since the app was started (the slow one: nothing read yet)
 *   last  = the latest load (later ones are quicker: only the new calls are read)
 */
object IttcPerf {
    private const val PREFS = "ittc_perf"
    private const val KEY_FIRST = "recents_first_ms"
    private const val KEY_FIRST_ALL = "recents_first_all_ms"
    private const val KEY_LAST = "recents_last_ms"

    @Volatile private var firstDone = false
    @Volatile private var firstAllDone = false

    fun now(): Long = SystemClock.elapsedRealtime()

    /** full = the whole log (read after the newest calls are shown) */
    fun recentsLoaded(context: Context, full: Boolean, startedAt: Long) {
        val ms = (now() - startedAt).coerceAtLeast(0)
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (full) {
            if (!firstAllDone) {
                firstAllDone = true
                edit.putLong(KEY_FIRST_ALL, ms)
            }
        } else {
            if (!firstDone) {
                firstDone = true
                edit.putLong(KEY_FIRST, ms)
            }
            edit.putLong(KEY_LAST, ms)
        }
        edit.apply()
    }

    /** "Call history: first 1.2 s (all 2.0 s) · last 0.3 s", or null before the first load */
    fun recentsText(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val first = prefs.getLong(KEY_FIRST, -1)
        if (first < 0) return null
        val all = prefs.getLong(KEY_FIRST_ALL, -1)
        val last = prefs.getLong(KEY_LAST, -1)
        val s = { ms: Long -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) }
        val firstText = if (all >= 0) "first ${s(first)} (all ${s(all)})" else "first ${s(first)}"
        return if (last >= 0) "Call history: $firstText · last ${s(last)}" else "Call history: $firstText"
    }
}
