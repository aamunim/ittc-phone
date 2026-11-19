package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.phone.extensions.config
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 🎓 ITTC 3.8: the numbers on the main screen's tabs.
 * - Call History: missed calls since the tab was last looked at ([missedSince], a light call log count).
 * - Leads: my leads due today or overdue, counted from the list the Leads tab last read
 *   ([IttcBridge.myLeadsCache]). Whoever reads a new leads list calls [leadsChanged] so the badge
 *   follows at once (MainActivity also counts again on every return to the screen).
 */
object IttcTabBadges {
    /** Leads due today or overdue (mine) as last counted */
    @Volatile
    var leadsDue: Int = 0
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** [listener] is called on the main thread when [leadsDue] changes */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * A new leads list was read (IttcBridge.myLeads / phoneMeta updated their caches): count again.
     * Any thread; cheap (one pass over the list).
     */
    fun leadsChanged() {
        val n = try {
            countDue(IttcBridge.myLeadsCache, IttcBridge.metaCache)
        } catch (_: Throwable) {
            0
        }
        if (n == leadsDue) return
        leadsDue = n
        main.post { listeners.forEach { it() } }
    }

    private fun countDue(leads: List<IttcBridge.PhoneLead>, meta: IttcBridge.PhoneMeta?): Int {
        if (leads.isEmpty()) return 0
        val tomorrow = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        // (the list may hold others' leads — a super admin's, 3.11's All list: only my own count, whatever the tab shows)
        return IttcBridge.ownOnly(leads, meta?.meId, ownersKnown = IttcBridge.cacheIsDirect).count { l ->
            val next = l.nextCall
            next != null && next < tomorrow
        }
    }

    /**
     * Missed calls in the call log after [since] (ms). Off the main thread. 0 without the permission
     * or on any error.
     */
    @SuppressLint("MissingPermission")
    fun missedSince(context: Context, since: Long): Int {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return 0
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
                null
            )?.use { it.count } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    /** The Call History badge's count (off the main thread); the first time ever: from now on */
    fun missedNotSeen(context: Context): Int {
        val config = context.config
        val seen = config.ittcRecentsSeenAt
        if (seen <= 0L) {
            config.ittcRecentsSeenAt = System.currentTimeMillis()
            return 0
        }
        return missedSince(context, seen)
    }
}
