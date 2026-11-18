package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.commons.extensions.formatTime
import org.fossify.commons.extensions.getColoredDrawableWithColor
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
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.RecentsHelper
import org.joda.time.DateTime

/**
 * 🎓 ITTC: one number's call history (a tap on a Recents row): who it is, call / SMS / WhatsApp /
 * contact / block, a summary, and every call with the number by day, newest first.
 * 3.5: the card look — a summary card on top, action tiles (call in teal), a small card per call.
 */
class IttcNumberActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityNumberBinding::inflate)

    private var number = ""
    private var givenName: String? = null
    private var contactName: String? = null
    private var contactUri: Uri? = null
    /** the lead's name when no contact has the number (for the initials) */
    private var leadName: String? = null
    private var shownPhoto: String? = null
    private var entries = listOf<IttcCallHistory.Entry>()
    /** 🎨 the 3.3 palette (the app's light / dark theme) */
    private lateinit var palette: IttcTheme.Palette

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
        palette = IttcTheme.of(this)
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

        updateTextColors(binding.ittcNumberScrollview)
        applyPalette()
        showHeader()
    }

    /** 🎨 the soft background, the summary card, the tiles (call: teal) */
    private fun applyPalette() {
        val p = palette
        binding.apply {
            ittcNumberCoordinator.setBackgroundColor(p.bg)
            ittcNumberScrollview.setBackgroundColor(p.bg)
            IttcTheme.card(ittcNumberCard, p)
            ittcNumberDivider.setBackgroundColor(p.divider)
            ittcNumberName.setTextColor(p.text)
            ittcNumberNumber.setTextColor(p.muted)
            ittcNumberSummary.setTextColor(p.text2)
            ittcNumberPlaceholder.setTextColor(p.muted)
            ittcNumberCall.background = IttcTheme.rounded(this@IttcNumberActivity, p.call, 16f)
            ittcNumberCall.setTextColor(p.onPrimary)
            listOf(ittcNumberSms, ittcNumberWhatsapp, ittcNumberContact, ittcNumberBlock).forEach {
                it.background = IttcTheme.rounded(this@IttcNumberActivity, p.surface, 16f)
                it.setTextColor(p.text)
            }
            ittcNumberDelete.setTextColor(p.danger)
        }
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
        val p = palette
        binding.apply {
            val name = contactName ?: givenName
            ittcNumberName.text = name ?: shownNumber()
            ittcNumberNumber.text = shownNumber()
            ittcNumberNumber.beVisibleIf(name != null)
            // (a contact's photo; else the initials in a soft colour, as in Recents)
            val hasPhoto = !photoUri.isNullOrEmpty()
            ittcNumberPhoto.beVisibleIf(hasPhoto)
            ittcNumberInitials.beVisibleIf(!hasPhoto)
            if (hasPhoto) {
                SimpleContactsHelper(this@IttcNumberActivity).loadContactImage(photoUri.orEmpty(), ittcNumberPhoto, displayName())
            } else {
                val shown = leadName?.takeIf { name == null } ?: displayName()
                val (bg, fg) = IttcTheme.avatarColors(this@IttcNumberActivity, shown)
                ittcNumberInitials.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bg)
                }
                ittcNumberInitials.setTextColor(fg)
                ittcNumberInitials.text = IttcTheme.initials(if (name == null && leadName == null) null else shown)
            }

            val icons = listOf(
                ittcNumberCall to R.drawable.ic_phone_vector,
                ittcNumberSms to R.drawable.ic_sms_vector,
                ittcNumberWhatsapp to R.drawable.ic_sms_vector,
                ittcNumberContact to if (contactUri != null) R.drawable.ic_person_vector else R.drawable.ic_add_person_vector,
                ittcNumberBlock to R.drawable.ic_block_vector,
            )
            icons.forEach { (view, icon) ->
                val color = when (view) {
                    ittcNumberCall -> p.onPrimary
                    ittcNumberWhatsapp -> WHATSAPP_GREEN
                    else -> p.primary
                }
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, resources.getColoredDrawableWithColor(icon, color), null, null)
            }
            ittcNumberContact.setText(if (contactUri != null) R.string.ittc_hist_contact else R.string.ittc_hist_save)
            ittcNumberDelete.setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getColoredDrawableWithColor(R.drawable.ic_delete_vector, p.danger), null, null, null
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
                shownPhoto = info?.photoUri
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
                val p = palette
                val unsaved = contactName == null && givenName == null
                binding.ittcNumberLead.apply {
                    beVisibleIf(lead != null)
                    if (lead != null) {
                        val who = if (unsaved && lead.name.isNotEmpty()) "🎓 ${lead.name}" else getString(R.string.ittc_lead_badge)
                        text = listOfNotNull(who, lead.course).joinToString(" · ")
                        setTextColor(p.text2)
                    }
                }
                // (the stage as a pill, coloured per stage)
                val stage = lead?.stage?.takeIf { it.isNotBlank() }
                binding.ittcNumberStage.apply {
                    beVisibleIf(stage != null)
                    if (stage != null) {
                        val (bg, fg) = IttcTheme.stageColors(this@IttcNumberActivity, stage)
                        IttcTheme.pill(this, bg, fg)
                        text = stage
                    }
                }
                // (no contact: the lead's name for the initials)
                val newLeadName = lead?.name?.takeIf { unsaved && it.isNotEmpty() }
                if (newLeadName != leadName) {
                    leadName = newLeadName
                    showHeader(shownPhoto)
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
        val p = palette
        val m = IttcTheme.dp(this, 4f)
        if (!note.isNullOrEmpty()) {
            box.addView(TextView(this).apply {
                text = note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(p.text2)
                setPadding(0, 0, 0, m)
            })
        }
        if (buttons.isEmpty()) return
        // (pills: the first filled, the others outlined)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, (label, action) ->
            line.addView(
                TextView(this).apply {
                    setText(label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    val pad = IttcTheme.dp(this@IttcNumberActivity, 12f)
                    setPadding(pad, 0, pad, 0)
                    minHeight = IttcTheme.dp(this@IttcNumberActivity, 40f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    if (i == 0) IttcTheme.pill(this, p.primary, p.onPrimary) else IttcTheme.pill(this, p.surface, p.primary, p.outline)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(if (i == 0) 0 else m, m, if (i == buttons.size - 1) 0 else m, 0) },
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

    /** What the call list shows now (count · newest id · access · day): the same → no rebuild */
    private var shownKey: String? = null
    /** Rows on screen (the newest first; "Show more" adds [PAGE] each tap) */
    private var shownRows = 0
    private var lastDayCode = ""

    private fun showCalls(calls: List<IttcCallHistory.Entry>, noAccess: Boolean) {
        // (back from somewhere with nothing changed: keep the list as it is, and its "Show more")
        val key = "${calls.size}|${calls.firstOrNull()?.id}|$noAccess|${System.currentTimeMillis().getDayCode()}"
        if (key == shownKey) return
        shownKey = key
        entries = calls

        binding.apply {
            ittcNumberPlaceholder.setText(if (noAccess) R.string.ittc_hist_no_access else R.string.ittc_hist_none)
            ittcNumberPlaceholder.beVisibleIf(calls.isEmpty())
            ittcNumberDelete.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.beVisibleIf(calls.isNotEmpty())
            ittcNumberDivider.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.text = summary(calls)
            ittcNumberCalls.removeAllViews()
        }
        shownRows = 0
        lastDayCode = ""
        showMoreCalls()
    }

    /** The next [PAGE] calls (not every call at once: a number may have thousands) */
    private fun showMoreCalls() {
        val calls = entries
        val list = binding.ittcNumberCalls
        // (the old "Show more" goes; a new one at the end if calls are left)
        list.findViewWithTag<android.view.View>(SHOW_MORE_TAG)?.let { list.removeView(it) }

        val p = palette
        val outgoingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, p.call)
        val incomingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, p.primary)
        val missedIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, p.danger)
        val showSim = areMultipleSIMsAvailable()

        val until = minOf(calls.size, shownRows + PAGE)
        for (i in shownRows until until) {
            val call = calls[i]
            val dayCode = call.startTS.getDayCode()
            if (dayCode != lastDayCode) {
                lastDayCode = dayCode
                val date = ItemRecentsDateBinding.inflate(layoutInflater, list, false)
                date.dateTextView.apply {
                    text = dayLabel(dayCode, call.startTS)
                    setTextColor(p.muted)
                }
                list.addView(date.root)
            }

            val row = IttcItemNumberCallBinding.inflate(layoutInflater, list, false)
            val missed = call.type == Calls.MISSED_TYPE
            // (a small card; the direction in a soft circle: outgoing teal, incoming blue, missed red)
            row.root.background = IttcTheme.rounded(this, p.surface, 14f)
            row.ittcCallType.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when (call.type) {
                        Calls.OUTGOING_TYPE -> p.callSoft
                        Calls.MISSED_TYPE -> p.dangerSoft
                        else -> p.primarySoft
                    }
                )
            }
            row.ittcCallType.setImageDrawable(
                when (call.type) {
                    Calls.OUTGOING_TYPE -> outgoingIcon
                    Calls.MISSED_TYPE -> missedIcon
                    else -> incomingIcon
                }
            )
            row.ittcCallTime.apply {
                text = call.startTS.formatTime(this@IttcNumberActivity)
                setTextColor(p.muted)
            }
            row.ittcCallKind.apply {
                setText(
                    when (call.type) {
                        Calls.OUTGOING_TYPE -> R.string.ittc_hist_outgoing
                        Calls.MISSED_TYPE -> R.string.ittc_hist_missed
                        Calls.REJECTED_TYPE -> R.string.ittc_a_declined
                        Calls.BLOCKED_TYPE -> R.string.ittc_a_blocked
                        else -> R.string.ittc_hist_incoming
                    }
                )
                setTextColor(if (missed) p.danger else p.text)
            }
            row.ittcCallDuration.apply {
                val talked = call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0
                text = if (talked) "· " + formatSecondsToShortTimeString(call.duration) else ""
                setTextColor(p.text2)
            }
            row.ittcCallSim.apply {
                beVisibleIf(showSim && call.simID != -1)
                text = getString(R.string.ittc_hist_sim, call.simID)
                setTextColor(p.muted)
            }
            list.addView(row.root)
        }
        shownRows = until

        val left = calls.size - shownRows
        if (left > 0) {
            val density = resources.displayMetrics.density
            val pad = (density * 12).toInt()
            list.addView(
                TextView(this).apply {
                    tag = SHOW_MORE_TAG
                    text = getString(R.string.ittc_hist_show_more, left)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(palette.primary)
                    gravity = Gravity.CENTER
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    setOnClickListener { showMoreCalls() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
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
        private const val PAGE = 100
        private const val SHOW_MORE_TAG = "ittc_show_more"

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
