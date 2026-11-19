package org.fossify.phone.helpers

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import org.fossify.phone.R
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcNotify
import org.fossify.phone.direct.IttcReminders
import org.fossify.phone.receivers.IttcModeReceiver
import org.fossify.phone.services.IttcModeTileService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.Executors

/**
 * 🎓 ITTC 3.12: what goes with [IttcMode] over time.
 *  - "Switch by office hours": one alarm at the next start / end of the office hours (exact when the
 *    phone allows it, else within a few minutes) and a notice 10 minutes before it ("Switching to
 *    Personal mode at 7:00 PM", with "Stay in ITTC"). A switch by hand holds until the next start /
 *    end. With the admin's "lock ITTC mode in office hours" the start of the hours switches back to
 *    ITTC even without the setting. Set again at boot, on a time / time zone change, when the
 *    settings change and at each start of the app; a start / end missed while the phone was off is
 *    done then.
 *  - Lead reminders and ITTC notifications that came in Personal mode wait here and come on the
 *    way back to ITTC mode (the reminders are set again too).
 *  - The Quick Settings tile is told about each change.
 */
object IttcModeAuto {
    private const val PREFS = "ittc_mode_auto"
    private const val KEY_APPLIED = "applied_at"
    private const val KEY_SKIP = "skip_at"
    private const val KEY_HELD = "held_reminders"
    private const val KEY_HELD_INBOX = "held_inbox"

    const val ACTION_SWITCH = "org.fossify.phone.ittc.MODE_SWITCH"
    const val ACTION_NOTICE = "org.fossify.phone.ittc.MODE_NOTICE"
    const val ACTION_STAY = "org.fossify.phone.ittc.MODE_STAY"
    const val EXTRA_AT = "at"
    const val EXTRA_PERSONAL = "personal"

    private const val CHANNEL = "ittc_mode"
    private const val NOTICE_ID = 7410
    private const val HELD_ID = 7411
    private const val RC_SWITCH = 7400
    private const val RC_NOTICE = 7401
    private const val RC_STAY = 7402
    private const val NOTICE_BEFORE_MS = 10 * 60_000L
    private const val HELD_MAX = 20
    private const val HELD_SHOWN = 5

    private val io = Executors.newSingleThreadExecutor()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The hours matter for this phone: signed in, and the setting on or the admin's lock */
    private fun wanted(c: Context): Boolean =
        IttcDirect.isSignedIn(c) && (IttcMode.autoSwitch(c) || IttcMode.hoursLocked(c))

    fun timeText(c: Context, at: Long): String =
        try {
            android.text.format.DateFormat.getTimeFormat(c).format(Date(at))
        } catch (_: Throwable) {
            IttcMode.Hours.text(((at / 60_000) % (24 * 60)).toInt())
        }

    // ============================================================================= checks
    /**
     * At boot, a time change, the app's start (any thread; reads the sign-in): a start / end of the
     * office hours missed meanwhile is done, the admin's limits apply, the alarms are set again.
     */
    fun check(context: Context) {
        val app = context.applicationContext
        try {
            IttcMode.enforce(app)
            if (wanted(app)) {
                val last = IttcMode.lastBoundary(IttcMode.hours(app))
                val applied = prefs(app).getLong(KEY_APPLIED, 0L)
                if (last != null && last.at > applied) {
                    // (the first time ever: only noted — the mode chosen by hand stays)
                    if (applied == 0L) prefs(app).edit().putLong(KEY_APPLIED, last.at).apply()
                    else apply(app, last)
                }
            }
            reschedule(app)
        } catch (_: Throwable) {
        }
    }

    /** The mode settings changed on the settings screen: the hours' mode now (with the setting on) and new alarms */
    fun settingsChanged(context: Context) {
        val app = context.applicationContext
        io.execute {
            try {
                if (IttcDirect.isSignedIn(app) && IttcMode.autoSwitch(app)) {
                    val h = IttcMode.hours(app)
                    if (!h.empty) {
                        val personal = !IttcMode.inHours(h, System.currentTimeMillis())
                        if (!personal || IttcMode.canBePersonal(app)) IttcMode.setPersonal(app, personal, "auto")
                    }
                    IttcMode.lastBoundary(h)?.let { prefs(app).edit().putLong(KEY_APPLIED, it.at).apply() }
                }
                IttcMode.enforce(app)
                reschedule(app)
            } catch (_: Throwable) {
            }
        }
    }

