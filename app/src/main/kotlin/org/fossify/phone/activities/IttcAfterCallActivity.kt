package org.fossify.phone.activities

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.provider.CallLog
import java.util.Calendar
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.helpers.ensureBackgroundThread
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAfterCallBinding
import org.fossify.phone.extensions.startCallIntent
import org.fossify.phone.helpers.IttcBridge

/**
 * 🎓 ITTC: right after a lead call that was not started from the Tracker (an incoming call, or one
 * dialed here): who it was, how the call went, and "Add a note" (the Tracker's own note popup for
 * this very call), "Call again", "Open the lead".
 * Calls started from the Tracker skip this: the Tracker shows its popup when the call ends.
 */
class IttcAfterCallActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAfterCallBinding::inflate)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.ittcAcHolder), padBottomSystem = listOf(binding.ittcAcHolder))
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun show(intent: Intent) {
        val leadId = intent.getStringExtra(LEAD_ID)
        val number = intent.getStringExtra(NUMBER)
        if (leadId.isNullOrEmpty() || number.isNullOrEmpty()) {
            finish()
            return
        }
        IttcBridge.afterCallShown(number)
        val name = intent.getStringExtra(NAME).orEmpty()
        val talked = intent.getIntExtra(TALKED_SEC, 0)
        val outgoing = intent.getBooleanExtra(OUTGOING, false)
        val reason = intent.getStringExtra(REASON)

        updateTextColors(binding.ittcAcHolder)
        val primary = getProperPrimaryColor()
        binding.apply {
            ittcAcBadge.text = getString(R.string.ittc_after_call_badge)
            ittcAcName.text = name.ifEmpty { number }
            ittcAcNumber.text = number
            ittcAcNumber.beGoneIf(name.isEmpty())
            ittcAcResult.text = result(talked, outgoing, reason)
            ittcAcResult.setTextColor(if (talked > 0) primary else getProperTextColor())

            val info = listOfNotNull(
                intent.getStringExtra(COURSE),
                intent.getStringExtra(STAGE),
                intent.getStringExtra(RATING)?.let { "★ $it" },
            ).joinToString(" · ")
            ittcAcInfo.text = info
            ittcAcInfo.beGoneIf(info.isEmpty())
            val note = intent.getStringExtra(LAST_NOTE)
            ittcAcNote.text = note?.let { "“${it.take(300)}”" }
            ittcAcNote.beGoneIf(note.isNullOrBlank())

            ittcAcAddNote.backgroundTintList = ColorStateList.valueOf(primary)
            ittcAcAddNote.setTextColor(primary.getContrastColor())
            ittcAcAddNote.setOnClickListener { addNote(leadId, number) }
            ittcAcCallAgain.setOnClickListener {
                startCallIntent(number)
                finish()
            }
            ittcAcWhatsapp.setOnClickListener { openOutside(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waDigits(number)}"))) }
            ittcAcSms.setOnClickListener { openOutside(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))) }
            ittcAcOpenLead.setOnClickListener { openLead(leadId, null) }
            ittcAcClose.setOnClickListener { finish() }
        }
        showToday()
    }

    /** "📊 Today: 23 calls · 1h 40m talking" (answered calls, from the phone's call log) */
    @SuppressLint("MissingPermission")
    private fun showToday() {
        ensureBackgroundThread {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            var count = 0
            var seconds = 0L
            try {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DURATION} > 0",
                    arrayOf(start.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        count++
                        seconds += c.getLong(0)
                    }
                }
            } catch (_: Throwable) {
                return@ensureBackgroundThread
            }
            if (count == 0) return@ensureBackgroundThread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.ittcAcToday.text = getString(R.string.ittc_today_stats, count, duration(seconds.toInt()))
                binding.ittcAcToday.beVisible()
            }
        }
    }

    /** 01711… → 8801711… (WhatsApp wants the country code, no +) */
    private fun waDigits(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.startsWith("0") && d.length == 11) "88$d" else d
    }

    private fun openOutside(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (_: Throwable) {
            toast(R.string.ittc_no_app)
        }
    }

    private fun result(talked: Int, outgoing: Boolean, reason: String?): String = when {
        talked > 0 -> getString(R.string.ittc_talked, duration(talked))
        reason == "busy" -> getString(R.string.ittc_busy)
        outgoing -> getString(R.string.ittc_no_answer)
        reason == "declined" -> getString(R.string.ittc_declined)
        else -> getString(R.string.ittc_missed)
    }

    private fun duration(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600}h ${sec % 3600 / 60}m"
        sec >= 60 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec}s"
    }

    /** The Tracker's note popup for this very call (its call-log row is found a moment after the call) */
    private fun addNote(leadId: String, number: String, tries: Int = 0) {
        val rowId = IttcBridge.rowIdFor(number)
        if (rowId == null && tries < 4) {
            binding.ittcAcAddNote.isEnabled = false
            main.postDelayed({ addNote(leadId, number, tries + 1) }, 700)
            return
        }
        binding.ittcAcAddNote.isEnabled = true
        openLead(leadId, rowId)
    }

    private fun openLead(leadId: String, rowId: String?) {
        if (IttcBridge.openLead(this, leadId, rowId)) {
            finish()
        } else {
            toast(R.string.ittc_tracker_missing)
        }
    }

    companion object {
        private const val LEAD_ID = "ittc_lead_id"
        private const val NAME = "ittc_name"
        private const val COURSE = "ittc_course"
        private const val STAGE = "ittc_stage"
        private const val RATING = "ittc_rating"
        private const val LAST_NOTE = "ittc_last_note"
        private const val NUMBER = "ittc_number"
        private const val OUTGOING = "ittc_outgoing"
        private const val TALKED_SEC = "ittc_talked_sec"
        private const val REASON = "ittc_reason"

        fun open(context: Context, lead: IttcBridge.Lead, number: String, outgoing: Boolean, talkedSec: Int, reason: String?) {
            try {
                context.startActivity(
                    Intent(context, IttcAfterCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(LEAD_ID, lead.leadId)
                        .putExtra(NAME, lead.name)
                        .putExtra(COURSE, lead.course)
                        .putExtra(STAGE, lead.stage)
                        .putExtra(RATING, lead.rating)
                        .putExtra(LAST_NOTE, lead.lastNote)
                        .putExtra(NUMBER, number)
                        .putExtra(OUTGOING, outgoing)
                        .putExtra(TALKED_SEC, talkedSec)
                        .putExtra(REASON, reason),
                )
            } catch (_: Throwable) {
                // (not allowed right now: the Tracker's sync still has the call)
            }
        }
    }
}
