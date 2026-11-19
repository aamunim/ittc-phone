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

    /**
     * 3.11.1: a little memory held back for the report: an OutOfMemoryError lets it go first, so
     * writing what happened does not fail for want of memory too
     */
    @Volatile
    private var reserve: ByteArray? = null

    /** A few facts kept for the report (list sizes…): what was big when memory ran out */
    private val marks = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun mark(key: String, value: String) {
        if (marks.size < 16 || marks.containsKey(key)) marks[key] = value
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        reserve = ByteArray(256 * 1024)
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (error is OutOfMemoryError) reserve = null
            record(app, error, "crash on ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }

    /** Keep an error (the newest one wins) */
    fun record(context: Context, error: Throwable, where: String) {
        if (error is OutOfMemoryError) {
            reserve = null
            recordSmall(context, error, where)
            return
        }
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

    /**
     * Out of memory: as little made as can be — no whole stack trace as one text, at most 40 lines
     * written as they are read — plus the memory and the marks (what was big)
     */
    private fun recordSmall(context: Context, error: Throwable, where: String) {
        try {
            File(context.applicationContext.filesDir, FILE).bufferedWriter().use { w ->
                w.write("ITTC Phone ")
                w.write(BuildConfig.VERSION_NAME)
                w.write(" (")
                w.write(BuildConfig.VERSION_CODE.toString())
                w.write(")\nAndroid ")
                w.write(Build.VERSION.RELEASE ?: "")
                w.write(" · ")
                w.write(Build.MANUFACTURER ?: "")
                w.write(" ")
                w.write(Build.MODEL ?: "")
                w.write("\n")
                w.write(where)
                w.write(" · ")
                w.write(java.util.Date().toString())
                w.write("\n\n")
                w.write(error.javaClass.name)
                w.write(": ")
                w.write(error.message ?: "")
                w.write("\n")
                val frames = error.stackTrace
                for (i in 0 until minOf(frames.size, 40)) {
                    w.write("\tat ")
                    w.write(frames[i].toString())
                    w.write("\n")
                }
                val rt = Runtime.getRuntime()
                w.write("\nMemory: max ")
                w.write((rt.maxMemory() shr 20).toString())
                w.write(" MB, used ")
                w.write(((rt.totalMemory() - rt.freeMemory()) shr 20).toString())
                w.write(" MB\n")
                for ((k, v) in marks) {
                    w.write(k)
                    w.write(": ")
                    w.write(v)
                    w.write("\n")
                }
            }
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
