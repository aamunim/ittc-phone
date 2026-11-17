package org.fossify.phone.helpers

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AlertDialog
import org.fossify.phone.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 🎓 ITTC Phone: when the app stops with an error, what happened is kept on the phone (never sent
 * anywhere) and shown once at the next start, with Copy / Share, so it can be reported.
 * Errors caught and survived (e.g. while drawing the Leads tab) are kept the same way.
 */
object IttcCrash {
    private const val FILE = "ittc_last_error.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, error, "crash on ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }

    /** Keep an error (the newest one wins) */
    fun record(context: Context, error: Throwable, where: String) {
        try {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val text = buildString {
                append("ITTC Phone ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append(where).append(" · ").append(java.util.Date()).append("\n\n")
                append(trace.take(12_000))
            }
            File(context.applicationContext.filesDir, FILE).writeText(text)
        } catch (_: Throwable) {
        }
    }

    /** At the start: the last error, once */
    fun showIfAny(activity: Activity) {
        val f = File(activity.filesDir, FILE)
        val text = try {
            if (f.exists()) f.readText() else null
        } catch (_: Throwable) {
            null
        } ?: return
        f.delete()
        try {
            AlertDialog.Builder(activity)
                .setTitle("⚠️ ITTC Phone stopped last time")
                .setMessage("Please send this to the ITTC team so it can be fixed:\n\n" + text.take(3_000))
                .setPositiveButton("Share") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                "ITTC Phone error",
                            ),
                        )
                    } catch (_: Throwable) {
                    }
                }
                .setNeutralButton("Copy") { _, _ ->
                    try {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ITTC Phone error", text))
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (_: Throwable) {
        }
    }
}
