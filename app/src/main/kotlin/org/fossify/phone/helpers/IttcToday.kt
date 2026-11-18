package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import org.fossify.phone.direct.IttcApi
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 📊 "Today" for the Leads tab and the team screen.
 * Direct mode: the server's phone_today() ({me{calls,connected,talk_sec,stage_changes,new_leads},
 * target, team[…]}), kept a minute in memory and for the day in the prefs (offline: the last numbers
 * stay). Tracker mode: counted from the phone's own call log.
 * Call everything here off the main thread.
 */
object IttcToday {
    private const val PREFS = "ittc_today"

    @Volatile private var cached: JSONObject? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedDay: String? = null

    /** The last fetch failed (the numbers shown are older ones) */
    @Volatile
    var offline = false
        private set

    /** When the numbers kept were fetched (0: not in this run) */
    @Volatile
    var fetchedAt = 0L
        private set

    /** The last error's message (null: the last fetch worked) */
    @Volatile
    var lastError: String? = null
        private set

    /** Signed out: nothing of the last person's day stays */
    fun clear(context: Context) {
        cached = null
        cachedAt = 0L
        cachedDay = null
        fetchedAt = 0L
        lastError = null
        offline = false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * The day's numbers: fresh when older than [maxAgeMs] (or [force]); on an error the last ones of
     * today (null: none yet / signed out)
     */
    fun direct(context: Context, maxAgeMs: Long = 60_000, force: Boolean = false): JSONObject? {
        val app = context.applicationContext
        val today = day()
        if (cachedDay != today) {
            cached = null
            cachedAt = 0
            cachedDay = today
            // (the day's last numbers, from before the app was closed)
            try {
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (p.getString("day", null) == today) cached = p.getString("json", null)?.let { JSONObject(it) }
            } catch (_: Throwable) {
            }
        }
        val now = System.currentTimeMillis()
        if (!force && cachedAt > 0 && now - cachedAt < maxAgeMs) return cached
        return try {
            val text = IttcApi.rpc(app, "phone_today")
            val o = if (text.isBlank() || text.trim() == "null") null else JSONObject(text)
            cached = o
            cachedAt = now
            fetchedAt = now
            offline = false
            lastError = null
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("day", today).putString("json", o?.toString()).apply()
            o
        } catch (e: Throwable) {
            offline = true
            lastError = (e as? IttcApi.ApiError)?.takeIf { it.status != 0 }?.message ?: ""
            // (try again on the next look, not before 15 s)
            cachedAt = now - maxAgeMs + 15_000
            cached
        }
    }

    /** Kept numbers only, no network (null: none for today) */
    fun peek(): JSONObject? = if (cachedDay == day()) cached else null

    /** Today in the phone's call log: calls, answered ones, seconds talked (null: no access) */
    @SuppressLint("MissingPermission")
    fun fromCallLog(context: Context): IntArray? {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var calls = 0
        var connected = 0
        var seconds = 0L
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(start.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    calls++
                    val d = c.getLong(0)
                    if (d > 0) {
                        connected++
                        seconds += d
                    }
                }
            } ?: return null
        } catch (_: Throwable) {
            return null
        }
        return intArrayOf(calls, connected, seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    fun duration(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600}h ${sec % 3600 / 60}m"
        sec >= 60 -> "${sec / 60}m"
        else -> "${sec}s"
    }
}
