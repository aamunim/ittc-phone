package org.fossify.phone.direct

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.fossify.phone.R
import org.fossify.phone.helpers.IttcBridge

/**
 * ⏰ Follow-up reminders (3.2, with or without the Tracker): at a lead's next-call time a
 * notification "⏰ Call Rahim now · CSE" with Call and "In 15 min". Uses the phone's own alarm
 * (no internet needed); set again whenever the Leads list comes in. Only the person's own leads,
 * only the next 24 hours, at most 60.
 */
object IttcReminders {
    private const val CHANNEL = "ittc_reminders"
    private const val PREFS = "ittc_reminders"
    private const val MAX = 60

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)

    /** The person turned them on or off themselves (with the Tracker, they are off until then) */
    fun chosen(c: Context): Boolean = prefs(c).getBoolean("chosen", false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).putBoolean("chosen", true).apply()
        if (!on) cancelAll(c) else schedule(c, IttcBridge.myLeadsCache, IttcBridge.cacheIsDirect)
    }

    /**
     * Set the alarms for these leads (my own, due in the next 24 h); the old ones go.
     * [ownersKnown]: the list is the server's own (direct mode), which always says whose — every
     * lead in it may be someone else's or unassigned.
     */
    fun schedule(context: Context, leads: List<IttcBridge.PhoneLead>, ownersKnown: Boolean = false) {
        val app = context.applicationContext
        cancelAll(app)
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        val me = (if (IttcDirect.isSignedIn(app)) IttcDirect.meta(app) else null)?.meId ?: IttcBridge.metaCache?.meId
        // (3.11: the list may hold everyone's I may see: only my own ring)
        val due = IttcBridge.ownOnly(leads, me, ownersKnown).filter { l ->
            val at = l.nextCall ?: return@filter false
            at > now && at < now + 24 * 3600_000L && l.callNumber != null
        }.sortedBy { it.nextCall }.take(MAX)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val ids = HashSet<String>()
        due.forEachIndexed { i, l ->
            val pi = alarmIntent(app, i, l.leadId, l.name, l.callNumber!!, listOfNotNull(l.course, l.stage).joinToString(" · "))
            try {
                // (inexact by a minute or two: no special "exact alarm" permission needed)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, l.nextCall!!, pi)
                ids += i.toString()
            } catch (_: Throwable) {
            }
        }
        prefs(app).edit().putStringSet("ids", ids).apply()
    }

    fun cancelAll(context: Context, notifications: Boolean = false) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        if (notifications) {
            // (signed out: the snoozed one and those on screen go too)
            PendingIntent.getBroadcast(
                app, 7299, Intent(app, IttcReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { am.cancel(it) }
            try {
                val nm = app.getSystemService(NotificationManager::class.java)
                nm?.activeNotifications?.filter { it.notification.channelId == CHANNEL }?.forEach { nm.cancel(it.id) }
            } catch (_: Throwable) {
            }
        }
        prefs(app).getStringSet("ids", emptySet())?.forEach { id ->
            id.toIntOrNull()?.let { i ->
                PendingIntent.getBroadcast(
                    app, 7300 + i, Intent(app, IttcReminderReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )?.let { am.cancel(it) }
            }
        }
        prefs(app).edit().remove("ids").apply()
    }

    private fun alarmIntent(c: Context, slot: Int, leadId: String, name: String, number: String, line: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, 7300 + slot,
            Intent(c, IttcReminderReceiver::class.java)
                .putExtra("lead_id", leadId).putExtra("name", name).putExtra("number", number).putExtra("line", line),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** In 15 minutes again (the "In 15 min" button) */
    internal fun snooze(c: Context, i: Intent) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            c, 7299, Intent(c, IttcReminderReceiver::class.java).putExtras(i),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60_000, pi)
        } catch (_: Throwable) {
        }
    }

    internal fun show(c: Context, i: Intent) {
        // (🎓 3.12: Personal mode — it waits, and rings on the way back to ITTC mode)
        if (org.fossify.phone.helpers.IttcMode.isPersonal(c)) {
            org.fossify.phone.helpers.IttcModeAuto.holdReminder(c, i)
            return
        }
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Follow-up reminders", NotificationManager.IMPORTANCE_HIGH))
        val number = i.getStringExtra("number") ?: return
        val name = i.getStringExtra("name").orEmpty().ifEmpty { number }
        val nid = (i.getStringExtra("lead_id") ?: number).hashCode()
        val call = PendingIntent.getActivity(
            c, nid, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).setPackage(c.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // (a tap opens the number's page — the lead, its calls — never dials by itself)
        val open = PendingIntent.getActivity(
            c, nid + 2,
            Intent(c, org.fossify.phone.activities.IttcNumberActivity::class.java)
                .putExtra("ittc_number", number).putExtra("ittc_name", name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            c, nid + 1, Intent(c, IttcReminderReceiver::class.java).putExtras(i).putExtra("snooze", true).putExtra("nid", nid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(org.fossify.commons.R.drawable.ic_phone_vector)
            .setContentTitle("⏰ Call $name now")
            .setContentText(i.getStringExtra("line").orEmpty().ifEmpty { number })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "📞 Call", call)
            .addAction(0, "In 15 min", later)
            .build()
        try {
            nm.notify(nid, n)
        } catch (_: SecurityException) {
            // (notifications not allowed: nothing to do)
        }
    }
}

class IttcReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("snooze", false)) {
            context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("nid", 0))
            IttcReminders.snooze(context, Intent().putExtras(intent).apply { removeExtra("snooze"); removeExtra("nid") })
            return
        }
        IttcReminders.show(context, intent)
    }
}
