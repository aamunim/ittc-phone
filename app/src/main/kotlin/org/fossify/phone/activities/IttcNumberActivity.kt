package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.commons.extensions.formatTime
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemRecentsDateBinding
import org.fossify.phone.databinding.IttcActivityNumberBinding
import org.fossify.phone.databinding.IttcItemNumberCallBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getDayCode
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCallHistory
import org.fossify.phone.helpers.RecentsHelper
import org.joda.time.DateTime

/**
 * 🎓 ITTC: one number's call history (a tap on a Recents row): who it is, call / SMS / WhatsApp /
 * contact / block, a summary, and every call with the number by day, newest first.
 */
class IttcNumberActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityNumberBinding::inflate)

    private var number = ""
    private var givenName: String? = null
    private var contactName: String? = null
    private var contactUri: Uri? = null
    private var entries = listOf<IttcCallHistory.Entry>()

    private data class ContactInfo(val name: String?, val photoUri: String?, val uri: Uri?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        givenName = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() && it != number }
        if (number.isBlank()) {
            finish()
            return
        }

        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcNumberScrollview))
            setupMaterialScrollListener(ittcNumberScrollview, ittcNumberAppbar)

            ittcNumberCall.setOnClickListener { startCallWithConfirmationCheck(number, displayName()) }
            ittcNumberSms.setOnClickListener { launchSendSMSIntent(number) }
            ittcNumberWhatsapp.setOnClickListener { openWhatsApp() }
            ittcNumberContact.setOnClickListener {
                val uri = contactUri
                if (uri != null) launchViewContactIntent(uri) else startAddContactIntent(number)
            }
            ittcNumberBlock.setOnClickListener { tryBlocking() }
            ittcNumberDelete.setOnClickListener { askConfirmDelete() }
        }

        showHeader()
        updateTextColors(binding.ittcNumberScrollview)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcNumberAppbar, NavigationIcon.Arrow)
        // (back from a call or the contacts app: the calls and the name may have changed)
        loadContact()
        loadLead()
        loadCalls()
    }

    private fun displayName() = contactName ?: givenName ?: number

    private fun shownNumber() = if (config.formatPhoneNumbers) number.formatPhoneNumber() else number

    private fun showHeader(photoUri: String? = null) {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        binding.apply {
            val name = contactName ?: givenName
            ittcNumberName.text = name ?: shownNumber()
            ittcNumberNumber.text = shownNumber()
            ittcNumberNumber.beVisibleIf(name != null)
            SimpleContactsHelper(this@IttcNumberActivity).loadContactImage(photoUri.orEmpty(), ittcNumberPhoto, displayName())

            val icons = listOf(
                ittcNumberCall to R.drawable.ic_phone_vector,
                ittcNumberSms to R.drawable.ic_sms_vector,
                ittcNumberWhatsapp to R.drawable.ic_sms_vector,
                ittcNumberContact to if (contactUri != null) R.drawable.ic_person_vector else R.drawable.ic_add_person_vector,
                ittcNumberBlock to R.drawable.ic_block_vector,
            )
            icons.forEach { (view, icon) ->
                val color = if (view == ittcNumberWhatsapp) WHATSAPP_GREEN else primaryColor
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, resources.getColoredDrawableWithColor(icon, color), null, null)
                view.setTextColor(textColor)
            }
            ittcNumberContact.setText(if (contactUri != null) R.string.ittc_hist_contact else R.string.ittc_hist_save)
            ittcNumberDelete.setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getColoredDrawableWithColor(R.drawable.ic_delete_vector, textColor), null, null, null
            )
        }
    }

    private fun loadContact() {
        ensureBackgroundThread {
            val info = lookupContact(number)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                contactName = info?.name?.takeIf { it.isNotBlank() }
                contactUri = info?.uri
                showHeader(info?.photoUri)
            }
        }
    }

    @SuppressLint("MissingPermission") // (READ_CONTACTS checked first)
    private fun lookupContact(number: String): ContactInfo? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.LOOKUP_KEY,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
        )
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val lookupKey = cursor.getString(1)
                    return ContactInfo(
                        name = cursor.getString(2),
                        photoUri = cursor.getString(3),
                        uri = ContactsContract.Contacts.getLookupUri(id, lookupKey),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** The ITTC lead line: "🎓 Lead · Course · Stage" (the lead's name when no contact has the number) */
    private fun loadLead() {
        ensureBackgroundThread {
            val lead = try {
                IttcBridge.leadsFor(this, listOf(number))[number]
            } catch (_: Throwable) {
                null
            }
            val direct = try {
                IttcDirect.isActive(this)
            } catch (_: Throwable) {
                false
            }
            val canOpen = direct || IttcBridge.trackerInstalled(this)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showLeadBox(lead, direct, canOpen)
                binding.ittcNumberLead.apply {
                    beVisibleIf(lead != null)
                    if (lead != null) {
                        val unsaved = contactName == null && givenName == null
                        val who = if (unsaved && lead.name.isNotEmpty()) "🎓 ${lead.name}" else getString(R.string.ittc_lead_badge)
                        text = listOfNotNull(who, lead.course, lead.stage).joinToString(" · ")
                        setTextColor(IttcBridge.stageColor(lead.stage, getProperPrimaryColor(), getProperTextColor().adjustAlpha(0.6f)))
                    }
                }
            }
        }
    }

    /**
     * The lead box under the header: a lead → its summary, "Open lead" (and "Templates" in direct
     * mode); no lead and signed in to ITTC here → "Add as a lead"
     */
    private fun showLeadBox(lead: IttcBridge.Lead?, direct: Boolean, canOpen: Boolean) {
        val box = binding.ittcNumberLeadBox
        box.removeAllViews()
        val buttons = ArrayList<Pair<Int, () -> Unit>>()
        if (lead != null) {
            if (canOpen) {
                buttons += R.string.ittc_nl_open to {
                    if (!IttcBridge.openLead(this, lead.leadId)) toast(R.string.ittc_tracker_missing)
                }
            }
            if (direct) buttons += R.string.ittc_nl_templates to { IttcLeadActivity.open(this, lead.leadId, showTemplates = true) }
        } else if (direct) {
            buttons += R.string.ittc_nl_add to { IttcAddLeadActivity.open(this, number, contactName ?: givenName) }
        }
        val note = lead?.let { listOfNotNull(it.rating?.let { r -> "★ ${r.replaceFirstChar { c -> c.uppercase() }}" }, it.lastNote?.let { n -> "“${n.take(160)}”" }).joinToString("  ") }
        box.beVisibleIf(buttons.isNotEmpty() || !note.isNullOrEmpty())
        val textColor = getProperTextColor()
        val primary = getProperPrimaryColor()
        val density = resources.displayMetrics.density
        val m = (density * 4).toInt()
        box.setPadding((density * 12).toInt(), 0, (density * 12).toInt(), (density * 8).toInt())
        if (!note.isNullOrEmpty()) {
            box.addView(TextView(this).apply {
                text = note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textColor.adjustAlpha(0.8f))
                setPadding(m, 0, m, m)
            })
        }
        if (buttons.isEmpty()) return
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, action) ->
            line.addView(
                TextView(this).apply {
                    setText(label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    val pad = (density * 10).toInt()
                    setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setBackgroundResource(R.drawable.ittc_chip_background)
                    background.mutate().applyColorFilter(primary)
                    setTextColor(primary.getContrastColor())
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m, m, 0) },
            )
        }
        box.addView(line)
    }

    private fun loadCalls() {
        if (!hasPermission(PERMISSION_READ_CALL_LOG)) {
            showCalls(emptyList(), noAccess = true)
            return
        }

        ensureBackgroundThread {
            val calls = IttcCallHistory.loadCalls(this, number)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) showCalls(calls, noAccess = false)
            }
        }
    }

    private fun showCalls(calls: List<IttcCallHistory.Entry>, noAccess: Boolean) {
        entries = calls
        val textColor = getProperTextColor()
        val secondaryTextColor = textColor.adjustAlpha(0.6f)
        val missedColor = resources.getColor(R.color.color_missed_call, theme)
        val outgoingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, resources.getColor(R.color.color_outgoing_call, theme))
        val incomingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, resources.getColor(R.color.color_incoming_call, theme))
        val missedIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, missedColor)
        val showSim = areMultipleSIMsAvailable()

        binding.apply {
            ittcNumberPlaceholder.setText(if (noAccess) R.string.ittc_hist_no_access else R.string.ittc_hist_none)
            ittcNumberPlaceholder.beVisibleIf(calls.isEmpty())
            ittcNumberDelete.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.text = summary(calls)

            val list = ittcNumberCalls
            list.removeAllViews()
            var lastDayCode = ""
            for (call in calls) {
                val dayCode = call.startTS.getDayCode()
                if (dayCode != lastDayCode) {
                    lastDayCode = dayCode
                    val date = ItemRecentsDateBinding.inflate(layoutInflater, list, false)
                    date.dateTextView.apply {
                        text = dayLabel(dayCode, call.startTS)
                        setTextColor(secondaryTextColor)
                    }
                    list.addView(date.root)
                }

                val row = IttcItemNumberCallBinding.inflate(layoutInflater, list, false)
                val missed = call.type == Calls.MISSED_TYPE
                row.ittcCallType.setImageDrawable(
                    when (call.type) {
                        Calls.OUTGOING_TYPE -> outgoingIcon
                        Calls.MISSED_TYPE -> missedIcon
                        else -> incomingIcon
                    }
                )
                row.ittcCallTime.apply {
                    text = call.startTS.formatTime(this@IttcNumberActivity)
                    setTextColor(if (missed) missedColor else textColor)
                }
                row.ittcCallKind.apply {
                    setText(
                        when (call.type) {
                            Calls.OUTGOING_TYPE -> R.string.ittc_hist_outgoing
                            Calls.MISSED_TYPE -> R.string.ittc_hist_missed
                            else -> R.string.ittc_hist_incoming
                        }
                    )
                    setTextColor(textColor)
                }
                row.ittcCallDuration.apply {
                    val talked = call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0
                    text = if (talked) formatSecondsToShortTimeString(call.duration) else ""
                    setTextColor(textColor)
                }
                row.ittcCallSim.apply {
                    beVisibleIf(showSim && call.simID != -1)
                    text = getString(R.string.ittc_hist_sim, call.simID)
                    setTextColor(secondaryTextColor)
                }
                list.addView(row.root)
            }
        }
    }

    private fun dayLabel(dayCode: String, timestamp: Long): String {
        val now = DateTime.now()
        return when (dayCode) {
            now.millis.getDayCode() -> getString(R.string.today)
            now.minusDays(1).millis.getDayCode() -> getString(R.string.yesterday)
            else -> timestamp.formatDateOrTime(this, hideTimeOnOtherDays = true, showCurrentYear = false)
        }
    }

    /** "12 calls · 1h 5m talk · 3 missed · 2 Jan 2025 – 18 Nov 2026" */
    private fun summary(calls: List<IttcCallHistory.Entry>): String {
        if (calls.isEmpty()) return ""
        val talkSeconds = calls
            .filter { it.type != Calls.MISSED_TYPE && it.type != Calls.REJECTED_TYPE }
            .sumOf { it.duration.toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val missed = calls.count { it.type == Calls.MISSED_TYPE }
        val newest = calls.maxOf { it.startTS }
        val oldest = calls.minOf { it.startTS }
        fun date(ts: Long) = ts.formatDateOrTime(this, hideTimeOnOtherDays = true, showCurrentYear = true, hideTodaysDate = false)

        val parts = arrayListOf(
            if (calls.size == 1) getString(R.string.ittc_hist_one_call) else getString(R.string.ittc_hist_calls, calls.size)
        )
        if (talkSeconds > 0) parts += getString(R.string.ittc_hist_talk, formatSecondsToShortTimeString(talkSeconds))
        if (missed > 0) parts += getString(R.string.ittc_hist_missed_count, missed)
        val first = date(oldest)
        val last = date(newest)
        parts += if (first == last) first else getString(R.string.ittc_hist_range, first, last)
        return parts.joinToString(" · ")
    }

    /** wa.me wants the number with its country code: a Bangladeshi "01…" is "8801…" */
    private fun openWhatsApp() {
        val key = IttcCallHistory.key(number)
        val waNumber = when {
            key.length == 11 && key.startsWith("01") -> "88$key"
            key.startsWith("00") -> key.substring(2)
            else -> key
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$waNumber")))
        } catch (_: Throwable) {
            toast(R.string.ittc_hist_no_app)
        }
    }

    // (as the Recents list's "Block number")
    private fun tryBlocking() {
        if (!isOrWasThankYouInstalled()) {
            FeatureLockedDialog(this) { }
            return
        }

        val question = String.format(getString(R.string.block_confirmation), number)
        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                if (addBlockedNumber(number)) {
                    IttcCallHistory.recentsDirty = true
                    runOnUiThread { toast(R.string.ittc_hist_blocked) }
                }
            }
        }
    }

    // (as the Recents list's "Remove": the call log, with WRITE_CALL_LOG)
    private fun askConfirmDelete() {
        val count = entries.size
        if (count == 0) return
        ConfirmationDialog(this, getString(R.string.ittc_hist_delete_confirm, count, displayName())) {
            handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) deleteHistory()
            }
        }
    }

    private fun deleteHistory() {
        val shownIds = entries.map { it.id }
        ensureBackgroundThread {
            val ids = (shownIds + IttcCallHistory.idsFor(this, listOf(number))).distinct()
            RecentsHelper(this).removeRecentCalls(ids) {
                IttcCallHistory.recentsDirty = true
                runOnUiThread {
                    toast(R.string.ittc_hist_deleted)
                    loadCalls()
                }
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "ittc_number"
        const val EXTRA_NAME = "ittc_name"
        private const val WHATSAPP_GREEN = 0xFF25D366.toInt()

        fun open(context: Context, number: String, name: String? = null) {
            if (number.isBlank()) return
            Intent(context, IttcNumberActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                if (!name.isNullOrBlank()) putExtra(EXTRA_NAME, name)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        }
    }
}
