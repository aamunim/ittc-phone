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

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        if (!on) cancelAll(c) else schedule(c, IttcBridge.myLeadsCache)
    }

    /** Set the alarms for these leads (my own, due in the next 24 h); the old ones go */
    fun schedule(context: Context, leads: List<IttcBridge.PhoneLead>) {
        val app = context.applicationContext
        cancelAll(app)
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        val me = IttcBridge.metaCache?.meId
        val due = leads.filter { l ->
            val at = l.nextCall ?: return@filter false
            at > now && at < now + 24 * 3600_000L && (me == null || l.assignedTo == null || l.assignedTo == me) && l.callNumber != null
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

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
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
        val later = PendingIntent.getBroadcast(
            c, nid + 1, Intent(c, IttcReminderReceiver::class.java).putExtras(i).putExtra("snooze", true).putExtra("nid", nid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(org.fossify.commons.R.drawable.ic_phone_vector)
            .setContentTitle("⏰ Call $name now")
            .setContentText(i.getStringExtra("line").orEmpty().ifEmpty { number })
            .setContentIntent(call)
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
