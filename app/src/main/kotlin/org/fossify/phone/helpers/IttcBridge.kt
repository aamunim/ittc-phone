package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.Call
import android.telecom.DisconnectCause
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 🎓 ITTC Phone ↔ ITTC Admission Tracker (package com.abamunim.leads).
 *
 * The Tracker is a separate, private app. The two only talk through its provider:
 *  - "is this number a lead?"  query content://com.abamunim.leads.leadlookup/number/<number>
 *  - "a call ended"            call(…, "call_ended", <the call log's _ID for the call>, …)
 * Each side checks the other's signing certificate against a fixed list (the Tracker comes from
 * Google Play, re-signed with Google's key). The full contract is in docs/ITTC.md.
 * Nothing here ever leaves the phone.
 */
object IttcBridge {
    private const val TRACKER = "com.abamunim.leads"
    private const val AUTHORITY = "com.abamunim.leads.leadlookup"
    private const val PROVIDER = "content://$AUTHORITY"
    private const val LOOKUP = "$PROVIDER/number/"

    /** SHA-256 of the Tracker's signing certificates: Google Play's app signing key, and the upload key (APK builds) */
    private val TRACKER_CERTS = setOf(
        "d07ea3e9d65a0df3027e771b1b14e79833fc1c87cadae7810202e395e28e4e54",
        "2955a704b8bab01f1fb8ed467a07ce3906cc46f783c33ba098b30e74f4710d83",
    )

    // (the call screen's lookups never wait behind a call-log search)
    private val io = Executors.newSingleThreadExecutor()
    private val ended = Executors.newSingleThreadExecutor()
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

    /**
     * The real Tracker is installed and owns the provider (its certificate is on the list:
     * nothing is ever sent to another app)
     */
    @Suppress("DEPRECATION")
    private fun trackerReady(context: Context): Boolean =
        try {
            val pm = context.packageManager
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val s = pm.getPackageInfo(TRACKER, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (s == null) emptyArray() else if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
            } else {
                pm.getPackageInfo(TRACKER, PackageManager.GET_SIGNATURES).signatures
            } ?: emptyArray()
            pm.resolveContentProvider(AUTHORITY, 0)?.packageName == TRACKER &&
                certs.any { sha256(it.toByteArray()) in TRACKER_CERTS }
        } catch (_: Throwable) {
            false
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

    /**
     * The Tracker's provider as an "unstable" client: if the Tracker dies mid-request we get an
     * exception (caught) instead of Android killing the phone app with it.
     */
    private fun <T> withTracker(context: Context, block: (android.content.ContentProviderClient) -> T?): T? {
        val client = context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY) ?: return null
        return try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun query(context: Context, number: String): Lead? = withTracker(context) { client ->
        val uri = Uri.parse(LOOKUP + Uri.encode(number))
        // (ask the Tracker to give up after 3 s, so a slow one holds up the next calls less)
        val cancel = CancellationSignal()
        val timeout = Runnable { cancel.cancel() }
        main.postDelayed(timeout, 3_000)
        try {
            readLead(client.query(uri, null, null, null, null, cancel))
        } finally {
            main.removeCallbacks(timeout)
        }
    }

    private fun readLead(cursor: android.database.Cursor?): Lead? {
        cursor?.use { c ->
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
        // (never in the way of the phone: anything going wrong here is dropped)
        try {
            handOver(context, call)
        } catch (_: Throwable) {
        }
    }

    private fun handOver(context: Context, call: Call) {
        val app = context.applicationContext
        val details = try {
            call.details
        } catch (_: Throwable) {
            null
        } ?: return
        val number = clean(details.handle?.schemeSpecificPart) ?: return
        val startedAt = details.creationTimeMillis.takeIf { it > 0 } ?: (System.currentTimeMillis() - 60_000)
        val reason = endReason(details.disconnectCause)
        ended.execute {
            try {
                if (!trackerReady(app)) return@execute
                // only lead calls: a personal call is never handed over
                if (query(app, number) == null) return@execute
                val id = findRow(app, number, startedAt) ?: return@execute
                val extras = Bundle().apply {
                    putString("number", number)
                    if (reason != null) putString("end_reason", reason)
                }
                withTracker(app) { it.call("call_ended", id, extras) }
            } catch (_: Throwable) {
                // never break the phone because of the Tracker; its own sync is the backup
            }
        }
    }

    /** The call log _ID of this call: the row for the number nearest to when it began (waits up to ~10 s) */
    @SuppressLint("MissingPermission")
    private fun findRow(context: Context, number: String, startedAt: Long): String? {
        val wanted = number.filter { it.isDigit() }.takeLast(10)
        repeat(8) { attempt ->
            if (attempt > 0) Thread.sleep(1_500)
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                    arrayOf((startedAt - 10_000).toString(), (startedAt + 10_000).toString()),
                    null,
                )?.use { c ->
                    // (a redial of the same number moments later is another row: take the nearest)
                    var best: String? = null
                    var bestGap = Long.MAX_VALUE
                    while (c.moveToNext()) {
                        val n = c.getString(1)?.filter { it.isDigit() }?.takeLast(10) ?: continue
                        if (n != wanted) continue
                        val gap = kotlin.math.abs(c.getLong(2) - startedAt)
                        if (gap < bestGap) {
                            bestGap = gap
                            best = c.getLong(0).toString()
                        }
                    }
                    if (best != null) return best
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
