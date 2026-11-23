package org.fossify.phone.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.fossify.commons.extensions.toast
import org.fossify.phone.R

/**
 * 🎓 ITTC 3.15: "নম্বর কপি" — a number into the clipboard, as it is shown / saved (one plain copy,
 * no second "with the country code" one). A short toast "নম্বর কপি হয়েছে" below Android 13; on 13+
 * the system shows its own clipboard confirmation, so ours would be a second message (Android's advice).
 */
object IttcCopy {
    /** true: copied */
    fun number(context: Context, number: String?): Boolean {
        val n = number?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!put(context, n)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.toast(R.string.ittc_copy_number_done)
        return true
    }

    /** [text] into the clipboard, no toast (the caller says what was copied); true: copied */
    fun put(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ittc_copy_number), text))
        true
    } catch (_: Throwable) {
        false
    }
}