    /** A start / end of the office hours: the mode it wants (unless "Stay in ITTC" was chosen for it) */
    private fun apply(app: Context, b: IttcMode.Boundary) {
        val p = prefs(app)
        // (each start / end once: an alarm that came twice changes nothing the second time)
        if (b.at <= p.getLong(KEY_APPLIED, 0L)) return
        p.edit().putLong(KEY_APPLIED, b.at).apply()
        if (p.getLong(KEY_SKIP, 0L) == b.at) {
            p.edit().remove(KEY_SKIP).apply()
            return
        }
        if (b.personal) {
            if (IttcMode.autoSwitch(app) && IttcMode.canBePersonal(app)) IttcMode.setPersonal(app, true, "auto")
        } else if (IttcMode.autoSwitch(app) || IttcMode.hoursLocked(app)) {
            IttcMode.setPersonal(app, false, "auto")
        }
    }

    // ============================================================================= alarms
    /**
     * The next start / end's alarm (after [after]) and its notice (old ones go; none when not
     * wanted). Any thread.
     */
    fun reschedule(context: Context, after: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        cancelAlarms(app)
        try {
            if (!wanted(app)) {
                cancelNotice(app)
                return
            }
            val next = IttcMode.nextBoundary(IttcMode.hours(app), after) ?: return
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            setAlarm(am, next.at, pending(app, ACTION_SWITCH, RC_SWITCH, next))
            val noticeAt = next.at - NOTICE_BEFORE_MS
            if (noticeAt > System.currentTimeMillis() + 30_000) setAlarm(am, noticeAt, pending(app, ACTION_NOTICE, RC_NOTICE, next))
        } catch (_: Throwable) {
        }
    }

    /** Signed out: no alarms, no notice */
    fun cancel(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app)
        cancelNotice(app)
        prefs(app).edit().remove(KEY_SKIP).remove(KEY_APPLIED).remove(KEY_HELD).remove(KEY_HELD_INBOX).apply()
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            // (exact when the phone allows apps exact alarms; else Android may move it a few minutes)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    private fun intent(app: Context, action: String) = Intent(app, IttcModeReceiver::class.java).setAction(action)

