package org.fossify.phone.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.setNavigationBarAppearance
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyEditText
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityLeadBinding
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcLeadApi
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCrash
import org.fossify.phone.helpers.IttcDisclosure
import org.fossify.phone.helpers.IttcTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 🎓 ITTC direct mode: one lead (opened from the Leads tab, a number, a reminder…): who it is, its
 * numbers, call / WhatsApp / SMS / templates, quick changes (note, stage, next call, rating, edit,
 * hand over) and its timeline. Changes go through IttcDirect's queue (sent when online) and show
 * at once; the lead is read again a moment later.
 * 3.5: the new look — a blue hero, the stage card (the course's stages as steps, the next call),
 * big actions, a chips row (visit, fee, papers, payment, admit and the rest), the visit / fee and
 * admission cards (IttcLeadExtras, IttcAdmitCard) and the timeline as cards by day.
 */
class IttcLeadActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityLeadBinding::inflate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: IttcTheme.Palette

    private var leadId = ""

    /** What is shown: phone_lead_detail's keys (offline: the kept lead's, the same keys) */
    private var lead: JSONObject? = null
    private var fromServer = false
    private var meta: IttcBridge.PhoneMeta? = null
    private var loadSeq = 0
    private var templatesWanted = false
    private var notesOpen = false
    /** A change was queued here: while the queue still holds something, what is shown (the change
     * made at once) stays rather than the server's older copy */
    private var queuedHere = false
    private val retryLoad = Runnable { if (alive()) load() }

    /** Signed in to ITTC here (read off the main thread: the session is kept encrypted) */
    private var signedIn = false

    /** The visit / fee and admission cards were filled once (they reload after a change) */
    private var cardsBound = false
    /** Back from the admission screen: read the lead again */
    private var reloadOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leadId = intent.getStringExtra(LEAD_ID).orEmpty()
        if (leadId.isBlank()) {
            finish()
            return
        }
        p = IttcTheme.of(this)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcLeadScrollview))
            // (no colour change on scroll: the bar stays the hero's blue)
            ittcLeadCall.setOnClickListener { callLead() }
            ittcLeadWhatsapp.setOnClickListener { openWhatsApp(null) }
            ittcLeadSms.setOnClickListener { openSms() }
            ittcLeadNote.setOnClickListener { addNote() }
            ittcLeadInfo.setOnClickListener { callLead() }
            ittcLeadStar.setOnClickListener { if (signedIn) changeRating() }
            ittcLeadMore.setOnClickListener { showMore() }
            ittcLeadChangeStage.setOnClickListener { changeStage() }
            ittcLeadNext.setOnClickListener { if (signedIn) nextCall() }
            ittcLeadStepper.setOnClickListener { if (signedIn) changeStage() }
            ittcLeadNotes.setOnClickListener {
                notesOpen = !notesOpen
                ittcLeadNotes.maxLines = if (notesOpen) Int.MAX_VALUE else 6
            }
        }
        styleStatic()
        templatesWanted = savedInstanceState == null && intent.getBooleanExtra(SHOW_TEMPLATES, false)
        render()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::p.isInitialized) return
        setupTopAppBar(binding.ittcLeadAppbar, NavigationIcon.Arrow, topBarColor = p.primary)
        try {
            window.setNavigationBarAppearance(p.bg)
        } catch (_: Throwable) {
        }
        if (reloadOnResume) {
            // (back from the admission screen: the lead and both cards again, even offline)
            reloadOnResume = false
            cardsBound = false
            load()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------------
    // 📥 Loading: the kept lead at once, then the server's (with the timeline)
    // ------------------------------------------------------------------
    private fun load() {
        val seq = ++loadSeq
        val id = leadId
        val needKept = lead == null
        ensureBackgroundThread {
            val inNow = try {
                IttcDirect.isSignedIn(this)
            } catch (_: Throwable) {
                false
            }
            val m = try {
                IttcDirect.meta(this) ?: IttcBridge.metaCache
            } catch (_: Throwable) {
                IttcBridge.metaCache
            }
            val kept = if (!needKept) null else try {
                IttcDirect.leads(this).firstOrNull { it.leadId == id }?.let { keptJson(it, m) }
            } catch (_: Throwable) {
                null
            }
            if (kept != null) {
                runOnUiThread {
                    if (alive() && seq == loadSeq && lead == null) {
                        signedIn = inNow
                        meta = m
                        lead = kept
                        render()
                    }
                }
            }
            var status: String? = null
            val detail = try {
                IttcLeadApi.detail(this, id).also { if (it == null) status = getString(R.string.ittc_lp_not_found) }
            } catch (e: IttcApi.ApiError) {
                status = when (e.status) {
                    0 -> getString(R.string.ittc_lp_offline)
                    401 -> getString(R.string.ittc_lp_not_signed_in)
                    else -> e.message ?: getString(R.string.ittc_lp_offline)
                }
                null
            } catch (e: Throwable) {
                status = e.message ?: getString(R.string.ittc_lp_offline)
                null
            }
            val pending = if (!queuedHere) 0 else try {
                IttcDirect.pendingFor(this, id)
            } catch (_: Throwable) {
                0
            }
            runOnUiThread {
                if (!alive() || seq != loadSeq) return@runOnUiThread
                signedIn = inNow
                meta = m
                // (a change made here not sent yet: keep it on screen, say it waits, look again later)
                val waiting = queuedHere && pending > 0 && lead != null
                if (waiting) {
                    status = getString(R.string.ittc_lp_waiting)
                    main.removeCallbacks(retryLoad)
                    main.postDelayed(retryLoad, 15_000)
                } else {
                    if (queuedHere && pending == 0) queuedHere = false
                    if (detail != null) {
                        lead = detail
                        fromServer = true
                    } else if (lead == null && kept != null) {
                        lead = kept
                    }
                }
                binding.ittcLeadStatus.text = status
                binding.ittcLeadStatus.beVisibleIf(status != null)
                render()
                // (the visit / fee and admission cards: when the server answered, or once)
                if (detail != null || !cardsBound) bindCards()
                if (templatesWanted && lead != null && status == null) {
                    templatesWanted = false
                    showTemplates()
                }
            }
        }
    }

    /** The kept lead (Leads tab list) with phone_lead_detail's keys */
    private fun keptJson(p: IttcBridge.PhoneLead, m: IttcBridge.PhoneMeta?): JSONObject = JSONObject().apply {
        put("lead_id", p.leadId)
        put("name", p.name)
        put("phone", p.phone)
        put("alt_phone", p.altPhone)
        put("whatsapp_number", p.whatsapp)
        put("stage", p.stage)
        put("stage_id", p.stageId)
        put("course", p.course)
        put("course_id", p.courseId)
        put("batch", p.batch)
        put("rating", p.rating)
        p.nextCall?.let { put("next_call", isoOf(it)) }
        put("call_attempts", p.attempts)
        put("assigned_to", p.assignedTo)
        put("owner", m?.people?.firstOrNull { it.id == p.assignedTo }?.name)
        put("last_note", p.lastNote)
    }

    /** 🏫 🎓 The visit / fee card (Part C) and the admission card (Part D); never the page over them */
    private fun bindCards() {
        val extras = binding.ittcLeadExtras
        val admit = binding.ittcLeadAdmit
        if (!signedIn) {
            extras.removeAllViews()
            admit.removeAllViews()
            return
        }
        cardsBound = true
        try {
            IttcLeadExtras.bind(this, extras, leadId)
        } catch (e: Throwable) {
            IttcCrash.record(this, e, "Lead page: visit / fee card")
        }
        try {
            IttcAdmitCard.bind(this, admit, leadId)
        } catch (e: Throwable) {
            IttcCrash.record(this, e, "Lead page: admission card")
        }
    }

    /** After a sheet saved something: the lead and its cards again */
    private fun reload() {
        if (!alive()) return
        load()
    }

    // ------------------------------------------------------------------
    // 🖼 Showing it
    // ------------------------------------------------------------------
    private fun dpf(v: Float) = IttcTheme.dp(this, v)

    /** 🎨 The parts whose colours do not change with the lead */
    private fun styleStatic() {
        binding.apply {
            ittcLeadCoordinator.setBackgroundColor(p.bg)
            ittcLeadHeader.setBackgroundColor(p.primary)
            ittcLeadName.setTextColor(p.onPrimary)
            ittcLeadInfo.setTextColor(p.onPrimaryMuted)
            ittcLeadMeta.setTextColor(p.onPrimaryMuted)
            ittcLeadMore.setColorFilter(p.onPrimary)
            ittcLeadStageCard.background = IttcTheme.rounded(this@IttcLeadActivity, p.surface, 16f)
            ittcLeadStageDivider.setBackgroundColor(p.divider)
            ittcLeadNext.setTextColor(p.text2)
            ittcLeadChangeStage.background = IttcTheme.rounded(this@IttcLeadActivity, p.surface, 999f, p.outline)
            ittcLeadChangeStage.setTextColor(p.primary)
            ittcLeadStatus.setTextColor(p.muted)
            ittcLeadNotesLabel.setTextColor(p.muted)
            ittcLeadNotes.background = IttcTheme.rounded(this@IttcLeadActivity, p.surface, 14f)
            ittcLeadNotes.setTextColor(p.text2)
            ittcLeadNumbers.background = IttcTheme.rounded(this@IttcLeadActivity, p.surface, 14f)

            // the four big actions: Call teal, the others white cards with a coloured icon
            ittcLeadCall.background = IttcTheme.rounded(this@IttcLeadActivity, p.call, 16f)
            ittcLeadCall.setTextColor(Color.WHITE)
            val amber = IttcTheme.stageColors(this@IttcLeadActivity, "interested").second
            listOf(
                Triple(ittcLeadCall, R.drawable.ic_phone_vector, Color.WHITE),
                Triple(ittcLeadWhatsapp, R.drawable.ic_sms_vector, WHATSAPP_GREEN),
                Triple(ittcLeadSms, R.drawable.ic_sms_vector, p.primary),
                Triple(ittcLeadNote, R.drawable.ic_edit_vector, amber),
            ).forEach { (view, icon, color) ->
                if (view != ittcLeadCall) {
                    view.background = IttcTheme.rounded(this@IttcLeadActivity, p.surface, 16f)
                    view.setTextColor(p.text)
                }
                val d = resources.getColoredDrawableWithColor(icon, color)
                val size = dpf(22f)
                d.setBounds(0, 0, size, size)
                view.setCompoundDrawablesRelative(null, d, null, null)
                touchFeedback(view)
            }
            touchFeedback(ittcLeadChangeStage)
        }
    }

    private fun render() {
        val l = lead
        val has = l != null
        binding.apply {
            ittcLeadHeader.beVisibleIf(has)
            ittcLeadStageCard.beVisibleIf(has)
            ittcLeadActions.beVisibleIf(has)
            ittcLeadChipsScroll.beVisibleIf(has)
            ittcLeadNote.beVisibleIf(signedIn)
            ittcLeadChangeStage.beVisibleIf(signedIn)
            ittcLeadStar.beVisibleIf(has && signedIn)
            ittcLeadMore.beVisibleIf(has)
        }
        if (l == null) {
            binding.ittcLeadNotesLabel.beGone()
            binding.ittcLeadNotes.beGone()
            binding.ittcLeadNumbers.beGone()
            binding.ittcLeadTimeline.removeAllViews()
            return
        }
        renderHero(l)
        renderStepper(l)
        renderNext(l)
        renderNumbers(l)
        renderChips()

        val notes = l.s("notes") ?: l.s("last_note")
        binding.ittcLeadNotesLabel.beVisibleIf(notes != null)
        binding.ittcLeadNotes.beVisibleIf(notes != null)
        binding.ittcLeadNotes.text = notes

        renderTimeline(l)
    }

    /** 🟦 Avatar, name, "number · course · batch", "Hot · AI chance 72% · Assigned to you" */
    private fun renderHero(l: JSONObject) {
        val name = l.s("name") ?: l.s("phone") ?: ""
        binding.ittcLeadName.text = name
        val (avBg, avFg) = IttcTheme.avatarColors(this, name)
        binding.ittcLeadAvatar.text = IttcTheme.initials(l.s("name"))
        binding.ittcLeadAvatar.setTextColor(avFg)
        binding.ittcLeadAvatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avBg)
        }

        val info = listOfNotNull(numbers(l).firstOrNull()?.first, l.s("course"), l.s("batch")).joinToString(" · ")
        binding.ittcLeadInfo.text = info
        binding.ittcLeadInfo.beVisibleIf(info.isNotEmpty())

        val owner = when {
            l.s("assigned_to") != null && l.s("assigned_to") == meta?.meId -> getString(R.string.ittc_b_assigned_you)
            l.s("owner") != null -> getString(R.string.ittc_b_assigned_to, l.s("owner"))
            else -> null
        }
        val attempts = l.optInt("call_attempts", 0)
        val line = listOfNotNull(
            l.s("rating")?.let { ratingLabel(it) },
            aiChance(l)?.let { getString(R.string.ittc_b_ai_chance, it) },
            owner,
            attempts.takeIf { it > 0 }?.let { getString(R.string.ittc_lp_attempts, it) },
        ).joinToString(" · ")
        binding.ittcLeadMeta.text = line
        binding.ittcLeadMeta.beVisibleIf(line.isNotEmpty())

        val rated = l.s("rating") != null
        binding.ittcLeadStar.setImageResource(if (rated) R.drawable.ic_star_vector else R.drawable.ic_star_outline_vector)
        binding.ittcLeadStar.setColorFilter(p.onPrimary)
    }

    /** The AI's admission chance, when the server gives one (0–1 or 0–100) → "72%" */
    private fun aiChance(l: JSONObject): String? {
        val key = listOf("ai_chance", "chance").firstOrNull { l.has(it) && !l.isNull(it) } ?: return null
        val v = l.optDouble(key, Double.NaN)
        if (v.isNaN() || v < 0) return null
        val pct = if (v <= 1.0) v * 100 else v
        return "${Math.round(pct.coerceAtMost(100.0))}%"
    }

    // ------------------------------------------------------------------
    // 🧭 The stage steps: first, previous, current, next, last (Admitted) — at most 5
    // ------------------------------------------------------------------
    private fun isClosed(name: String): Boolean {
        val s = name.lowercase()
        return s.contains("not now") || s.contains("lost") || s.contains("cancel") || s.contains("drop")
    }

    private fun renderStepper(l: JSONObject) {
        val box = binding.ittcLeadStepper
        box.removeAllViews()
        val curId = l.s("stage_id")
        val curName = l.s("stage")
        val all = meta?.stages.orEmpty().filter { it.forCourse(l.s("course_id")) }
        val list = all.filter { !isClosed(it.name) || it.id == curId }
        var ci = list.indexOfFirst { it.id == curId }
        if (ci < 0 && curName != null) ci = list.indexOfFirst { it.name.equals(curName, true) }
        if (ci < 0) {
            // (the stage is not among the course's stages: just its name)
            if (curName != null) box.addView(step(curName, null, STEP_CURRENT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.beVisibleIf(curName != null)
            binding.ittcLeadStageDivider.beVisibleIf(curName != null)
            return
        }
        box.beVisibleIf(true)
        binding.ittcLeadStageDivider.beVisibleIf(true)
        val shown: List<Int> = if (list.size <= 5) {
            list.indices.toList()
        } else {
            val admitted = list.indexOfLast { it.name.contains("admitted", true) && !it.name.contains("cancel", true) }
            val last = if (admitted >= 0) admitted else list.lastIndex
            sortedSetOf(0, ci - 1, ci, ci + 1, last).filter { it in list.indices }.take(5)
        }
        shown.forEach { i ->
            val st = list[i]
            val state = when {
                i < ci -> STEP_DONE
                i == ci -> STEP_CURRENT
                else -> STEP_TODO
            }
            box.addView(step(st.name, st.color, state), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** One step: a ticked green dot (done), a big dot in the stage's colour with a ring (now), an
     * empty ring (to come); the name under it */
    private fun step(name: String, color: Int?, state: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val (soft, strong) = IttcTheme.stageColors(context, name)
        val done = IttcTheme.stageColors(context, "admitted").second
        val dot = FrameLayout(context)
        val size = when (state) {
            STEP_CURRENT -> dpf(30f)
            else -> dpf(22f)
        }
        val mark: View = if (state == STEP_DONE) {
            ImageView(context).apply {
                setImageResource(R.drawable.ic_check_vector)
                setColorFilter(Color.WHITE)
                setPadding(dpf(4f), dpf(4f), dpf(4f), dpf(4f))
                background = oval(done, 0, 0f)
            }
        } else if (state == STEP_CURRENT) {
            View(context).apply { background = oval(color ?: strong, soft, 4f) }
        } else {
            View(context).apply { background = oval(p.surface, p.outline, 2f) }
        }
        dot.addView(mark, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        addView(dot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpf(30f)))
        addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            when (state) {
                STEP_CURRENT -> {
                    setTextColor(strong)
                    setTypeface(typeface, Typeface.BOLD)
                }
                STEP_DONE -> setTextColor(p.text2)
                else -> setTextColor(p.muted)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpf(4f) })
    }

    private fun oval(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dpf(strokeDp), stroke)
    }

    /** "Next call Today 10:30 AM" (late: red) */
    private fun renderNext(l: JSONObject) {
        val next = IttcBridge.parseTimeOf(l.s("next_call"))
        if (next == null) {
            binding.ittcLeadNext.text = getString(R.string.ittc_b_no_next_call)
            return
        }
        val late = next < System.currentTimeMillis()
        val label = getString(R.string.ittc_b_next_call_label)
        binding.ittcLeadNext.text = SpannableStringBuilder(label).apply {
            append(" ")
            val start = length
            append(dayWhen(next))
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(if (late) p.danger else p.text), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** "Today 10:30 AM", "Tomorrow 10:00 AM", "Sat, 12 Dec 11:00 AM" */
    private fun dayWhen(ms: Long): String {
        val time = DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_TIME)
        return when {
            DateUtils.isToday(ms) -> getString(R.string.ittc_b_today_at, time)
            DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS) -> getString(R.string.ittc_b_tomorrow_at, time)
            DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> getString(R.string.ittc_b_yesterday_at, time)
            else -> whenText(ms)
        }
    }

    /** The lead's numbers: phone, other phone, WhatsApp (each once; a tap calls it) */
    private fun numbers(l: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        listOf("phone" to "", "alt_phone" to getString(R.string.ittc_lp_alt_phone), "whatsapp_number" to getString(R.string.ittc_lp_whatsapp))
            .forEach { (key, label) ->
                val n = l.s(key) ?: return@forEach
                val k = IttcDirect.normalizeBd(n) ?: n.filter { it.isDigit() }
                if (seen.add(k)) out += n to label
            }
        return out
    }

    /** A card with the numbers, only when there is more than one (one: it is in the hero) */
    private fun renderNumbers(l: JSONObject) {
        val list = binding.ittcLeadNumbers
        list.removeAllViews()
        val all = numbers(l)
        list.beVisibleIf(all.size > 1)
        if (all.size <= 1) return
        val icon = resources.getColoredDrawableWithColor(R.drawable.ic_phone_vector, p.call)
        all.forEachIndexed { i, (number, label) ->
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(p.divider) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            list.addView(TextView(this).apply {
                this.text = if (label.isEmpty()) number else "$number  ·  $label"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(p.text)
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = dp(12)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setBackgroundResource(ripple())
                setOnClickListener { call(number) }
            })
        }
    }

    /** Only super admins: my_phone_data lists the people (whom to hand over to) only for them */
    private fun canHandOver(): Boolean = meta?.isSuper == true

    /** The chips row: admission steps first, then the rest */
    private fun chipItems(): List<Pair<Int, () -> Unit>> {
        if (!signedIn) return emptyList()
        val items = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.ittc_b_book_visit to { bookVisit() },
            R.string.ittc_b_fee to { feeOffer() },
            R.string.ittc_b_papers to { papers() },
            R.string.ittc_b_payment to { payment() },
            R.string.ittc_b_admit to { admit() },
            R.string.ittc_b_templates to { showTemplates() },
            R.string.ittc_b_next_call to { nextCall() },
            R.string.ittc_b_rating to { changeRating() },
            R.string.ittc_b_edit to { editLead() },
        )
        if (canHandOver()) items += R.string.ittc_b_hand_over to { handOver() }
        items += R.string.ittc_b_web to { openWeb() }
        return items
    }

    private fun renderChips() {
        val box = binding.ittcLeadChips
        box.removeAllViews()
        val items = chipItems()
        binding.ittcLeadChipsScroll.beVisibleIf(items.isNotEmpty())
        items.forEach { (label, action) ->
            box.addView(
                TextView(this).apply {
                    setText(label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(14), 0, dp(14), 0)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    IttcTheme.pill(this, p.surface, p.text, p.outline)
                    touchFeedback(this)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) },
            )
        }
    }

    /** ⋮ More: everything the page can do, in one list */
    private fun showMore() {
        if (lead == null) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (signedIn) {
            items += getString(R.string.ittc_b_change_stage) to { changeStage() }
            items += getString(R.string.ittc_b_add_note) to { addNote() }
        }
        chipItems().forEach { (label, action) -> items += getString(label) to action }
        if (!signedIn) items += getString(R.string.ittc_b_web) to { openWeb() }
        pickList(getString(R.string.ittc_b_more), items.map { it.first }) { items[it].second() }
    }

    // ------------------------------------------------------------------
    // 🏫 💰 📄 🎓 Visit, fee, papers, payment, admission (the sheets of 3.4 / 3.5)
    // ------------------------------------------------------------------
    private fun guarded(where: String, block: () -> Unit) {
        if (!signedIn) return toast(R.string.ittc_lp_not_signed_in)
        try {
            block()
        } catch (e: Throwable) {
            IttcCrash.record(this, e, "Lead page: $where")
            toast(R.string.ittc_lp_no_app)
        }
    }

    private fun bookVisit() = guarded("visit") { IttcVisitSheet.show(this, leadId, lead?.s("name")) { reload() } }

    private fun feeOffer() = guarded("fee") { IttcFeeSheet.show(this, leadId) { reload() } }

    private fun papers() = guarded("papers") {
        IttcPapersSheet.show(this, leadId, lead?.s("name"), lead?.let { it.s("phone") ?: it.s("whatsapp_number") ?: it.s("alt_phone") }) { reload() }
    }

    private fun payment() = guarded("payment") { IttcPaymentSheet.show(this, leadId) { reload() } }

    private fun admit() = guarded("admit") {
        reloadOnResume = true
        IttcAdmitActivity.open(this, leadId)
    }

    // ------------------------------------------------------------------
    // 🕘 The timeline: cards by day (TODAY / YESTERDAY / a date), a round icon per kind
    // ------------------------------------------------------------------
    private class Item(val at: Long, val by: String?, val text: String, val note: String?, val kind: Int)

    private fun renderTimeline(l: JSONObject) {
        val list = binding.ittcLeadTimeline
        list.removeAllViews()
        if (!fromServer) return
        val items = ArrayList<Item>()
        l.optJSONArray("calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val at = IttcBridge.parseTimeOf(c.s("at")) ?: continue
                val outcome = c.s("outcome")
                val dur = c.optInt("duration_sec", 0)
                val kind = when (outcome) {
                    "message" -> K_MESSAGE
                    "connected" -> K_CALL_OK
                    null -> if (dur > 0) K_CALL_OK else K_CALL_MISSED
                    else -> K_CALL_MISSED
                }
                val what = if (outcome == "message") getString(R.string.ittc_b_tl_message)
                else listOfNotNull(
                    getString(R.string.ittc_b_tl_call),
                    dur.takeIf { it > 0 }?.let { duration(it) },
                    outcome?.let { OUTCOMES[it] ?: it.replace('_', ' ') },
                ).joinToString(" · ")
                items += Item(at, c.s("by"), what, c.s("notes"), kind)
            }
        }
        l.optJSONArray("activity")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val at = IttcBridge.parseTimeOf(a.s("at")) ?: continue
                val action = a.s("action").orEmpty()
                val details = a.optJSONObject("details")
                val note = if (action == "note_added") details?.s("note") else null
                items += Item(at, a.s("by"), describe(action, details), note, kindOf(action))
            }
        }
        items.sortByDescending { it.at }
        if (items.isEmpty()) {
            list.addView(sectionHead(getString(R.string.ittc_lp_timeline)))
            list.addView(TextView(this).apply {
                setText(R.string.ittc_lp_timeline_none)
                setTextColor(p.muted)
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }
        val me = myName()
        var lastDay: String? = null
        items.forEach { item ->
            val day = dayHead(item.at)
            if (day != lastDay) {
                list.addView(sectionHead(day))
                lastDay = day
            }
            list.addView(card(item, me), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun dayHead(ms: Long): String = when {
        DateUtils.isToday(ms) -> getString(R.string.ittc_b_today)
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> getString(R.string.ittc_b_yesterday)
        else -> DateUtils.formatDateTime(
            this, ms,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
    }.uppercase()

    private fun sectionHead(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.04f
        setTextColor(p.muted)
        setPadding(0, dp(12), 0, dp(8))
    }

    /** (icon, its colour, the circle's colour) for a kind */
    private fun look(kind: Int): Triple<Int, Int, Int> {
        val (amberBg, amber) = IttcTheme.stageColors(this, "interested")
        val (purpleBg, purple) = IttcTheme.stageColors(this, "visit")
        val (greenBg, green) = IttcTheme.stageColors(this, "admitted")
        return when (kind) {
            K_CALL_OK -> Triple(R.drawable.ic_call_made_vector, p.call, p.callSoft)
            K_CALL_MISSED -> Triple(R.drawable.ic_call_missed_vector, p.danger, p.dangerSoft)
            K_MESSAGE -> Triple(R.drawable.ic_sms_vector, p.primary, p.primarySoft)
            K_STAGE -> Triple(R.drawable.ic_arrow_right_vector, amber, amberBg)
            K_NOTE -> Triple(R.drawable.ic_edit_vector, amber, amberBg)
            K_NEXT -> Triple(R.drawable.ic_clock_vector, p.primary, p.primarySoft)
            K_PEOPLE -> Triple(R.drawable.ic_people_vector, p.primary, p.primarySoft)
            K_RATING -> Triple(R.drawable.ic_star_vector, amber, amberBg)
            K_VISIT -> Triple(R.drawable.ic_place_vector, purple, purpleBg)
            K_MONEY -> Triple(R.drawable.ic_dollar_vector, green, greenBg)
            K_ADDED -> Triple(R.drawable.ic_plus_vector, green, greenBg)
            else -> Triple(R.drawable.ic_info_vector, p.muted, p.surfaceAlt)
        }
    }

    private fun kindOf(action: String): Int = when {
        action == "stage_changed" -> K_STAGE
        action.startsWith("note") -> K_NOTE
        action.startsWith("follow_up") -> K_NEXT
        action == "assigned" -> K_PEOPLE
        action == "rating_changed" -> K_RATING
        action.startsWith("visit") -> K_VISIT
        action.startsWith("payment") || action.startsWith("fee") -> K_MONEY
        action == "created" || action == "re_enquiry" || action == "admitted" -> K_ADDED
        else -> K_OTHER
    }

    private fun card(item: Item, me: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = IttcTheme.rounded(context, p.surface, 14f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val (icon, fg, bg) = look(item.kind)
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(fg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = oval(bg, 0, 0f)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = item.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.text)
            })
            item.note?.let { note ->
                addView(TextView(context).apply {
                    text = "“$note”"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(p.text2)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            addView(TextView(context).apply {
                text = listOfNotNull(DateUtils.formatDateTime(context, item.at, DateUtils.FORMAT_SHOW_TIME), byName(item.by, me)).joinToString(" · ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(p.muted)
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    /** "You" for my own changes */
    private fun byName(by: String?, me: String?): String? {
        if (by == null) return null
        return if (by == me) getString(R.string.ittc_b_you) else by
    }

    /** One change in words (the Tracker's common ones; the rest by their name) */
    private fun describe(action: String, d: JSONObject?): String {
        fun stageName(id: String?) = meta?.stages?.firstOrNull { it.id == id }?.name ?: "—"
        fun person(id: String?) = meta?.people?.firstOrNull { it.id == id }?.name ?: "someone"
        return when (action) {
            "stage_changed" -> "Stage: ${stageName(d?.s("from"))} → ${stageName(d?.s("to"))}"
            "note_added" -> "Note added"
            "note_edited" -> "Notes edited"
            "follow_up_set" -> "Next call " + (IttcBridge.parseTimeOf(d?.s("due_at"))?.let { whenText(it) } ?: "")
            "follow_up_cancelled" -> "Next call removed"
            "assigned" -> d?.s("to")?.let { "Handed to ${person(it)}" } ?: "Unassigned"
            "rating_changed" -> "Rating: ${d?.s("from") ?: "—"} → ${d?.s("to") ?: "—"}"
            "created" -> "Lead added"
            "re_enquiry" -> "Asked again"
            "details_changed", "fields_changed" -> "Details changed"
            "course_added" -> "Course + ${d?.s("course") ?: "—"}"
            "course_removed" -> "Course − ${d?.s("course") ?: "—"}"
            "tag_added" -> "Tag + ${d?.s("tag") ?: "—"}"
            "tag_removed" -> "Tag − ${d?.s("tag") ?: "—"}"
            "visit_planned" -> "Visit planned " + (IttcBridge.parseTimeOf(d?.s("at"))?.let { whenText(it) } ?: "")
            "visit_done" -> "Visited the campus"
            "payment_received" -> "Payment received"
            else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    // ------------------------------------------------------------------
    // 📞 Call / WhatsApp / SMS
    // ------------------------------------------------------------------
    private fun leadName() = lead?.s("name") ?: ""

    private fun call(number: String) = startCallWithConfirmationCheck(number, leadName().ifEmpty { number })

    private fun callLead() {
        val l = lead ?: return
        val list = numbers(l)
        when {
            list.isEmpty() -> toast(R.string.ittc_lp_no_number)
            list.size == 1 -> call(list[0].first)
            else -> pickList(getString(R.string.ittc_lp_call), list.map { (n, label) -> if (label.isEmpty()) n else "$n  ·  $label" }) {
                call(list[it].first)
            }
        }
    }

    private fun whatsAppNumber(): String? = lead?.let { it.s("whatsapp_number") ?: it.s("phone") ?: it.s("alt_phone") }

    private fun smsNumber(): String? = lead?.let { it.s("phone") ?: it.s("alt_phone") ?: it.s("whatsapp_number") }

    /** true: WhatsApp opened */
    private fun openWhatsApp(text: String?): Boolean {
        val n = whatsAppNumber() ?: run {
            toast(R.string.ittc_lp_no_number)
            return false
        }
        val url = "https://wa.me/${IttcLeadApi.waNumber(n)}" + (text?.let { "?text=" + Uri.encode(it) } ?: "")
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Throwable) {
            toast(R.string.ittc_lp_no_app)
            false
        }
    }

    private fun openSms() {
        val n = smsNumber() ?: return toast(R.string.ittc_lp_no_number)
        launchSendSMSIntent(n)
    }

    /** true: the SMS app opened with the text */
    private fun sendSms(text: String): Boolean {
        val n = smsNumber() ?: run {
            toast(R.string.ittc_lp_no_number)
            return false
        }
        return try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", text))
            true
        } catch (_: Throwable) {
            toast(R.string.ittc_lp_no_app)
            false
        }
    }

    private fun openWeb() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(IttcLeadApi.webUrl(leadId))))
        } catch (_: Throwable) {
            toast(R.string.ittc_lp_no_app)
        }
    }

    // ------------------------------------------------------------------
    // 💬 Templates
    // ------------------------------------------------------------------
    private fun showTemplates() {
        if (lead == null) return
        if (!signedIn) return toast(R.string.ittc_lp_not_signed_in)
        ensureBackgroundThread {
            val result = try {
                Triple(IttcLeadApi.templates(this), IttcLeadApi.institute(this), null as String?)
            } catch (e: Throwable) {
                Triple(null, null, if (e is IttcApi.ApiError && e.status == 0) getString(R.string.ittc_lp_offline) else e.message ?: "")
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                val (list, institute, error) = result
                if (list == null) {
                    toast(getString(R.string.ittc_lp_templates_failed, error ?: ""))
                } else {
                    pickTemplate(list, institute ?: JSONObject(), "whatsapp")
                }
            }
        }
    }

    private fun pickTemplate(all: List<IttcLeadApi.Template>, institute: JSONObject, channel: String) {
        val courseId = lead?.s("course_id")
        val list = all.filter { it.forChannel(channel) && it.forCourse(courseId) }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dialog: AlertDialog? = null
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("whatsapp" to getString(R.string.ittc_lp_whatsapp), "sms" to getString(R.string.ittc_lp_sms)).forEach { (key, label) ->
            tabs.addView(
                chip(label, key == channel) {
                    if (key != channel) {
                        dialog?.dismiss()
                        pickTemplate(all, institute, key)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), dp(8)) },
            )
        }
        holder.addView(tabs)
        val fg = getProperTextColor()
        if (list.isEmpty()) {
            holder.addView(TextView(this).apply {
                setText(R.string.ittc_lp_templates_none)
                setTextColor(fg.adjustAlpha(0.7f))
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
        val myName = IttcDirect.me(this)?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
        list.forEach { t ->
            holder.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundResource(ripple())
                addView(TextView(context).apply {
                    this.text = t.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(fg)
                })
                addView(TextView(context).apply {
                    this.text = lead?.let { IttcLeadApi.fill(t.body, it, myName, institute) } ?: t.body
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(fg.adjustAlpha(0.7f))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                setOnClickListener {
                    dialog?.dismiss()
                    sendTemplate(t, channel, institute, myName)
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(holder) }
        getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .apply { setupDialogStuff(scroll, this, titleText = getString(R.string.ittc_lp_templates)) { dialog = it } }
    }

    /** The filled text, editable, then WhatsApp / the SMS app (the server is told it was sent) */
    private fun sendTemplate(t: IttcLeadApi.Template, channel: String, institute: JSONObject, myName: String?) {
        val l = lead ?: return
        val filled = IttcLeadApi.fill(t.body, l, myName, institute)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(TextView(this).apply {
            setText(R.string.ittc_lp_edit_before)
            setTextColor(getProperTextColor().adjustAlpha(0.7f))
        })
        val input = MyEditText(this).apply {
            setText(filled)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 10
        }
        holder.addView(input)
        getAlertDialogBuilder()
            .setPositiveButton(R.string.ittc_lp_send, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                setupDialogStuff(ScrollView(this@IttcLeadActivity).apply { addView(holder) }, this, titleText = t.name) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text?.toString()?.trim().orEmpty()
                        if (text.isEmpty()) return@setOnClickListener
                        val opened = if (channel == "sms") sendSms(text) else openWhatsApp(text)
                        if (opened) {
                            dialog.dismiss()
                            queue(
                                JSONObject().put("kind", "template").put("template_id", t.id).put("channel", channel)
                                    .put("edited", text != filled),
                                quiet = true,
                            ) { lo ->
                                prepend(
                                    lo, "calls",
                                    JSONObject().put("at", isoOf(System.currentTimeMillis())).put("outcome", "message")
                                        .put("notes", "Template: ${t.name}" + if (text != filled) " (edited)" else "").put("by", myName),
                                )
                            }
                        }
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // ✏️ Quick changes (queued; shown at once)
    // ------------------------------------------------------------------
    private fun myName() = IttcDirect.me(this)?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }

    /** Queue a change for this lead; [optimistic] shows it now (the lead is read again soon) */
    private fun queue(item: JSONObject, quiet: Boolean = false, optimistic: (JSONObject) -> Unit) {
        IttcDisclosure.showOnce(this) {
            item.put("lead_id", leadId)
            val app = applicationContext
            ensureBackgroundThread {
                val ok = try {
                    IttcDirect.enqueue(app, item)
                    true
                } catch (_: Throwable) {
                    false
                }
                runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    if (!ok) {
                        toast(R.string.ittc_lp_no_app)
                        return@runOnUiThread
                    }
                    queuedHere = true
                    lead?.let {
                        try {
                            optimistic(it)
                        } catch (_: Throwable) {
                        }
                        render()
                    }
                    if (!quiet) toast(R.string.ittc_lp_saved)
                    main.removeCallbacksAndMessages(null)
                    // (once the queue has sent it; again a little later for a slow line)
                    main.postDelayed({ if (alive()) load() }, 1_500)
                    main.postDelayed({ if (alive()) load() }, 6_000)
                }
            }
            IttcDirect.refreshAsync(app, force = true, delayMs = 3_000)
        }
    }

    private fun prepend(l: JSONObject, key: String, entry: JSONObject) {
        val old = l.optJSONArray(key) ?: JSONArray()
        val out = JSONArray().put(entry)
        for (i in 0 until old.length()) out.put(old.get(i))
        l.put(key, out)
    }

    private fun activityEntry(action: String, details: JSONObject) =
        JSONObject().put("at", isoOf(System.currentTimeMillis())).put("action", action).put("details", details).put("by", myName())

    private fun addNote() {
        noteDialog(getString(R.string.ittc_lp_add_note), required = true) { note ->
            queue(JSONObject().put("kind", "note").put("note", note)) { l ->
                prepend(l, "activity", activityEntry("note_added", JSONObject().put("note", note)))
            }
        }
    }

    private fun changeStage() {
        val l = lead ?: return
        val currentId = l.s("stage_id")
        val list = meta?.stages.orEmpty().filter { it.forCourse(l.s("course_id")) }
        if (list.isEmpty()) return toast(R.string.ittc_lp_no_stages)
        pickList(getString(R.string.ittc_lp_stage), list.map { it.name }, list.indexOfFirst { it.id == currentId }) { i ->
            val st = list[i]
            if (st.id == currentId) return@pickList
            // (the stage's own rules, as in the Tracker: a note, a date)
            val afterNote = { note: String? ->
                val save = { due: Long? ->
                    val item = JSONObject().put("kind", "stage").put("stage_id", st.id)
                    note?.let { item.put("note", it) }
                    due?.let { item.put("due_at", it) }
                    queue(item) { lo ->
                        prepend(lo, "activity", activityEntry("stage_changed", JSONObject().put("from", currentId).put("to", st.id)))
                        lo.put("stage", st.name)
                        lo.put("stage_id", st.id)
                        due?.let { lo.put("next_call", isoOf(it)) }
                    }
                }
                if (st.askDate) {
                    toast(getString(R.string.ittc_lp_date_for_stage, (st.dateLabel ?: "date & time").lowercase()))
                    pickDateTime(null) { save(it) }
                } else {
                    save(null)
                }
            }
            if (st.noteRequired) noteDialog(getString(R.string.ittc_lp_note_for_stage, st.name), required = true) { afterNote(it) }
            else afterNote(null)
        }
    }

    private fun tomorrow10(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextCall() {
        val options = listOf(getString(R.string.ittc_lp_tomorrow), getString(R.string.ittc_lp_in_2_days), getString(R.string.ittc_lp_pick))
        pickList(getString(R.string.ittc_lp_next_call), options) { i ->
            val save = { at: Long ->
                queue(JSONObject().put("kind", "next").put("due_at", at)) { l ->
                    l.put("next_call", isoOf(at))
                    prepend(l, "activity", activityEntry("follow_up_set", JSONObject().put("due_at", isoOf(at))))
                }
            }
            when (i) {
                0 -> save(tomorrow10())
                1 -> save(tomorrow10() + 24 * 3600_000L)
                else -> pickDateTime(IttcBridge.parseTimeOf(lead?.s("next_call"))) { save(it) }
            }
        }
    }

    private fun ratingLabel(value: String) = value.replaceFirstChar { it.uppercase() }

    private fun changeRating() {
        val values = listOf("hot", "warm", "cold", null)
        val labels = listOf(
            getString(R.string.ittc_lp_hot), getString(R.string.ittc_lp_warm), getString(R.string.ittc_lp_cold),
            getString(R.string.ittc_lp_no_rating),
        )
        val current = lead?.s("rating")?.lowercase()
        pickList(getString(R.string.ittc_lp_rating), labels, values.indexOf(current)) { i ->
            val v = values[i]
            if (v == current) return@pickList
            queue(JSONObject().put("kind", "edit").put("values", JSONObject().put("rating", v ?: JSONObject.NULL))) { l ->
                prepend(l, "activity", activityEntry("rating_changed", JSONObject().put("from", current).put("to", v)))
                if (v == null) l.remove("rating") else l.put("rating", v)
            }
        }
    }

    private fun editLead() {
        val l = lead ?: return
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: Int, value: String?, type: Int): MyEditText = MyEditText(this).apply {
            setHint(hint)
            setText(value.orEmpty())
            inputType = type
            holder.addView(this)
        }
        val name = field(R.string.ittc_lp_name, l.s("name"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val alt = field(R.string.ittc_lp_alt_phone, l.s("alt_phone"), InputType.TYPE_CLASS_PHONE)
        val wa = field(R.string.ittc_lp_whatsapp_number, l.s("whatsapp_number"), InputType.TYPE_CLASS_PHONE)
        getAlertDialogBuilder()
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                setupDialogStuff(holder, this, titleText = getString(R.string.ittc_lp_edit)) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val n = name.text?.toString()?.trim().orEmpty()
                        val a = alt.text?.toString()?.trim().orEmpty()
                        val w = wa.text?.toString()?.trim().orEmpty()
                        if (n.isEmpty()) return@setOnClickListener toast(R.string.ittc_lp_name_needed)
                        val altN = if (a.isEmpty()) "" else IttcDirect.normalizeBd(a) ?: return@setOnClickListener toast(R.string.ittc_lp_bad_number)
                        val waN = if (w.isEmpty()) "" else IttcDirect.normalizeBd(w)
                            ?: w.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 8 }
                            ?: return@setOnClickListener toast(R.string.ittc_lp_bad_number)
                        val values = JSONObject()
                        if (n != l.s("name")) values.put("name", n)
                        if (altN != l.s("alt_phone").orEmpty()) values.put("alt_phone", altN.ifEmpty { null } ?: JSONObject.NULL)
                        if (waN != l.s("whatsapp_number").orEmpty()) values.put("whatsapp_number", waN.ifEmpty { null } ?: JSONObject.NULL)
                        dialog.dismiss()
                        if (values.length() == 0) return@setOnClickListener
                        queue(JSONObject().put("kind", "edit").put("values", values)) { lo ->
                            values.keys().forEach { k -> if (values.isNull(k)) lo.remove(k) else lo.put(k, values.get(k)) }
                        }
                    }
                }
            }
    }

    private fun handOver() {
        val l = lead ?: return
        val people = meta?.people.orEmpty().filter { it.id != l.s("assigned_to") }.sortedBy { it.name.lowercase() }
        if (people.isEmpty()) return toast(R.string.ittc_lp_no_people)
        pickList(getString(R.string.ittc_lp_hand_over), people.map { p -> if (p.role.isBlank()) p.name else "${p.name}  ·  ${p.role.replace('_', ' ')}" }) { i ->
            val p = people[i]
            noteDialog(getString(R.string.ittc_lp_hand_over_to, p.name), required = false) { note ->
                val item = JSONObject().put("kind", "handover").put("to", p.id)
                if (note.isNotEmpty()) item.put("note", note)
                queue(item) { lo ->
                    lo.put("assigned_to", p.id)
                    lo.put("owner", p.name)
                    prepend(lo, "activity", activityEntry("assigned", JSONObject().put("to", p.id)))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 🧩 Small dialogs and views
    // ------------------------------------------------------------------
    /** A note: free text and the admin's presets (a tap adds one) */
    private fun noteDialog(title: String, required: Boolean, done: (String) -> Unit) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = MyEditText(this).apply {
            setHint(if (required) R.string.ittc_lp_note_hint else R.string.ittc_lp_note_optional)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
        }
        holder.addView(input)
        val presets = (meta ?: IttcBridge.metaCache)?.notePresets.orEmpty()
        val m = dp(4)
        presets.chunked(2).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { preset ->
                line.addView(
                    chip(preset, false) {
                        val now = input.text?.toString()?.trim().orEmpty()
                        val next = if (now.isEmpty()) preset else "$now. $preset"
                        input.setText(next)
                        input.setSelection(next.length)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m * 2, m, 0) },
                )
            }
            if (row.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            holder.addView(line)
        }
        getAlertDialogBuilder()
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                setupDialogStuff(ScrollView(this@IttcLeadActivity).apply { addView(holder) }, this, titleText = title) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val note = input.text?.toString()?.trim().orEmpty()
                        if (required && note.isEmpty()) return@setOnClickListener toast(R.string.ittc_lp_note_empty)
                        dialog.dismiss()
                        done(note)
                    }
                }
            }
    }

    /** A list to pick one from (the [checked] one marked) */
    private fun pickList(title: String, items: List<String>, checked: Int = -1, onPick: (Int) -> Unit) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val fg = getProperTextColor()
        val primary = getProperPrimaryColor()
        var dialog: AlertDialog? = null
        items.forEachIndexed { i, label ->
            holder.addView(TextView(this).apply {
                this.text = if (i == checked) "✓  $label" else label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(if (i == checked) primary else fg)
                setPadding(dp(24), dp(14), dp(24), dp(14))
                setBackgroundResource(ripple())
                setOnClickListener {
                    dialog?.dismiss()
                    onPick(i)
                }
            })
        }
        getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .apply { setupDialogStuff(ScrollView(this@IttcLeadActivity).apply { addView(holder) }, this, titleText = title) { dialog = it } }
    }

    /** A date, then a time (only later than now) */
    private fun pickDateTime(initial: Long?, done: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { initial?.let { timeInMillis = it } }
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                val at = Calendar.getInstance().apply {
                    set(y, mo, d, h, mi, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > System.currentTimeMillis()) done(at)
            }, if (initial != null) c.get(Calendar.HOUR_OF_DAY) else 10, if (initial != null) c.get(Calendar.MINUTE) else 0, false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val pad = dp(12)
        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
        gravity = Gravity.CENTER
        if (on) IttcTheme.pill(this, p.primary, p.onPrimary) else IttcTheme.pill(this, p.surfaceAlt, p.text)
        maxLines = 2
        touchFeedback(this)
        setOnClickListener { onClick() }
    }

    /** The system's touch ripple over a view with its own background */
    private fun touchFeedback(view: View) {
        try {
            view.foreground = androidx.core.content.ContextCompat.getDrawable(this, ripple())
        } catch (_: Throwable) {
        }
    }

    private fun ripple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun whenText(ms: Long): String =
        DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)

    private fun duration(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600}h ${sec % 3600 / 60}m"
        sec >= 60 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec}s"
    }

    private fun isoOf(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    private fun JSONObject.s(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val LEAD_ID = "ittc_lead_id"
        private const val SHOW_TEMPLATES = "ittc_show_templates"
        private const val WHATSAPP_GREEN = 0xFF25D366.toInt()

        private const val STEP_DONE = 0
        private const val STEP_CURRENT = 1
        private const val STEP_TODO = 2

        private const val K_CALL_OK = 0
        private const val K_CALL_MISSED = 1
        private const val K_MESSAGE = 2
        private const val K_STAGE = 3
        private const val K_NOTE = 4
        private const val K_NEXT = 5
        private const val K_PEOPLE = 6
        private const val K_RATING = 7
        private const val K_VISIT = 8
        private const val K_MONEY = 9
        private const val K_ADDED = 10
        private const val K_OTHER = 11

        private val OUTCOMES = mapOf(
            "connected" to "Connected", "no_answer" to "No answer", "busy" to "Busy",
            "switched_off" to "Switched off", "wrong_number" to "Wrong number", "call_back" to "Call back later",
            "message" to "Message",
        )

        /** The lead page (direct mode); [showTemplates]: the templates open at once */
        fun open(context: Context, leadId: String, showTemplates: Boolean = false) {
            if (leadId.isBlank()) return
            try {
                context.startActivity(
                    Intent(context, IttcLeadActivity::class.java)
                        .putExtra(LEAD_ID, leadId)
                        .putExtra(SHOW_TEMPLATES, showTemplates)
                        .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            } catch (_: Throwable) {
            }
        }
    }
}
