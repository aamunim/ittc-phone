package org.fossify.phone.helpers

import android.content.Context
import android.os.SystemClock

/**
 * 🎓 ITTC 3.13: how long Call History took to load, the last time (shown small at the bottom of
 * the side panel), so a slow phone's numbers can be compared before and after a change.
 *   quick = the newest calls (what is seen first) · full = the whole log after them
 */
object IttcPerf {
    private const val PREFS = "ittc_perf"
    private const val KEY_QUICK = "recents_quick_ms"
    private const val KEY_FULL = "recents_full_ms"

    fun now(): Long = SystemClock.elapsedRealtime()

    fun recentsLoaded(context: Context, full: Boolean, startedAt: Long) {
        val ms = (now() - startedAt).coerceAtLeast(0)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(if (full) KEY_FULL else KEY_QUICK, ms).apply()
    }

    /** "Call history: 0.4 s · all 1.1 s", or null before the first load */
    fun recentsText(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val quick = prefs.getLong(KEY_QUICK, -1)
        if (quick < 0) return null
        val full = prefs.getLong(KEY_FULL, -1)
        val s = { ms: Long -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) }
        return if (full >= 0) "Call history: ${s(quick)} · all ${s(full)}" else "Call history: ${s(quick)}"
    }
}