    private fun pending(app: Context, action: String, code: Int, b: IttcMode.Boundary): PendingIntent =
        PendingIntent.getBroadcast(
            app, code,
            intent(app, action).putExtra(EXTRA_AT, b.at).putExtra(EXTRA_PERSONAL, b.personal),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelAlarms(app: Context) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        listOf(ACTION_SWITCH to RC_SWITCH, ACTION_NOTICE to RC_NOTICE).forEach { (action, code) ->
            try {
                PendingIntent.getBroadcast(app, code, intent(app, action), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    ?.let { am.cancel(it) }
            } catch (_: Throwable) {
            }
        }
    }

    /** IttcModeReceiver: an alarm or the notice's "Stay in ITTC" (off the main thread) */
    fun onAlarm(context: Context, intent: Intent) {
        val app = context.applicationContext
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val personal = intent.getBooleanExtra(EXTRA_PERSONAL, false)
        when (intent.action) {
            ACTION_SWITCH -> {
                cancelNotice(app)
                if (wanted(app) && at > 0) apply(app, IttcMode.Boundary(at, personal))
                // (an alarm a moment early: the next one after this start / end, not this one again)
                reschedule(app, maxOf(System.currentTimeMillis(), at))
            }

            ACTION_NOTICE -> if (wanted(app) && at > System.currentTimeMillis()) showNotice(app, at, personal)
            ACTION_STAY -> {
                // (this switch to Personal is skipped: ITTC until the next start / end)
                if (at > 0) prefs(app).edit().putLong(KEY_SKIP, at).apply()
                cancelNotice(app)
                IttcMode.setPersonal(app, false, "user")
            }
        }
    }

    // ============================================================================= the notice
    private fun showNotice(app: Context, at: Long, personal: Boolean) {
        // (only when the switch will change something)
        if (prefs(app).getLong(KEY_SKIP, 0L) == at) return
        val now = IttcMode.isPersonal(app)
        if (now == personal) return
        if (personal && (!IttcMode.autoSwitch(app) || !IttcMode.admin(app).personalAllowed)) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.ittc_mode_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val time = timeText(app, at)
            val open = PendingIntent.getActivity(
                app, RC_NOTICE,
                Intent().setClassName(app, "org.fossify.phone.activities.IttcModeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val b = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ittc_ic_mode_tile)
                .setContentTitle(app.getString(if (personal) R.string.ittc_mode_notice_personal else R.string.ittc_mode_notice_ittc, time))
                .setContentText(app.getString(if (personal) R.string.ittc_mode_notice_text_personal else R.string.ittc_mode_notice_text_ittc))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter((at - System.currentTimeMillis()).coerceAtLeast(60_000L))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            if (personal) {
                val stay = PendingIntent.getBroadcast(
                    app, RC_STAY,
                    intent(app, ACTION_STAY).putExtra(EXTRA_AT, at).putExtra(EXTRA_PERSONAL, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, app.getString(R.string.ittc_mode_stay), stay)
            }
            nm.notify(NOTICE_ID, b.build())
        } catch (_: Throwable) {
            // (notifications not allowed: the switch still happens)
        }
    }

    private fun cancelNotice(app: Context) {
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
        } catch (_: Throwable) {
        }
    }

    // ============================================================================= waiting in Personal mode
    /** A lead reminder came in Personal mode: kept for the way back (the newest [HELD_MAX]) */
    fun holdReminder(context: Context, i: Intent) {
        val app = context.applicationContext
        try {
            val p = prefs(app)
            val arr = try {
                JSONArray(p.getString(KEY_HELD, "[]"))
            } catch (_: Throwable) {
                JSONArray()
            }
            val o = JSONObject()
            listOf("lead_id", "name", "number", "line").forEach { k -> i.getStringExtra(k)?.let { o.put(k, it) } }
            if (!o.has("number")) return
            // (the same lead once: its newest)
            val kept = JSONArray()
            for (n in 0 until arr.length()) {
                val old = arr.optJSONObject(n) ?: continue
                if (old.optString("lead_id") != o.optString("lead_id") || old.optString("number") != o.optString("number")) kept.put(old)
            }
            kept.put(o)
            val out = JSONArray()
            for (n in maxOf(0, kept.length() - HELD_MAX) until kept.length()) out.put(kept.get(n))
            p.edit().putString(KEY_HELD, out.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** An ITTC notification came in Personal mode (it is in the inbox): counted for the way back */
    fun holdInbox(context: Context) {
        val p = prefs(context.applicationContext)
        p.edit().putInt(KEY_HELD_INBOX, p.getInt(KEY_HELD_INBOX, 0) + 1).apply()
    }

    /** IttcMode.setPersonal: the tile, and back in ITTC mode what waited */
    internal fun modeChanged(app: Context, personal: Boolean, reason: String) {
        try {
            TileService.requestListeningState(app, ComponentName(app, IttcModeTileService::class.java))
        } catch (_: Throwable) {
        }
        if (!personal) {
            cancelNotice(app)
            if (reason == "signout") return
            io.execute { deliverHeld(app) }
        }
    }

    private fun deliverHeld(app: Context) {
        try {
            val p = prefs(app)
            val held = try {
                JSONArray(p.getString(KEY_HELD, "[]"))
            } catch (_: Throwable) {
                JSONArray()
            }
            val inbox = p.getInt(KEY_HELD_INBOX, 0)
            p.edit().remove(KEY_HELD).remove(KEY_HELD_INBOX).apply()
            // (the reminders that rang in Personal mode, the newest few)
            for (n in maxOf(0, held.length() - HELD_SHOWN) until held.length()) {
                val o = held.optJSONObject(n) ?: continue
                val i = Intent()
                o.keys().forEach { k -> i.putExtra(k, o.optString(k)) }
                IttcReminders.show(app, i)
            }
            // (the alarms again, from the list already kept)
            val direct = IttcDirect.isActive(app)
            if (direct || IttcReminders.chosen(app)) {
                val leads = if (direct) IttcDirect.leads(app) else IttcBridge.myLeadsCache
                if (leads.isNotEmpty()) IttcReminders.schedule(app, leads, ownersKnown = direct || IttcBridge.cacheIsDirect)
            }
            if (inbox > 0) IttcNotify.postWaiting(app, inbox)
        } catch (_: Throwable) {
        }
    }
}
