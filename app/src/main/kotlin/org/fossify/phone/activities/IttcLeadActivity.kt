package org.fossify.phone.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
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
import org.fossify.phone.helpers.IttcDisclosure
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 🎓 ITTC direct mode: one lead (opened from the Leads tab, a number, a reminder…): who it is, its
 * numbers, call / WhatsApp / SMS / templates, quick changes (note, stage, next call, rating, edit,
 * hand over) and its timeline. Changes go through IttcDirect's queue (sent when online) and show
 * at once; the lead is read again a moment later.
 */
class IttcLeadActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityLeadBinding::inflate)
    private val main = Handler(Looper.getMainLooper())

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leadId = intent.getStringExtra(LEAD_ID).orEmpty()
        if (leadId.isBlank()) {
            finish()
            return
        }
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcLeadScrollview))
            setupMaterialScrollListener(ittcLeadScrollview, ittcLeadAppbar)
            ittcLeadCall.setOnClickListener { callLead() }
            ittcLeadWhatsapp.setOnClickListener { openWhatsApp(null) }
            ittcLeadSms.setOnClickListener { openSms() }
            ittcLeadTemplates.setOnClickListener { showTemplates() }
            ittcLeadNotes.setOnClickListener {
                notesOpen = !notesOpen
                ittcLeadNotes.maxLines = if (notesOpen) Int.MAX_VALUE else 6
            }
        }
        styleActions()
        updateTextColors(binding.ittcLeadHolder)
        templatesWanted = savedInstanceState == null && intent.getBooleanExtra(SHOW_TEMPLATES, false)
        render()
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcLeadAppbar, NavigationIcon.Arrow)
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

    // ------------------------------------------------------------------
    // 🖼 Showing it
    // ------------------------------------------------------------------
    private fun styleActions() {
        val primary = getProperPrimaryColor()
        listOf(
            binding.ittcLeadCall to R.drawable.ic_phone_vector,
            binding.ittcLeadWhatsapp to R.drawable.ic_sms_vector,
            binding.ittcLeadSms to R.drawable.ic_sms_vector,
            binding.ittcLeadTemplates to R.drawable.ic_edit_vector,
        ).forEach { (view, icon) ->
            val color = if (view == binding.ittcLeadWhatsapp) WHATSAPP_GREEN else primary
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, resources.getColoredDrawableWithColor(icon, color), null, null)
        }
    }

    private fun render() {
        val l = lead
        val has = l != null
        binding.apply {
            ittcLeadHeader.beVisibleIf(has)
            ittcLeadActions.beVisibleIf(has)
            ittcLeadQuick.beVisibleIf(has && signedIn)
            ittcLeadTemplates.beVisibleIf(signedIn)
        }
        if (l == null) {
            binding.ittcLeadNotesLabel.beGone()
            binding.ittcLeadNotes.beGone()
            binding.ittcLeadTimelineLabel.beGone()
            binding.ittcLeadTimeline.removeAllViews()
            return
        }
        val text = getProperTextColor()
        val primary = getProperPrimaryColor()
        val stage = l.s("stage")
        val stageObj = meta?.stages?.firstOrNull { it.id == l.s("stage_id") }

        binding.ittcLeadName.text = l.s("name") ?: l.s("phone") ?: ""
        val info = listOfNotNull(l.s("course"), l.s("batch"), stage).joinToString(" · ")
        binding.ittcLeadInfo.text = info
        binding.ittcLeadInfo.beVisibleIf(info.isNotEmpty())
        binding.ittcLeadInfo.setTextColor(stageObj?.color ?: IttcBridge.stageColor(stage, primary, text.adjustAlpha(0.6f)))

        val next = IttcBridge.parseTimeOf(l.s("next_call"))
        val attempts = l.optInt("call_attempts", 0)
        val line = listOfNotNull(
            l.s("rating")?.let { "★ ${ratingLabel(it)}" },
            next?.let { getString(R.string.ittc_lp_next, whenText(it)) },
            attempts.takeIf { it > 0 }?.let { getString(R.string.ittc_lp_attempts, it) },
            l.s("owner")?.let { getString(R.string.ittc_lp_owner, it) },
        ).joinToString(" · ")
        binding.ittcLeadMeta.text = line
        binding.ittcLeadMeta.beVisibleIf(line.isNotEmpty())

        renderNumbers(l)
        renderQuick()

        val notes = l.s("notes") ?: l.s("last_note")
        binding.ittcLeadNotesLabel.beVisibleIf(notes != null)
        binding.ittcLeadNotes.beVisibleIf(notes != null)
        binding.ittcLeadNotes.text = notes

        renderTimeline(l)
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

    private fun renderNumbers(l: JSONObject) {
        val list = binding.ittcLeadNumbers
        list.removeAllViews()
        val fg = getProperTextColor()
        val icon = resources.getColoredDrawableWithColor(R.drawable.ic_phone_vector, getProperPrimaryColor())
        val pad = dp(8)
        numbers(l).forEach { (number, label) ->
            list.addView(TextView(this).apply {
                this.text = if (label.isEmpty()) number else "$number  ·  $label"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(fg)
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = dp(12)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
                setBackgroundResource(ripple())
                setOnClickListener { call(number) }
            })
        }
    }

    /** Only super admins: my_phone_data lists the people (whom to hand over to) only for them */
    private fun canHandOver(): Boolean = meta?.isSuper == true

    private fun renderQuick() {
        val box = binding.ittcLeadQuick
        box.removeAllViews()
        val items = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.ittc_lp_add_note to { addNote() },
            R.string.ittc_lp_stage to { changeStage() },
            R.string.ittc_lp_next_call to { nextCall() },
            R.string.ittc_lp_rating to { changeRating() },
            R.string.ittc_lp_edit to { editLead() },
        )
        if (canHandOver()) items += R.string.ittc_lp_hand_over to { handOver() }
        items += R.string.ittc_lp_web to { openWeb() }
        val m = dp(4)
        items.chunked(3).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (label, action) ->
                line.addView(
                    chip(getString(label), false) { action() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m * 2, m, 0) },
                )
            }
            repeat(3 - row.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, m * 2, m, 0) })
            }
            box.addView(line)
        }
    }

    private class Item(val at: Long, val head: String, val text: String, val note: String?)

    private fun renderTimeline(l: JSONObject) {
        val list = binding.ittcLeadTimeline
        list.removeAllViews()
        binding.ittcLeadTimelineLabel.beVisibleIf(fromServer)
        if (!fromServer) return
        val items = ArrayList<Item>()
        l.optJSONArray("calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val at = IttcBridge.parseTimeOf(c.s("at")) ?: continue
                val outcome = c.s("outcome")
                val dur = c.optInt("duration_sec", 0)
                val what = if (outcome == "message") "💬 Message" else "📞 ${outcome?.let { OUTCOMES[it] ?: it.replace('_', ' ') } ?: "Call"}"
                val text = listOfNotNull(what, dur.takeIf { it > 0 }?.let { duration(it) }).joinToString(" · ")
                items += Item(at, head(at, c.s("by")), text, c.s("notes"))
            }
        }
        l.optJSONArray("activity")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val at = IttcBridge.parseTimeOf(a.s("at")) ?: continue
                items += Item(at, head(at, a.s("by")), describe(a.s("action").orEmpty(), a.optJSONObject("details")), null)
            }
        }
        items.sortByDescending { it.at }
        val fg = getProperTextColor()
        val faint = fg.adjustAlpha(0.6f)
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.ittc_lp_timeline_none)
                setTextColor(faint)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                this.text = item.head
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(faint)
            })
            row.addView(TextView(this).apply {
                this.text = item.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(fg)
            })
            item.note?.let { note ->
                row.addView(TextView(this).apply {
                    this.text = "“$note”"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(fg.adjustAlpha(0.85f))
                })
            }
            list.addView(row)
        }
    }

    private fun head(at: Long, by: String?) = listOfNotNull(whenText(at), by).joinToString(" · ")

    /** One change in words (the Tracker's common ones; the rest by their name) */
    private fun describe(action: String, d: JSONObject?): String {
        fun stageName(id: String?) = meta?.stages?.firstOrNull { it.id == id }?.name ?: "—"
        fun person(id: String?) = meta?.people?.firstOrNull { it.id == id }?.name ?: "someone"
        return when (action) {
            "stage_changed" -> "🚩 ${stageName(d?.s("from"))} → ${stageName(d?.s("to"))}"
            "note_added" -> "📝 “${d?.s("note") ?: "—"}”"
            "note_edited" -> "📝 Notes edited"
            "follow_up_set" -> "⏰ Next call " + (IttcBridge.parseTimeOf(d?.s("due_at"))?.let { whenText(it) } ?: "")
            "follow_up_cancelled" -> "⏰ Next call removed"
            "assigned" -> d?.s("to")?.let { "👥 → ${person(it)}" } ?: "👥 Unassigned"
            "rating_changed" -> "⭐ ${d?.s("from") ?: "—"} → ${d?.s("to") ?: "—"}"
            "created" -> "➕ Lead added"
            "re_enquiry" -> "➕ Asked again"
            "details_changed", "fields_changed" -> "✏️ Details changed"
            "course_added" -> "🎓 Course + ${d?.s("course") ?: "—"}"
            "course_removed" -> "🎓 Course − ${d?.s("course") ?: "—"}"
            "tag_added" -> "🏷 + ${d?.s("tag") ?: "—"}"
            "tag_removed" -> "🏷 − ${d?.s("tag") ?: "—"}"
            "visit_planned" -> "🏫 Visit planned " + (IttcBridge.parseTimeOf(d?.s("at"))?.let { whenText(it) } ?: "")
            "visit_done" -> "🏫 Visited the campus"
            "payment_received" -> "💰 Payment received"
            else -> "• " + action.replace('_', ' ').replaceFirstChar { it.uppercase() }
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
        val pad = dp(10)
        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.ittc_chip_background)
        val primary = getProperPrimaryColor()
        val textColor = getProperTextColor()
        background.mutate().applyColorFilter(if (on) primary else (textColor and 0x00FFFFFF) or 0x22000000)
        setTextColor(if (on) primary.getContrastColor() else textColor)
        maxLines = 2
        setOnClickListener { onClick() }
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
