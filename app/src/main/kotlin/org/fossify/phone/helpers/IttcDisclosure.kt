package org.fossify.phone.helpers

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.fossify.phone.R

/**
 * 🎓 ITTC Phone: before Android asks to make it the phone app (which gives it the call log,
 * contacts and calls), say plainly what it does with them — once (Google Play "prominent
 * disclosure" for call-log and contacts access).
 */
object IttcDisclosure {
    private const val KEY = "disclosure_v1"

    fun showOnce(activity: Activity, then: () -> Unit) {
        val prefs = activity.getSharedPreferences("ittc", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) {
            then()
            return
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.ittc_disclosure_title)
                .setMessage(R.string.ittc_disclosure_text)
                .setCancelable(false)
                .setPositiveButton(R.string.ittc_disclosure_ok) { _, _ ->
                    prefs.edit().putBoolean(KEY, true).apply()
                    then()
                }
                .setNegativeButton(R.string.ittc_disclosure_not_now) { _, _ -> activity.finish() }
                .show()
        } catch (_: Throwable) {
            then()
        }
    }
}
