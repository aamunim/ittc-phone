package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog.Calls
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.RecentCall

/**
 * 🎓 ITTC: "one row per number" in Recents and the per-number call history page.
 * One number is one key: digits only, and a Bangladeshi "+8801…" / "008801…" is "01…".
 */
object IttcCallHistory {
    const val PREFS = "ittc"
    const val KEY_ONE_PER_NUMBER = "recents_one_per_number"
    const val KEY_KIND = "recents_kind"
    const val KEY_TAP_CALLS = "recents_tap_calls"

    const val KIND_ALL = 0
    const val KIND_MISSED = 1
    const val KIND_INCOMING = 2
    const val KIND_OUTGOING = 3

    /** Something changed the call log from elsewhere (the history page): Recents reads it again in full */
    @Volatile
    var recentsDirty = false

    private const val UNKNOWN_KEY = "\u0000unknown"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onePerNumber(context: Context) = prefs(context).getBoolean(KEY_ONE_PER_NUMBER, true)

    fun kind(context: Context) = prefs(context).getInt(KEY_KIND, KIND_ALL)

    /**
     * 3.5: a tap on a Recents row always calls (as Fossify does); the history opens from the row's →
     * button. The old setting ([KEY_TAP_CALLS]) is no longer read.
     */
    @Suppress("UNUSED_PARAMETER")
    fun tapCalls(context: Context) = true

    fun takeDirty(): Boolean {
        val dirty = recentsDirty
        recentsDirty = false
        return dirty
    }

    /** The number's key ("" when it has no usable digits, e.g. a hidden number) */
    fun key(number: String?): String {
        if (number.isNullOrBlank() || number == "-1") return ""
        val digits = number.filter { it in '0'..'9' }
        return when {
            digits.length == 13 && digits.startsWith("8801") -> digits.substring(2)
            digits.length == 15 && digits.startsWith("008801") -> digits.substring(4)
            else -> digits
        }
    }

    private fun keyOf(call: RecentCall): String =
        if (call.isUnknownNumber) UNKNOWN_KEY else key(call.phoneNumber).ifEmpty { UNKNOWN_KEY }

    fun matchesKind(type: Int, kind: Int): Boolean = when (kind) {
        KIND_MISSED -> type == Calls.MISSED_TYPE
        KIND_OUTGOING -> type == Calls.OUTGOING_TYPE
        // (the same split as the Recents icons: neither missed nor outgoing is incoming)
        KIND_INCOMING -> type != Calls.MISSED_TYPE && type != Calls.OUTGOING_TYPE
        else -> true
    }

    /**
     * Each number once (call off the main thread): the newest call is the row (its id, time, icon
     * and place); all the number's calls, newest first, are its groupedCalls when there are 2+.
     * Rows may already be "subsequent calls" groups: they are opened up first.
     */
    fun groupByNumber(rows: List<RecentCall>, kind: Int = KIND_ALL): List<RecentCall> {
        val groups = LinkedHashMap<String, MutableList<RecentCall>>()
        for (row in rows) {
            val calls = row.groupedCalls ?: listOf(row)
            for (call in calls) {
                if (kind != KIND_ALL && !matchesKind(call.type, kind)) continue
                val single = if (call.groupedCalls == null) call else call.copy(groupedCalls = null)
                groups.getOrPut(keyOf(single)) { ArrayList() }.add(single)
            }
        }

        val result = ArrayList<RecentCall>(groups.size)
        for (calls in groups.values) {
            val sorted = calls.distinctBy { it.id }.sortedByDescending { it.startTS }
            val newest = sorted.first()
            result += if (sorted.size > 1) newest.copy(groupedCalls = sorted.toMutableList()) else newest
        }

        result.sortByDescending { it.startTS }
        return result
    }

    /** Missed calls newer than the number's last answered or outgoing call */
    fun newMissed(row: RecentCall): Int {
        var missed = 0
        for (call in row.groupedCalls ?: listOf(row)) {
            when (call.type) {
                Calls.MISSED_TYPE -> missed++
                Calls.INCOMING_TYPE, Calls.OUTGOING_TYPE -> return missed
            }
        }
        return missed
    }

    data class Entry(
        val id: Int,
        val number: String,
        val startTS: Long,
        val duration: Int,
        val type: Int,
        val simID: Int,
    )

    /** All calls with this number (same key), newest first; off the main thread */
    @SuppressLint("MissingPermission") // (READ_CALL_LOG checked first)
    fun loadCalls(context: Context, number: String): List<Entry> {
        val wanted = key(number)
        if (wanted.isEmpty() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) return emptyList()

        val simIds = HashMap<String, Int>()
        context.getAvailableSIMCardLabels().forEach { simIds[it.handle.id] = it.id }

        val projection = arrayOf(Calls._ID, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.PHONE_ACCOUNT_ID)
        val entries = ArrayList<Entry>()
        try {
            // (the whole log, matched here: the same number is stored as "01…", "+8801…", with spaces…)
            context.contentResolver.query(Calls.CONTENT_URI, projection, null, null, "${Calls.DATE} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val callNumber = cursor.getStringValueOrNull(Calls.NUMBER) ?: continue
                    if (key(callNumber) != wanted) continue
                    entries += Entry(
                        id = cursor.getIntValue(Calls._ID),
                        number = callNumber,
                        startTS = cursor.getLongValue(Calls.DATE),
                        duration = cursor.getIntValue(Calls.DURATION),
                        type = cursor.getIntValue(Calls.TYPE),
                        simID = cursor.getStringValueOrNull(Calls.PHONE_ACCOUNT_ID)?.let { simIds[it] } ?: -1,
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return entries
    }

    /** The call-log ids of every call with one of these numbers (only those of [kind]); off the main thread */
    @SuppressLint("MissingPermission") // (READ_CALL_LOG checked first)
    fun idsFor(context: Context, numbers: Collection<String>, kind: Int = KIND_ALL): List<Int> {
        val keys = numbers.map { key(it) }.filter { it.isNotEmpty() }.toHashSet()
        if (keys.isEmpty() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) return emptyList()

        val ids = ArrayList<Int>()
        try {
            context.contentResolver.query(Calls.CONTENT_URI, arrayOf(Calls._ID, Calls.NUMBER, Calls.TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (key(cursor.getStringValueOrNull(Calls.NUMBER)) !in keys) continue
                    if (kind != KIND_ALL && !matchesKind(cursor.getIntValue(Calls.TYPE), kind)) continue
                    ids += cursor.getIntValue(Calls._ID)
                }
            }
        } catch (_: Throwable) {
        }
        return ids
    }
}
