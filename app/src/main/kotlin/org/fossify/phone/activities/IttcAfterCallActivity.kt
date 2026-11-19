package org.fossify.phone.activities

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.provider.CallLog
import java.util.Calendar
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.helpers.ensureBackgroundThread
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAfterCallBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCrash
import org.fossify.phone.helpers.IttcDialer
import org.fossify.phone.helpers.IttcDisclosure
import org.fossify.phone.helpers.IttcHandover
import org.fossify.phone.helpers.IttcNoteDraft
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import org.fossify.commons.extensions.setNavigationBarAppearance
import org.fossify.commons.extensions.setStatusBarAppearance

/**
 * 🎓 ITTC: right after a lead call that was not started from the Tracker (an incoming call, or one
 * dialed here): who it was, how the call went, and in one go what happened, the pipeline stage,
 * a note and the next call — saved through the Tracker (an older Tracker: its own note popup).
 * Calls started from the Tracker skip this: the Tracker shows its popup when the call ends.
 * 3.2: the admin's quick notes, the next call a result suggests, the power dialer ("Save & call
 * next"), and a number that is no lead (direct mode): "Add as a lead".
 * 3.5: the new look (a dark top, a white sheet with big result tiles, stage chips, the list being
 * called) and the note written on the call screen ("Write a note now") filled in.
 * 👥 Direct mode: "Hand over" the lead (anyone, to anyone) — sent after the call's result.
 */
class IttcAfterCallActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAfterCallBinding::inflate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: IttcTheme.Palette
    /** whether the note is empty (the stage hint changes only when this flips) */
    private var noteBlank = true

    // 🎓 ITTC 3.12: never made again mid-call / mid-note for a new display size (it takes it next time)
    override val recreateOnDisplaySizeChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = IttcTheme.of(this)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.ittcAcHolder), padBottomImeAndSystem = listOf(binding.ittcAcSheet))
        styleStatic()
        // (a stage that needs a note: its hint goes once one is written)
        binding.ittcAcNoteInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                // (only when the note goes from empty to written or back: not on every letter)
                val blank = s.isNullOrBlank()
                if (blank == noteBlank) return
                noteBlank = blank
                if (stageChoice?.noteRequired == true) renderStages()
            }
        })
        // (👥 a hand over chosen before the screen was made again: kept; the same call stays on screen)
        savedInstanceState?.let { st ->
            shownKey = st.getString(STATE_SHOWN_KEY)
            val lead = st.getString(STATE_HO_LEAD)
            val to = st.getString(STATE_HO_TO)
            if (lead != null && to != null) {
                handOver = Triple(lead, IttcBridge.Person(to, st.getString(STATE_HO_NAME).orEmpty(), ""), st.getString(STATE_HO_NOTE).orEmpty())
            }
        }
        show(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHOWN_KEY, shownKey)
        // (a save in progress queues the hand over itself: not kept, so a recreated screen can't queue it again)
        if (!saving) handOver?.let { (lead, person, note) ->
            outState.putString(STATE_HO_LEAD, lead)
            outState.putString(STATE_HO_TO, person.id)
            outState.putString(STATE_HO_NAME, person.name)
            outState.putString(STATE_HO_NOTE, note)
        }
    }

    override fun onResume() {
        super.onResume()
        // (light icons on the dark top, dark ones on the white sheet)
        try {
            window.setStatusBarAppearance(p.inCallBg)
            window.setNavigationBarAppearance(p.surface)
        } catch (_: Throwable) {
        }
    }

    /** 🎨 The parts that do not change with the call */
    private fun styleStatic() {
        val white = Color.WHITE
        binding.apply {
            ittcAcRoot.setBackgroundColor(p.inCallBg)
            ittcAcClose.setTextColor(white)
            ittcAcBadge.setTextColor(white.withAlpha(0.75f))
            ittcAcName.setTextColor(white)
            ittcAcNumber.setTextColor(white.withAlpha(0.75f))
            ittcAcNote.setTextColor(white.withAlpha(0.85f))
            ittcAcToday.setTextColor(white.withAlpha(0.7f))
            // (rounded on top only)
            ittcAcSheet.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.surface)
                val r = IttcTheme.dp(this@IttcAfterCallActivity, 24f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            ittcAcHandle.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.outline, 2f)
            listOf(ittcAcOutcomeLabel, ittcAcStageLabel, ittcAcNoteLabel, ittcAcNextLabel).forEach { it.setTextColor(p.text) }
            ittcAcStageHint.setTextColor(p.text2)
            ittcAcNoteInput.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.surface, 14f, p.outline)
            ittcAcNoteInput.setTextColor(p.text)
            ittcAcNoteInput.setHintTextColor(p.muted)
            ittcAcDialer.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.bg, 14f)
            ittcAcDialerIcon.setColorFilter(p.call)
            ittcAcDialerText.setTextColor(p.text2)
            ittcAcStop.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.surface, 999f, p.outline)
            ittcAcStop.setTextColor(p.danger)
            ittcAcSave.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.call, 999f)
            // (dark theme: the teal is light, so the dark text reads on it)
            ittcAcSave.setTextColor(if (p.dark) p.onPrimary else Color.WHITE)
            ittcAcAddLead.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.primary, 999f)
            ittcAcAddLead.setTextColor(p.onPrimary)
            ittcAcCloseButton.setTextColor(p.text2)
            ittcAcHandOver.background = IttcTheme.rounded(this@IttcAfterCallActivity, p.surface, 999f, p.outline)
            ittcAcHandOver.setTextColor(p.primary)
            listOf<View>(ittcAcSave, ittcAcAddLead, ittcAcStop, ittcAcHandOver).forEach { touchFeedback(it) }
        }
    }

    private fun Int.withAlpha(a: Float): Int = Color.argb((a * 255).toInt(), Color.red(this), Color.green(this), Color.blue(this))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // (👥 a hand over chosen and the screen closed without saving: it still goes; while a
        // save is running, its thread queues it right after the result)
        if (isFinishing && !saving) flushHandOver()
        super.onDestroy()
    }

    /** ▶ Back on the dialer's current lead: as ✕ (the list stops) */
    override fun onBackPressedCompat(): Boolean {
        if (dialerHere()) {
            IttcDialer.stop(this)
            toast(R.string.ittc_l2_calling_stopped)
        }
        return false
    }

    /** ▶ Is this screen's lead the one the power dialer is calling now? (another lead's call, an
     * incoming call or a number that is no lead: the screen behaves as normal) */
    private fun dialerHere(): Boolean {
        if (unknownMode) return false
        val leadId = intent?.getStringExtra(LEAD_ID)?.takeIf { it.isNotEmpty() } ?: return false
        return IttcDialer.currentLead?.leadId == leadId
    }

    /** The key of what is on screen (a new call: the note written for the last one goes) */
    private var shownKey: String? = null
    private var unknownMode = false

    private fun show(intent: Intent) {
        try {
            showNow(intent)
        } catch (e: Throwable) {
            // (never lose the screen right after a call: what could be shown stays)
            IttcCrash.record(this, e, "After-call screen")
        }
    }

    private fun showNow(intent: Intent) {
        val unknown = intent.getBooleanExtra(UNKNOWN, false)
        val leadId = intent.getStringExtra(LEAD_ID)?.takeIf { it.isNotEmpty() }
        val number = intent.getStringExtra(NUMBER)?.takeIf { it.isNotEmpty() }
        if (number == null || (!unknown && leadId == null)) {
            finish()
            return
        }
        unknownMode = unknown
        IttcBridge.afterCallShown(number)
        val name = intent.getStringExtra(NAME).orEmpty()
        val talked = intent.getIntExtra(TALKED_SEC, 0)
        val outgoing = intent.getBooleanExtra(OUTGOING, false)
        val reason = intent.getStringExtra(REASON)
        val key = "${leadId.orEmpty()}|$number|${intent.getLongExtra(STARTED_AT, 0L)}"
        if (key != shownKey) {
            // (👥 a hand over chosen for the call shown before: it goes now — or, while that call's
            // result is being saved, right after it, from the save's thread)
            if (!saving) flushHandOver()
            shownKey = key
            // (a note written on the call screen for this number: filled in)
            val draft = if (unknown) null else try {
                IttcNoteDraft.take(this, number)
            } catch (_: Throwable) {
                null
            }
            binding.ittcAcNoteInput.setText(draft.orEmpty())
            draft?.let { binding.ittcAcNoteInput.setSelection(it.length) }
        }

        binding.apply {
            ittcAcBadge.text = getString(
                when {
                    unknown -> R.string.ittc_b_ended_unknown
                    outgoing -> R.string.ittc_b_ended_out
                    else -> R.string.ittc_b_ended_in
                },
            )
            ittcAcName.text = name.ifEmpty { number }
            ittcAcNumber.text = number
            ittcAcNumber.beGoneIf(name.isEmpty())
            val line = listOfNotNull(
                result(talked, outgoing, reason),
                if (unknown) null else intent.getStringExtra(COURSE)?.takeIf { it.isNotBlank() },
                if (unknown) null else intent.getStringExtra(STAGE)?.takeIf { it.isNotBlank() },
                if (unknown) null else intent.getStringExtra(RATING)?.takeIf { it.isNotBlank() }?.let { "★ " + it.replaceFirstChar { c -> c.uppercase() } },
            ).joinToString(" · ")
            ittcAcResult.text = line
            ittcAcResult.setTextColor(if (talked > 0) p.ring else Color.WHITE.withAlpha(0.85f))
        }
        showLeadSections(!unknown)
        if (unknown) {
            showUnknown(number)
            return
        }
        showLead(intent, leadId ?: return, number, talked, outgoing, reason)
    }

    /** The result / stage / note / next call parts: only for a lead */
    private fun showLeadSections(show: Boolean) {
        binding.apply {
            listOf<View>(ittcAcOutcomeLabel, ittcAcOutcomes, ittcAcNoteLabel, ittcAcNoteInput, ittcAcNextLabel, ittcAcNextScroll, ittcAcSave)
                .forEach { it.beGoneIf(!show) }
            if (!show) {
                listOf<View>(ittcAcNote, ittcAcStageLabel, ittcAcStagesScroll, ittcAcStageHint, ittcAcPresetsScroll, ittcAcDialer, ittcAcHandOver)
                    .forEach { it.beGoneIf(true) }
            }
            ittcAcAddLead.beGoneIf(show)
            ittcAcCloseButton.beGoneIf(show)
        }
    }

    // ------------------------------------------------------------------
    // 📞 A number that is no lead (direct mode): "Add as a lead" or close
    // ------------------------------------------------------------------
    private var contactName: String? = null

    private fun showUnknown(number: String) {
        val startedAt = intent.getLongExtra(STARTED_AT, 0L)
        contactName = null
        outcome = null
        stages = emptyList()
        stageChoice = null
        currentStageId = null
        binding.apply {
            ittcAcAddLead.setOnClickListener {
                try {
                    IttcAddLeadActivity.open(this@IttcAfterCallActivity, number, contactName, startedAt)
                } catch (e: Throwable) {
                    IttcCrash.record(this@IttcAfterCallActivity, e, "After-call: add as a lead")
                }
                finish()
            }
            ittcAcCloseButton.setOnClickListener { finish() }
            ittcAcClose.setOnClickListener { finish() }
        }
        showToday()
        // (the name a contact on this phone has for the number)
        ensureBackgroundThread {
            val found = contactNameFor(number)
            runOnUiThread {
                if (isFinishing || isDestroyed || !unknownMode || intent.getStringExtra(NUMBER) != number) return@runOnUiThread
                if (found.isNullOrBlank()) return@runOnUiThread
                contactName = found
                binding.ittcAcName.text = found
                binding.ittcAcNumber.beGoneIf(false)
            }
        }
    }

    @SuppressLint("MissingPermission") // (READ_CONTACTS checked first)
    private fun contactNameFor(number: String): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 🎓 A lead
    // ------------------------------------------------------------------
    private fun showLead(intent: Intent, leadId: String, number: String, talked: Int, outgoing: Boolean, reason: String?) {
        binding.apply {
            val note = intent.getStringExtra(LAST_NOTE)
            ittcAcNote.text = note?.let { getString(R.string.ittc_b_last_note, it.take(300)) }
            ittcAcNote.beGoneIf(note.isNullOrBlank())

            ittcAcSave.setOnClickListener { save(leadId, number) }
            ittcAcClose.setOnClickListener {
                // (✕ on the lead the list is calling: the list stops)
                if (dialerHere()) {
                    IttcDialer.stop(this@IttcAfterCallActivity)
                    toast(R.string.ittc_l2_calling_stopped)
                }
                finish()
            }
            ittcAcStop.setOnClickListener {
                IttcDialer.stop(this@IttcAfterCallActivity)
                toast(R.string.ittc_l2_calling_stopped)
                renderDialer()
            }
        }
        // (a likely answer chosen already: talked → Connected, no answer → No answer, busy → Busy)
        outcome = when {
            talked > 0 -> "connected"
            reason == "busy" -> "busy"
            outgoing -> "no_answer"
            else -> null
        }
        nextAt = null
        nextKey = "none"
        userNext = false
        stages = emptyList()
        currentStageId = null
        stageChoice = null
        // (the kept settings at once: quick notes and the suggested next call)
        meta = IttcBridge.metaCache
        applyAutoNext()
        renderChoices()
        renderStages()
        renderNextLabel()
        renderPresets()
        IttcDialer.load(this)
        renderDialer()
        showToday()
        loadStages(leadId, intent.getStringExtra(STAGE))
        setupHandOver(leadId)
    }

    // ------------------------------------------------------------------
    // 👥 Hand over (direct mode): anyone may give the lead to anyone. Chosen here, queued after
    // the call's result (Save), or when the screen closes without saving — the result first, so
    // it is still allowed (the lead is someone else's afterwards).
    // ------------------------------------------------------------------
    /** (lead id, to whom, note) chosen here and not queued yet (also read by the save's thread) */
    @Volatile
    private var handOver: Triple<String, IttcBridge.Person, String>? = null
    private val handOverLock = Any()
    private var directHere = false
    /** the lead's own person (left out of the list), when the kept leads know it */
    private var leadOwner: String? = null

    private fun setupHandOver(leadId: String) {
        directHere = false
        leadOwner = null
        renderHandOver()
        binding.ittcAcHandOver.setOnClickListener { chooseHandOver(leadId) }
        binding.ittcAcHandOver.setOnLongClickListener {
            if (handOver?.first != leadId) return@setOnLongClickListener false
            takeHandOver(leadId)
            renderHandOver()
            toast(R.string.ittc_ho_cancelled)
            true
        }
        val app = applicationContext
        ensureBackgroundThread {
            val direct = try {
                IttcDirect.isActive(app)
            } catch (_: Throwable) {
                false
            }
            val owner = if (!direct) null else try {
                IttcDirect.leads(app).firstOrNull { it.leadId == leadId }?.assignedTo
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || unknownMode || intent.getStringExtra(LEAD_ID) != leadId) return@runOnUiThread
                directHere = direct
                leadOwner = owner
                renderHandOver()
            }
        }
    }

    private fun renderHandOver() {
        val b = binding.ittcAcHandOver
        b.beGoneIf(!directHere || unknownMode)
        val chosen = handOver?.takeIf { it.first == intent?.getStringExtra(LEAD_ID) }
        b.text = if (chosen != null) getString(R.string.ittc_ho_chosen, chosen.second.name) else getString(R.string.ittc_ho_button)
        b.contentDescription = if (chosen != null) getString(R.string.ittc_ho_will, chosen.second.name) else null
    }

    private fun chooseHandOver(leadId: String) {
        if (!directHere) return
        IttcDisclosure.showOnce(this) {
            IttcHandover.pick(this, leadOwner) { person, note ->
                if (isFinishing || isDestroyed || unknownMode || intent.getStringExtra(LEAD_ID) != leadId) return@pick
                handOver = Triple(leadId, person, note)
                renderHandOver()
                toast(getString(R.string.ittc_ho_will, person.name))
            }
        }
    }

    /** The hand over chosen here, taken once (null: none, or another lead's when [leadId] is given) */
    private fun takeHandOver(leadId: String? = null): Triple<String, IttcBridge.Person, String>? = synchronized(handOverLock) {
        val h = handOver ?: return@synchronized null
        if (leadId != null && h.first != leadId) return@synchronized null
        handOver = null
        h
    }

    /** Queue the hand over chosen here (once); the outbox sends it after this lead's result */
    private fun flushHandOver() {
        val h = takeHandOver() ?: return
        val app = applicationContext
        try {
            app.toast(app.getString(R.string.ittc_ho_done, h.second.name))
            IttcHandover.queueAfterLead(app, h.first, h.second.id, h.second.name, h.third) { ok ->
                if (!ok) Handler(Looper.getMainLooper()).post { app.toast(R.string.ittc_ho_failed) }
            }
        } catch (e: Throwable) {
            IttcCrash.record(app, e, "After-call: hand over")
        }
    }

    /** The save's thread: the hand over chosen for [leadId] queued right after its result (any screen state) */
    private fun queueHandOverAfterSave(app: Context, leadId: String) {
        val h = takeHandOver(leadId) ?: return
        val ok = IttcHandover.queueNow(app, h.first, h.second.id, h.second.name, h.third)
        Handler(Looper.getMainLooper()).post {
            try {
                if (ok) app.toast(app.getString(R.string.ittc_ho_done, h.second.name)) else app.toast(R.string.ittc_ho_failed)
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // ▶ The power dialer: "Save & call next (N left)" · "Stop calling"
    // ------------------------------------------------------------------
    private fun renderDialer() {
        val on = dialerHere()
        val left = IttcDialer.remaining
        binding.ittcAcSave.text = when {
            !on -> getString(R.string.ittc_b_save)
            left > 0 -> getString(R.string.ittc_b_save_next)
            else -> getString(R.string.ittc_b_save_last)
        }
        binding.ittcAcDialer.beGoneIf(!on)
        if (on) {
            val next = IttcDialer.nextLead
            binding.ittcAcDialerText.text = if (next != null && left > 0) {
                val who = next.name.ifBlank { next.number }
                android.text.SpannableStringBuilder(getString(R.string.ittc_b_dialer_next_prefix)).apply {
                    val start = length
                    append(who)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(p.text), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(" · ")
                    append(getString(R.string.ittc_b_dialer_left, left))
                }
            } else {
                getString(R.string.ittc_b_dialer_last)
            }
        }
    }

    // ------------------------------------------------------------------
    // 📝 The admin's quick notes (a tap adds one to the note)
    // ------------------------------------------------------------------
    private var meta: IttcBridge.PhoneMeta? = null

    private fun renderPresets() {
        val presets: List<String> = if (unknownMode) emptyList() else try {
            meta?.notePresets.orEmpty()
        } catch (_: Throwable) {
            emptyList<String>()
        }
        binding.ittcAcPresets.removeAllViews()
        binding.ittcAcPresetsScroll.beGoneIf(presets.isEmpty())
        val m = (resources.displayMetrics.density * 6).toInt()
        presets.forEach { preset ->
            binding.ittcAcPresets.addView(
                softChip(preset) { addPreset(preset) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, m, 0) },
            )
        }
    }

    private fun addPreset(preset: String) {
        val input = binding.ittcAcNoteInput
        val now = input.text?.toString()?.trimEnd().orEmpty()
        val text = if (now.isEmpty()) preset else "$now, $preset"
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
    }

    // ------------------------------------------------------------------
    // ⚡ The next call a result suggests (the admin's rules) — the person's own choice wins
    // ------------------------------------------------------------------
    /** the person chose a next call themselves (then no rule changes it) */
    private var userNext = false

    private fun tomorrowAt10(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun applyAutoNext() {
        if (userNext || unknownMode) return
        val rule = try {
            meta?.nextFor(outcome)
        } catch (_: Throwable) {
            null
        }
        val at = when (rule) {
            is Number -> System.currentTimeMillis() + (rule.toDouble() * 60 * 60 * 1000).toLong()
            is String -> if (rule.equals("tomorrow10", true)) tomorrowAt10() else null
            else -> null
        }
        if (at != null) {
            nextKey = "auto"
            nextAt = at
        } else if (nextKey == "auto") {
            nextKey = "none"
            nextAt = null
        }
    }

    // ------------------------------------------------------------------
    // 🧭 The pipeline stage (Tracker 2.10.6+): open stages of the lead's course
    // ------------------------------------------------------------------
    private var stages: List<IttcBridge.Stage> = emptyList()
    private var currentStageId: String? = null
    private var stageChoice: IttcBridge.Stage? = null

    private fun loadStages(leadId: String, stageName: String?) {
        ensureBackgroundThread {
            // (fresh from the Tracker: the stage may have changed on the last call; the caches only
            // when it cannot answer)
            val fresh = try {
                IttcBridge.phoneMeta(this)
            } catch (_: Throwable) {
                null
            } ?: IttcBridge.metaCache
            val lead = try {
                (IttcBridge.myLeads(this) ?: IttcBridge.myLeadsCache).firstOrNull { it.leadId == leadId }
            } catch (_: Throwable) {
                null
            }
            val list = fresh?.stages.orEmpty().filter { it.forCourse(lead?.courseId) }
            val current = lead?.stageId ?: list.firstOrNull { it.name.equals(stageName, true) }?.id
            runOnUiThread {
                if (isFinishing || isDestroyed || unknownMode || intent.getStringExtra(LEAD_ID) != leadId) return@runOnUiThread
                stages = list
                currentStageId = current
                // (the latest settings: quick notes, and the next call for the result chosen)
                if (fresh != null) {
                    meta = fresh
                    applyAutoNext()
                    renderChoices()
                    renderPresets()
                }
                renderStages()
            }
        }
    }

    private fun renderStages() {
        try {
            renderStagesNow()
        } catch (e: Throwable) {
            // (never lose the result screen over the stage chips)
            org.fossify.phone.helpers.IttcCrash.record(this, e, "After-call stages")
            stages = emptyList()
            binding.ittcAcStageLabel.beGoneIf(true)
            binding.ittcAcStagesScroll.beGoneIf(true)
        }
    }

    private fun renderStagesNow() {
        val show = stages.isNotEmpty()
        binding.ittcAcStageLabel.beGoneIf(!show)
        binding.ittcAcStagesScroll.beGoneIf(!show)
        binding.ittcAcStages.removeAllViews()
        val m = (resources.displayMetrics.density * 8).toInt()
        val selectedId = stageChoice?.id
        // (the current stage first, then the others in the pipeline's order)
        val ordered = stages.filter { it.id == currentStageId } + stages.filter { it.id != currentStageId }
        ordered.forEach { st ->
            val isCurrent = st.id == currentStageId
            binding.ittcAcStages.addView(
                stageChip(st, isCurrent, st.id == selectedId) {
                    // (the current stage again: no change)
                    stageChoice = if (isCurrent || stageChoice?.id == st.id) null else st
                    renderStages()
                    renderNextLabel()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, IttcTheme.dp(this, 40f)).apply { setMargins(0, 0, m, 0) },
            )
        }
        val st = stageChoice
        val hint = when {
            st == null -> null
            st.askDate && nextAt == null -> getString(R.string.ittc_stage_needs_date, st.name, (st.dateLabel ?: "date & time").lowercase())
            st.noteRequired && binding.ittcAcNoteInput.text.isNullOrBlank() -> getString(R.string.ittc_stage_needs_note, st.name)
            !st.askDate && nextAt == null && st.followUpDays != null -> getString(R.string.ittc_stage_auto_next, st.followUpDays)
            else -> null
        }
        binding.ittcAcStageHint.text = hint
        binding.ittcAcStageHint.beGoneIf(hint == null)
    }

    /** "Next call", or the stage's own date name ("Visit date") when the chosen stage asks for one */
    private fun renderNextLabel() {
        val st = stageChoice
        binding.ittcAcNextLabel.text = if (st != null && st.askDate) {
            (st.dateLabel ?: getString(R.string.ittc_next_call)).replaceFirstChar { it.uppercase() }
        } else {
            getString(R.string.ittc_next_call)
        }
    }

    // ------------------------------------------------------------------
    // 📝 The result, a note and the next call (saved through the Tracker)
    // ------------------------------------------------------------------
    private var outcome: String? = null
    private var nextAt: Long? = null
    private var nextKey = "none"
    private var saving = false

    /** value, label, icon */
    private val outcomes = listOf(
        Triple("connected", R.string.ittc_b_out_connected, R.drawable.ic_check_vector),
        Triple("no_answer", R.string.ittc_b_out_no_answer, R.drawable.ic_clear_vector),
        Triple("busy", R.string.ittc_b_out_busy, R.drawable.ic_clock_vector),
        Triple("switched_off", R.string.ittc_b_out_switched_off, R.drawable.ic_phone_down_vector),
        Triple("call_back", R.string.ittc_b_out_call_back, R.drawable.ic_restart_alt_vector),
        Triple("wrong_number", R.string.ittc_b_out_wrong_number, R.drawable.ic_block_vector),
    )

    private fun dp(v: Float) = IttcTheme.dp(this, v)

    /** The system's touch ripple over a view with its own background, kept to its rounded shape */
    private fun touchFeedback(view: View) {
        try {
            IttcUi.ripple(view)
        } catch (_: Throwable) {
        }
    }

    /** A pill: on = filled [fill] (text [onText]); off = outlined */
    private fun chip(label: String, on: Boolean, fill: Int? = null, onText: Int? = null, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(14f), 0, dp(14f), 0)
        gravity = Gravity.CENTER
        maxLines = 1
        val f = fill ?: p.primary
        if (on) IttcTheme.pill(this, f, onText ?: f.getContrastColor()) else IttcTheme.pill(this, p.surface, p.text, p.outline)
        if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
        touchFeedback(this)
        setOnClickListener { onClick() }
    }

    /** A soft quick-note pill (a tap adds it to the note) */
    private fun softChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
        minHeight = dp(40f)
        gravity = Gravity.CENTER
        maxLines = 1
        IttcTheme.pill(this, p.surfaceAlt, p.text)
        touchFeedback(this)
        setOnClickListener { onClick() }
    }

    /** A stage pill: the current one soft in its colour ("• Interested (now)"), the chosen one filled */
    private fun stageChip(st: IttcBridge.Stage, isCurrent: Boolean, chosen: Boolean, onClick: () -> Unit): TextView {
        val (soft, strong) = IttcTheme.stageColors(this, st.name)
        val fill = st.color ?: strong
        return TextView(this).apply {
            text = if (isCurrent) getString(R.string.ittc_b_stage_now, st.name) else st.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14f), 0, dp(14f), 0)
            gravity = Gravity.CENTER
            maxLines = 1
            when {
                chosen -> IttcTheme.pill(this, fill, fill.getContrastColor())
                isCurrent -> IttcTheme.pill(this, soft, strong, strong)
                else -> IttcTheme.pill(this, p.surface, p.text, p.outline)
            }
            if (chosen || isCurrent) setTypeface(typeface, android.graphics.Typeface.BOLD)
            touchFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    /** A big result tile: icon over the label; chosen = teal border on a soft teal */
    private fun outcomeTile(label: String, icon: Int, on: Boolean, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = if (on) IttcTheme.rounded(context, p.callSoft, 14f, p.call, 2f) else IttcTheme.rounded(context, p.surface, 14f, p.outline)
        isClickable = true
        isFocusable = true
        touchFeedback(this)
        contentDescription = label
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(if (on) p.call else p.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
        addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (on) p.call else p.text)
            if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3f) })
        setOnClickListener { onClick() }
    }

    private fun renderChoices() {
        val m = (resources.displayMetrics.density * 4).toInt()
        binding.ittcAcOutcomes.removeAllViews()
        outcomes.chunked(3).forEachIndexed { r, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (value, label, icon) ->
                line.addView(
                    outcomeTile(getString(label), icon, outcome == value) {
                        outcome = if (outcome == value) null else value
                        // (the admin's rule for this result, unless the person chose a time)
                        applyAutoNext()
                        renderChoices()
                        renderStages()
                    },
                    LinearLayout.LayoutParams(0, dp(56f), 1f).apply { setMargins(m, if (r == 0) 0 else m * 2, m, 0) },
                )
            }
            binding.ittcAcOutcomes.addView(line)
        }
        val chipHeight = dp(40f)

        binding.ittcAcNext.removeAllViews()
        val now = System.currentTimeMillis()
        val tomorrow10 = tomorrowAt10()
        // (the time a result's rule chose: shown first, marked ⚡)
        val auto = nextAt
        if (nextKey == "auto" && auto != null) {
            val label = getString(
                R.string.ittc_l2_auto_next,
                DateUtils.formatDateTime(this, auto, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME),
            )
            binding.ittcAcNext.addView(
                chip(label, true) {
                    // (kept: now the person's own choice)
                    userNext = true
                    nextKey = "pick"
                    renderChoices()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight).apply { setMargins(0, 0, m * 2, 0) },
            )
        }
        val options = listOf(
            Triple("none", getString(R.string.ittc_b_next_none), null),
            Triple("1h", getString(R.string.ittc_b_next_1h), now + 60 * 60 * 1000L),
            Triple("tomorrow", getString(R.string.ittc_b_next_tomorrow), tomorrow10),
            Triple("2d", getString(R.string.ittc_b_next_2d), tomorrow10 + 24 * 60 * 60 * 1000L),
        )
        options.forEach { (key, label, at) ->
            binding.ittcAcNext.addView(
                chip(label, nextKey == key) {
                    userNext = true
                    nextKey = key
                    nextAt = at
                    renderChoices()
                    renderStages()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight).apply { setMargins(0, 0, m * 2, 0) },
            )
        }
        val pickedLabel = if (nextKey == "pick" && nextAt != null) {
            DateUtils.formatDateTime(this, nextAt!!, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        } else {
            getString(R.string.ittc_b_next_pick)
        }
        binding.ittcAcNext.addView(
            chip(pickedLabel, nextKey == "pick") { pickDate() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight).apply { setMargins(0, 0, m * 2, 0) },
        )
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { nextAt?.let { timeInMillis = it } }
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                val at = Calendar.getInstance().apply {
                    set(y, mo, d, h, mi, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > System.currentTimeMillis()) {
                    userNext = true
                    nextKey = "pick"
                    nextAt = at
                    renderChoices()
                    renderStages()
                }
            }, 10, 0, android.text.format.DateFormat.is24HourFormat(this)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    /** Save through the Tracker (it sends it); an older Tracker: its own note popup instead */
    private fun save(leadId: String, number: String) {
        if (saving) return
        val note = binding.ittcAcNoteInput.text?.toString()?.trim().orEmpty()
        val stage = stageChoice
        if (outcome == null && note.isEmpty() && nextAt == null && stage == null) {
            toast(R.string.ittc_pick_outcome)
            return
        }
        // (the stage's own rules, as in the Tracker)
        if (stage != null && stage.askDate && nextAt == null) {
            toast(getString(R.string.ittc_stage_needs_date, stage.name, (stage.dateLabel ?: "date & time").lowercase()))
            pickDate()
            return
        }
        if (stage != null && stage.noteRequired && note.isEmpty()) {
            toast(getString(R.string.ittc_stage_needs_note, stage.name))
            binding.ittcAcNoteInput.requestFocus()
            return
        }
        saving = true
        binding.ittcAcSave.isEnabled = false
        // (dimmed while it saves)
        binding.ittcAcSave.alpha = 0.6f
        val chosen = outcome
        val next = nextAt
        val dialing = dialerHere()
        val app = applicationContext
        ensureBackgroundThread {
            // (the call's row in the call log, so the result lands on that very call; if it is not
            // found yet, the Tracker finds it later by the number and the call's start time)
            var tries = 0
            while (chosen != null && IttcBridge.rowIdFor(number) == null && tries < 3) {
                Thread.sleep(500)
                tries++
            }
            val started = intent.getLongExtra(STARTED_AT, 0L).takeIf { it > 0 }
            // (a full disk, a broken file: never the app stopping right after a call)
            val ok = try {
                IttcBridge.saveOutcome(this, leadId, number, chosen, note, next, started, stage)
            } catch (e: Throwable) {
                IttcCrash.record(this, e, "After-call: save")
                false
            }
            // 👥 the hand over chosen here: queued now, after the result (here, not on the screen's
            // thread: the screen may be gone already). Not saved and the screen still up: it waits.
            if (ok || isFinishing || isDestroyed) {
                try {
                    queueHandOverAfterSave(app, leadId)
                } catch (e: Throwable) {
                    IttcCrash.record(app, e, "After-call: hand over")
                }
            }
            runOnUiThread {
                saving = false
                if (isDestroyed) return@runOnUiThread
                binding.ittcAcSave.isEnabled = true
                binding.ittcAcSave.alpha = 1f
                if (ok) {
                    toast(R.string.ittc_saved)
                    // ▶ calling through a list: the next lead at once (the dial screen opens on its own)
                    if (dialing && IttcDialer.currentLead?.leadId == leadId) {
                        try {
                            if (!IttcDialer.next(applicationContext)) toast(R.string.ittc_l2_all_called)
                        } catch (e: Throwable) {
                            IttcCrash.record(this, e, "After-call: call next")
                        }
                    }
                    finish()
                } else {
                    toast(R.string.ittc_old_tracker)
                    addNote(leadId, number)
                }
            }
        }
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

    private fun result(talked: Int, outgoing: Boolean, reason: String?): String = when {
        talked > 0 -> getString(R.string.ittc_b_talked, duration(talked))
        reason == "busy" -> getString(R.string.ittc_b_busy)
        outgoing -> getString(R.string.ittc_b_no_answer)
        reason == "declined" -> getString(R.string.ittc_b_declined)
        else -> getString(R.string.ittc_b_missed)
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
            main.postDelayed({ addNote(leadId, number, tries + 1) }, 700)
            return
        }
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
        private const val STARTED_AT = "ittc_started_at"
        private const val UNKNOWN = "ittc_unknown"
        private const val STATE_SHOWN_KEY = "ittc_shown_key"
        private const val STATE_HO_LEAD = "ittc_ho_lead"
        private const val STATE_HO_TO = "ittc_ho_to"
        private const val STATE_HO_NAME = "ittc_ho_name"
        private const val STATE_HO_NOTE = "ittc_ho_note"

        /** After a call with a number that is no lead (direct mode): "Add as a lead" or close */
        fun openUnknown(context: Context, number: String, outgoing: Boolean, talkedSec: Int, reason: String?, startedAt: Long) {
            try {
                context.startActivity(
                    Intent(context, IttcAfterCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(UNKNOWN, true)
                        .putExtra(NUMBER, number)
                        .putExtra(OUTGOING, outgoing)
                        .putExtra(TALKED_SEC, talkedSec)
                        .putExtra(REASON, reason)
                        .putExtra(STARTED_AT, startedAt),
                )
            } catch (_: Throwable) {
                // (not allowed right now: nothing is lost, it is no lead)
            }
        }

        fun open(
            context: Context,
            lead: IttcBridge.Lead,
            number: String,
            outgoing: Boolean,
            talkedSec: Int,
            reason: String?,
            startedAt: Long,
        ) {
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
                        .putExtra(REASON, reason)
                        .putExtra(STARTED_AT, startedAt),
                )
            } catch (_: Throwable) {
                // (not allowed right now: the Tracker's sync still has the call)
            }
        }
    }
}
