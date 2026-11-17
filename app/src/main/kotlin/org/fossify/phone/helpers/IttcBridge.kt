package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.Call
import android.telecom.DisconnectCause
import java.util.concurrent.Executors

/**
 * 🎓 ITTC Phone ↔ ITTC Admission Tracker (package com.abamunim.leads).
 *
 * The Tracker is a separate, private app. The two only talk through Android:
 *  - "is this number a lead?"  content://com.abamunim.leads.leadlookup/number/<number>
 *  - "a call ended"            a broadcast to the Tracker with the call log's _ID for the call
 * Both are closed to every other app by a signature permission (same signing key).
 * The full contract is in docs/ITTC.md. Nothing here ever leaves the phone.
 */
object IttcBridge {
    private const val TRACKER = "com.abamunim.leads"
    private const val PERMISSION = "com.abamunim.leads.permission.ITTC_PHONE"
    private const val LOOKUP = "content://com.abamunim.leads.leadlookup/number/"
    private const val ACTION_CALL_ENDED = "com.abamunim.leads.ITTC_PHONE_CALL_ENDED"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Lead(
        val leadId: String,
        val name: String,
        val rating: String?,
        val stage: String?,
        val course: String?,
        val lastNote: String?,
    ) {
        /** "🎓 Lead · Course · Stage" and the last note under it */
        fun summary(badge: String): String {
            val top = listOfNotNull(badge, course, stage, rating?.let { "★ $it" }).joinToString(" · ")
            return if (lastNote.isNullOrBlank()) top else "$top\n“${lastNote.take(120)}”"
        }
    }

    /** The Tracker is installed with our key (it can answer and listen) */
    private fun trackerReady(context: Context): Boolean =
        try {
            context.packageManager.checkSignatures(context.packageName, TRACKER) == PackageManager.SIGNATURE_MATCH
        } catch (_: Throwable) {
            false
        }

    private fun clean(number: String?): String? =
        number?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 6 }

    /** The lead with this number (off the main thread; the answer on the main thread, null if none) */
    fun lookup(context: Context, number: String?, done: (Lead?) -> Unit) {
        val app = context.applicationContext
        val n = clean(number)
        if (n == null) {
            done(null)
            return
        }
        io.execute {
            val lead = try {
                if (!trackerReady(app)) null else query(app, n)
            } catch (_: Throwable) {
                null
            }
            main.post { done(lead) }
        }
    }

    private fun query(context: Context, number: String): Lead? {
        val uri = Uri.parse(LOOKUP + Uri.encode(number))
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            fun col(name: String): String? {
                val i = c.getColumnIndex(name)
                return if (i < 0 || c.isNull(i)) null else c.getString(i)?.takeIf { it.isNotBlank() && it != "null" }
            }
            val id = col("lead_id") ?: return null
            return Lead(
                leadId = id,
                name = col("name") ?: "",
                rating = col("rating"),
                stage = col("stage"),
                course = col("course"),
                lastNote = col("last_note"),
            )
        }
        return null
    }

    // -------------------------------------------------------------------
    // A call ended: find its row in the call log (the system writes it a
    // moment later), then tell the Tracker which row it is.
    // -------------------------------------------------------------------
    fun onCallEnded(context: Context, call: Call) {
        val app = context.applicationContext
        val details = try {
            call.details
        } catch (_: Throwable) {
            null
        } ?: return
        val number = clean(details.handle?.schemeSpecificPart) ?: return
        val startedAt = details.creationTimeMillis.takeIf { it > 0 } ?: (System.currentTimeMillis() - 60_000)
        val reason = endReason(details.disconnectCause)
        io.execute {
            try {
                if (!trackerReady(app)) return@execute
                // only lead calls: a personal call is never handed over
                if (query(app, number) == null) return@execute
                val id = findRow(app, number, startedAt) ?: return@execute
                val intent = Intent(ACTION_CALL_ENDED)
                    .setPackage(TRACKER)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("call_log_id", id)
                    .putExtra("number", number)
                if (reason != null) intent.putExtra("end_reason", reason)
                app.sendBroadcast(intent, PERMISSION)
            } catch (_: Throwable) {
                // never break the phone because of the Tracker; its own sync is the backup
            }
        }
    }

    /** The call log _ID of this call: the newest row for the number since it began (waits up to ~12 s) */
    @SuppressLint("MissingPermission")
    private fun findRow(context: Context, number: String, startedAt: Long): String? {
        val wanted = number.filter { it.isDigit() }.takeLast(10)
        repeat(8) { attempt ->
            if (attempt > 0) Thread.sleep(1_500)
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                    "${CallLog.Calls.DATE} >= ?",
                    arrayOf((startedAt - 10_000).toString()),
                    "${CallLog.Calls.DATE} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(1)?.filter { it.isDigit() }?.takeLast(10) ?: continue
                        if (n == wanted) return c.getLong(0).toString()
                    }
                }
            } catch (_: SecurityException) {
                return null // no call log access: the Tracker's own sync will find it
            } catch (_: Throwable) {
                // try again
            }
        }
        return null
    }

    private fun endReason(cause: DisconnectCause?): String? = when (cause?.code) {
        null -> null
        DisconnectCause.LOCAL -> "hung_up"
        DisconnectCause.REMOTE -> "they_hung_up"
        DisconnectCause.BUSY -> "busy"
        DisconnectCause.MISSED -> "missed"
        DisconnectCause.REJECTED -> "declined"
        DisconnectCause.CANCELED -> "cancelled"
        DisconnectCause.ERROR -> "error"
        DisconnectCause.RESTRICTED -> "restricted"
        DisconnectCause.ANSWERED_ELSEWHERE -> "answered_elsewhere"
        DisconnectCause.CALL_PULLED -> "pulled"
        else -> null
    }
}
