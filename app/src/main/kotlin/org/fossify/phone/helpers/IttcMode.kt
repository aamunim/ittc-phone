package org.fossify.phone.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 🎓 ITTC 3.12: ITTC mode (work: leads, the Tracker) or Personal mode. A small first version so the
 * main screen's mode controls (the header's 💼 / 🏠 and the side panel's switch) work; what each mode
 * changes is done elsewhere. Saved in the "ittc" preferences; the listeners hear every change on
 * the main thread.
 */
object IttcMode {
    private const val PREFS = "ittc"
    private const val KEY_PERSONAL = "ittc_mode_personal"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cached: Boolean? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPersonal(ctx: Context): Boolean =
        cached ?: prefs(ctx).getBoolean(KEY_PERSONAL, false).also { cached = it }

    @Suppress("UNUSED_PARAMETER")
    fun setPersonal(ctx: Context, personal: Boolean, reason: String = "user") {
        if (isPersonal(ctx) == personal) return
        cached = personal
        prefs(ctx).edit().putBoolean(KEY_PERSONAL, personal).apply()
        val notify = Runnable { listeners.forEach { l -> l() } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else main.post(notify)
    }

    @Suppress("UNUSED_PARAMETER")
    fun canSwitch(ctx: Context): Boolean = true

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
