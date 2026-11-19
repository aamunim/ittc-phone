package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.phone.helpers.IttcModeAuto

/**
 * 🎓 ITTC 3.12: the office hours' alarms (switch, the notice 10 minutes before) and the notice's
 * "Stay in ITTC" — only this app's own PendingIntents reach it (not exported). Off the main thread.
 */
class IttcModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                IttcModeAuto.onAlarm(app, intent)
            } catch (_: Throwable) {
            } finally {
                result.finish()
            }
        }.start()
    }
}

/**
 * 🎓 ITTC 3.12: the phone started, its time or time zone changed, or the app was updated: the
 * mode's office-hours alarms are set again (a start / end missed meanwhile is done now).
 */
class IttcModeSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                IttcModeAuto.check(app)
            } catch (_: Throwable) {
            } finally {
                result.finish()
            }
        }.start()
    }
}
