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
import org.fossify.commons.extensions.applyColorFilter
import android.content.res.ColorStateList
import android.provider.CallLog
import java.util.Calendar
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.helpers.ensureBackgroundThread
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
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAfterCallBinding
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCrash
import org.fossify.phone.helpers.IttcDialer

/**
 * 🎓 ITTC: right after a lead call that was not started from the Tracker (an incoming call, or one
 * dialed here): who it was, how the call went, and in one go what happened, the pipeline stage,
 * a note and the next call — saved through the Tracker (an older Tracker: its own note popup).
 * Calls started from the Tracker skip this: the Tracker shows its popup when the call ends.
 * 3.2: the admin's quick notes, the next call a result suggests, the power dialer ("Save & call
 * next"), and a number that is no lead (direct mode): "Add as a lead".
 */
class IttcAfterCallActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAfterCallBinding::inflate)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.ittcAcHolder), padBottomSystem = listOf(binding.ittcAcHolder))
        // (a stage that needs a note: its hint goes once one is written)
        binding.ittcAcNoteInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (stageChoice?.noteRequired == true) renderStages()
            }
        })
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
            shownKey = key
            binding.ittcAcNoteInput.setText("")
        }

        updateTextColors(binding.ittcAcHolder)
        val primary = getProperPrimaryColor()
        binding.apply {
            ittcAcBadge.text = getString(if (unknown) R.string.ittc_l2_unknown_badge else R.string.ittc_after_call_badge)
            ittcAcName.text = name.ifEmpty { number }
            ittcAcNumber.text = number
            ittcAcNumber.beGoneIf(name.isEmpty())
            ittcAcResult.text = result(talked, outgoing, reason)
            ittcAcResult.setTextColor(if (talked > 0) primary else getProperTextColor())
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
            listOf(ittcAcOutcomeLabel, ittcAcOutcomes, ittcAcNoteInput, ittcAcNextLabel, ittcAcNextScroll, ittcAcSave)
                .forEach { it.beGoneIf(!show) }
            if (!show) {
                listOf(ittcAcInfo, ittcAcNote, ittcAcStageLabel, ittcAcStages, ittcAcStageHint, ittcAcPresetsScroll, ittcAcStop)
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
        val primary = getProperPrimaryColor()
        contactName = null
        outcome = null
        stages = emptyList()
        stageChoice = null
        currentStageId = null
        binding.apply {
            ittcAcAddLead.backgroundTintList = ColorStateList.valueOf(primary)
            ittcAcAddLead.setTextColor(primary.getContrastColor())
            ittcAcAddLead.setOnClickListener {
                try {
                    IttcAddLeadActivity.open(this@IttcAfterCallActivity, number, contactName)
                } catch (e: Throwable) {
                    IttcCrash.record(this@IttcAfterCallActivity, e, "After-call: add as a lead")
                }
                finish()
            }
            ittcAcCloseButton.setTextColor(getProperTextColor())
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
        val primary = getProperPrimaryColor()
        binding.apply {
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

            ittcAcSave.backgroundTintList = ColorStateList.valueOf(primary)
            ittcAcSave.setTextColor(primary.getContrastColor())
            ittcAcSave.setOnClickListener { save(leadId, number) }
            ittcAcClose.setOnClickListener {
                // (✕ while calling through a list: the list stops)
                if (IttcDialer.active) {
                    IttcDialer.stop(this@IttcAfterCallActivity)
                    toast(R.string.ittc_l2_calling_stopped)
                }
                finish()
            }
            ittcAcStop.setTextColor(getProperTextColor())
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
        renderPresets()
        IttcDialer.load(this)
        renderDialer()
        showToday()
        loadStages(leadId, intent.getStringExtra(STAGE))
    }

    // ------------------------------------------------------------------
    // ▶ The power dialer: "Save & call next (N left)" · "Stop calling"
    // ------------------------------------------------------------------
    private fun renderDialer() {
        val on = IttcDialer.active && !unknownMode
        binding.ittcAcSave.text = when {
            !on -> getString(R.string.ittc_save)
            IttcDialer.remaining > 0 -> getString(R.string.ittc_l2_save_next, IttcDialer.remaining)
            else -> getString(R.string.ittc_l2_save_last)
        }
        binding.ittcAcStop.beGoneIf(!on)
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
                chip(preset, false) { addPreset(preset) }.apply { maxLines = 1 },
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
            binding.ittcAcStages.beGoneIf(true)
        }
    }

    private fun renderStagesNow() {
        val show = stages.isNotEmpty()
        binding.ittcAcStageLabel.beGoneIf(!show)
        binding.ittcAcStages.beGoneIf(!show)
        binding.ittcAcStages.removeAllViews()
        val m = (resources.displayMetrics.density * 6).toInt()
        val selectedId = stageChoice?.id ?: currentStageId
        stages.chunked(3).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { st ->
                val isCurrent = st.id == currentStageId
                line.addView(
                    chip(if (isCurrent) "• ${st.name}" else st.name, st.id == selectedId, st.color) {
                        // (the current stage again: no change)
                        stageChoice = if (isCurrent || stageChoice?.id == st.id) null else st
                        renderStages()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m, m, 0) },
                )
            }
            // (a short last row keeps the same chip width)
            repeat(3 - row.size) {
                line.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, m, m, 0) })
            }
            binding.ittcAcStages.addView(line)
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

    // ------------------------------------------------------------------
    // 📝 The result, a note and the next call (saved through the Tracker)
    // ------------------------------------------------------------------
    private var outcome: String? = null
    private var nextAt: Long? = null
    private var nextKey = "none"
    private var saving = false

    private val outcomes = listOf(
        "connected" to "✅ Connected",
        "no_answer" to "📵 No answer",
        "busy" to "⏳ Busy",
        "switched_off" to "📴 Off",
        "wrong_number" to "❌ Wrong no.",
        "call_back" to "🔁 Call back",
    )

    private fun chip(label: String, on: Boolean, color: Int? = null, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.ittc_chip_background)
        val primary = color ?: getProperPrimaryColor()
        val text = getProperTextColor()
        background.mutate().applyColorFilter(if (on) primary else (text and 0x00FFFFFF) or 0x22000000)
        setTextColor(if (on) primary.getContrastColor() else text)
        maxLines = 2
        setOnClickListener { onClick() }
    }

    private fun renderChoices() {
        val m = (resources.displayMetrics.density * 6).toInt()
        binding.ittcAcOutcomes.removeAllViews()
        outcomes.chunked(3).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (value, label) ->
                line.addView(
                    chip(label, outcome == value) {
                        outcome = if (outcome == value) null else value
                        // (the admin's rule for this result, unless the person chose a time)
                        applyAutoNext()
                        renderChoices()
                        renderStages()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m, m, 0) },
                )
            }
            binding.ittcAcOutcomes.addView(line)
        }

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
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, 0, 0, 0) },
            )
        }
        val options = listOf(
            Triple("none", "No", null),
            Triple("1h", "In 1 hour", now + 60 * 60 * 1000L),
            Triple("tomorrow", "Tomorrow 10 AM", tomorrow10),
            Triple("2d", "In 2 days", tomorrow10 + 24 * 60 * 60 * 1000L),
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
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, 0, 0, 0) },
            )
        }
        val pickedLabel = if (nextKey == "pick" && nextAt != null) {
            "📅 " + DateUtils.formatDateTime(this, nextAt!!, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
        } else {
            "📅 Pick…"
        }
        binding.ittcAcNext.addView(
            chip(pickedLabel, nextKey == "pick") { pickDate() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, 0, m, 0) },
        )
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { nextAt?.let { timeInMillis = it } }
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                val at = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }.timeInMillis
                if (at > System.currentTimeMillis()) {
                    userNext = true
                    nextKey = "pick"
                    nextAt = at
                    renderChoices()
                    renderStages()
                }
            }, 10, 0, false).show()
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
        val chosen = outcome
        val next = nextAt
        val dialing = IttcDialer.active
        ensureBackgroundThread {
            // (the call's row in the call log, so the result lands on that very call; if it is not
            // found yet, the Tracker finds it later by the number and the call's start time)
            var tries = 0
            while (chosen != null && IttcBridge.rowIdFor(number) == null && tries < 3) {
                Thread.sleep(500)
                tries++
            }
            val started = intent.getLongExtra(STARTED_AT, 0L).takeIf { it > 0 }
            val ok = IttcBridge.saveOutcome(this, leadId, number, chosen, note, next, started, stage)
            runOnUiThread {
                saving = false
                if (isDestroyed) return@runOnUiThread
                binding.ittcAcSave.isEnabled = true
                if (ok) {
                    toast(R.string.ittc_saved)
                    // ▶ calling through a list: the next lead at once (the dial screen opens on its own)
                    if (dialing && IttcDialer.active) {
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
