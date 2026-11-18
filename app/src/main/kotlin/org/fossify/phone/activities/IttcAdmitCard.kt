package org.fossify.phone.activities

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcAdmitApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcTheme
import java.util.WeakHashMap

/**
 * 🎓 3.5: the lead page's admission card — where the admission is (not admitted / admitted /
 * submitted online / received by ITTC / returned, with the officer's reason), the fee paid and due
 * with the last payment, and the papers in (x/y), each with small buttons that open the Admit
 * screen, the Payments sheet and the Papers sheet. Loads off the main thread; calling again reloads.
 */
object IttcAdmitCard {
    /** The newest load per container (an older answer that comes late is dropped) */
    private val generation = WeakHashMap<LinearLayout, Int>()

    private class Data(
        val lead: IttcAdmitApi.LeadState,
        val settings: IttcAdmitApi.Settings,
        val may: IttcAdmitApi.May,
        val course: String?,
        val pays: IttcAdmitApi.Payments?,
        val returned: Pair<String, String>?,
    )

    fun bind(activity: Activity, container: LinearLayout, leadId: String) {
        try {
            if (!IttcDirect.isActive(activity) || leadId.isBlank()) {
                container.removeAllViews()
                container.visibility = View.GONE
                return
            }
            val gen = (generation[container] ?: 0) + 1
            generation[container] = gen
            container.visibility = View.VISIBLE
            if (container.childCount == 0) container.addView(IttcDUi.loadingCard(activity))
            val app = activity.applicationContext
            ensureBackgroundThread {
                val result: Result<Data?> = try {
                    Result.success(load(app, leadId))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                activity.runOnUiThread {
                    try {
                        if (activity.isFinishing || activity.isDestroyed || generation[container] != gen) return@runOnUiThread
                        container.removeAllViews()
                        val data = result.getOrNull()
                        when {
                            result.isFailure -> container.addView(errorCard(activity, container, leadId, result.exceptionOrNull()))
                            data == null -> container.visibility = View.GONE
                            else -> container.addView(card(activity, container, data))
                        }
                    } catch (_: Throwable) {
                        // (never take the lead page down)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun load(context: Context, leadId: String): Data? {
        val lead = IttcAdmitApi.lead(context, leadId) ?: return null
        val settings = IttcAdmitApi.settings(context)
        val may = IttcAdmitApi.may(context)
        val course = if (lead.admitted) IttcAdmitApi.nameOf(context, "courses", lead.admittedCourseId) else null
        val pays = if (lead.admitted) {
            try {
                IttcAdmitApi.payments(context, leadId)
            } catch (_: Throwable) {
                null
            }
        } else null
        val returned = if (lead.admitted && lead.receivedAt == null) {
            IttcAdmitApi.lastReturn(context, leadId, lead.submittedAt ?: lead.admittedAt)
                ?.takeIf { (step, _) -> lead.submittedAt == null || step == "received" }
        } else null
        return Data(lead, settings, may, course, pays, returned)
    }

    private fun reload(activity: Activity, container: LinearLayout, leadId: String) = { bind(activity, container, leadId) }

    private fun errorCard(activity: Activity, container: LinearLayout, leadId: String, e: Throwable?): View {
        val p = IttcTheme.of(activity)
        val card = IttcDUi.card(activity)
        card.addView(IttcDUi.text(activity, activity.getString(R.string.ittc_d_card_title), 15f, p.text, bold = true))
        card.addView(IttcDUi.hint(activity, IttcAdmitApi.errorText(e ?: Exception(), activity.getString(R.string.ittc_d_could_not_load))))
        val row = IttcDUi.row(activity)
        row.addView(IttcDUi.button(activity, activity.getString(R.string.ittc_d_retry), IttcDUi.Btn.SOFT, small = true) { bind(activity, container, leadId) })
        card.addView(row, IttcDUi.lp(activity, top = 8f))
        return card
    }

    private fun card(activity: Activity, container: LinearLayout, d: Data): View {
        val p = IttcTheme.of(activity)
        val lead = d.lead
        val done = reload(activity, container, lead.id)
        val card = IttcDUi.card(activity)

        // ---- 🎓 admission / approval
        val head = IttcDUi.row(activity)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(
            IttcDUi.text(activity, activity.getString(R.string.ittc_d_card_title), 15f, p.text, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val (statusText, tone) = status(activity, d)
        head.addView(IttcDUi.pill(activity, statusText, tone))
        card.addView(head)

        when {
            lead.admitted -> {
                val line = listOfNotNull(lead.admittedAt?.let { IttcAdmitApi.day(it) }, d.course).joinToString(" · ")
                if (line.isNotEmpty()) card.addView(IttcDUi.hint(activity, line), IttcDUi.lp(activity, top = 4f))
                lead.studentRef?.let { card.addView(IttcDUi.text(activity, activity.getString(R.string.ittc_d_student_id, it), 13f, p.text2), IttcDUi.lp(activity, top = 2f)) }
                card.addView(steps(activity, d), IttcDUi.lp(activity, top = 8f))
                d.returned?.let { (step, reason) ->
                    val what = activity.getString(if (step == "received") R.string.ittc_d_step_received else R.string.ittc_d_step_submitted)
                    val t = IttcDUi.text(activity, activity.getString(R.string.ittc_d_returned_reason, what, reason.ifBlank { "—" }), 13f, p.danger)
                    t.background = IttcTheme.rounded(activity, p.dangerSoft, 10f)
                    val pad = IttcTheme.dp(activity, 8f)
                    t.setPadding(pad, pad, pad, pad)
                    card.addView(t, IttcDUi.lp(activity, top = 8f))
                }
            }

            lead.isOpen && d.may.admit -> {
                val row = IttcDUi.row(activity)
                row.addView(IttcDUi.button(activity, activity.getString(R.string.ittc_d_admit_btn), IttcDUi.Btn.PRIMARY, small = true) {
                    IttcAdmitActivity.open(activity, lead.id)
                })
                card.addView(row, IttcDUi.lp(activity, top = 8f))
            }
        }

        // ---- 💵 fee and payments
        card.addView(IttcDUi.divider(activity), IttcDUi.lp(activity, top = 12f))
        val fee = IttcDUi.row(activity)
        fee.gravity = Gravity.CENTER_VERTICAL
        val feeText = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val payLabel: String
        if (lead.admitted) {
            val final = lead.feeFinal ?: 0.0
            val paid = d.pays?.paid ?: lead.feePaid ?: 0.0
            val due = maxOf(final - paid, 0.0)
            feeText.addView(
                IttcDUi.text(
                    activity,
                    if (final <= 0 && paid <= 0) activity.getString(R.string.ittc_d_no_fee)
                    else if (due <= 0) activity.getString(R.string.ittc_d_paid_full, IttcAdmitApi.money(paid))
                    else activity.getString(R.string.ittc_d_paid_due, IttcAdmitApi.money(paid), IttcAdmitApi.money(due)),
                    14f, if (due > 0 && late(d)) p.danger else p.text, bold = true,
                ),
            )
            d.pays?.payments?.firstOrNull { !it.voided && !it.refund }?.let { last ->
                feeText.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_last_payment, IttcAdmitApi.money(last.amount), IttcAdmitApi.day(last.paidAt), last.receiptNo)))
            }
            d.pays?.states()?.firstOrNull { it.state != "paid" }?.let { next ->
                val left = IttcAdmitApi.money(next.item.amount - next.covered)
                val text = activity.getString(
                    if (next.state == "late") R.string.ittc_d_next_late else R.string.ittc_d_next_due,
                    next.item.seq, left, IttcAdmitApi.dayOf(next.item.dueDate),
                )
                feeText.addView(IttcDUi.text(activity, text, 12f, if (next.state == "late") p.danger else p.muted))
            }
            payLabel = activity.getString(if (d.may.pay && due > 0 && d.settings.payOn) R.string.ittc_d_take_payment else R.string.ittc_d_payments)
        } else {
            val final = lead.feeFinal
            feeText.addView(
                IttcDUi.text(
                    activity,
                    if (final != null && final > 0) activity.getString(R.string.ittc_d_fee_is, IttcAdmitApi.money(final))
                    else activity.getString(R.string.ittc_d_fee_unknown),
                    14f, p.text, bold = true,
                ),
            )
            feeText.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_pay_after_admit)))
            payLabel = activity.getString(R.string.ittc_d_payments)
        }
        fee.addView(feeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (lead.admitted || (lead.feeFinal ?: 0.0) > 0) {
            fee.addView(IttcDUi.button(activity, payLabel, IttcDUi.Btn.SOFT, small = true) {
                IttcPaymentSheet.show(activity, lead.id) { done() }
            })
        }
        card.addView(fee, IttcDUi.lp(activity, top = 10f))

        // ---- 📄 papers
        card.addView(IttcDUi.divider(activity), IttcDUi.lp(activity, top = 12f))
        val papers = IttcDUi.row(activity)
        papers.gravity = Gravity.CENTER_VERTICAL
        val req = lead.papersRequired
        val got = minOf(lead.papersReceived, req)
        val paperText = if (req > 0) activity.getString(R.string.ittc_d_papers_count, got, req) else activity.getString(R.string.ittc_d_papers_none)
        papers.addView(
            IttcDUi.text(activity, paperText, 14f, if (req > 0 && got < req) p.text else p.muted, bold = req > 0),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (req > 0 && got >= req) papers.addView(IttcDUi.pill(activity, activity.getString(R.string.ittc_d_all_in), IttcDUi.Tone.GOOD))
        papers.addView(
            IttcDUi.button(activity, activity.getString(if (d.may.docReady) R.string.ittc_d_ask_papers else R.string.ittc_d_papers), IttcDUi.Btn.SOFT, small = true) {
                IttcPapersSheet.show(activity, lead.id, lead.name, lead.whatsapp ?: lead.phone) { done() }
            },
            IttcDUi.lp(activity, start = 8f, width = ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(papers, IttcDUi.lp(activity, top = 10f))
        return card
    }

    private fun late(d: Data): Boolean {
        val next = d.lead.feeNextDue ?: return false
        return next < java.time.LocalDate.now().toString()
    }

    /** The pill: Not admitted / Admitted / Submitted online / Ready for ITTC / Received by ITTC / Returned … */
    private fun status(context: Context, d: Data): Pair<String, IttcDUi.Tone> {
        val l = d.lead
        return when {
            l.kind == "cancelled" || l.cancelledAt != null -> context.getString(R.string.ittc_d_st_cancelled) to IttcDUi.Tone.MUTED
            l.kind == "lost" -> context.getString(R.string.ittc_d_st_closed) to IttcDUi.Tone.MUTED
            !l.admitted -> context.getString(R.string.ittc_d_st_not_admitted) to IttcDUi.Tone.INFO
            l.receivedAt != null -> context.getString(R.string.ittc_d_st_received) to IttcDUi.Tone.GOOD
            d.returned != null -> context.getString(R.string.ittc_d_st_returned) to IttcDUi.Tone.BAD
            l.submittedAt != null && feeOk(d) -> context.getString(R.string.ittc_d_st_ready) to IttcDUi.Tone.WAIT
            l.submittedAt != null -> context.getString(R.string.ittc_d_st_submitted) to IttcDUi.Tone.WAIT
            else -> context.getString(R.string.ittc_d_st_admitted) to IttcDUi.Tone.GOOD
        }
    }

    private fun need(d: Data) = d.lead.admissionFeeNeed ?: d.settings.admissionFeeDefault
    private fun paid(d: Data) = d.pays?.paid ?: d.lead.feePaid ?: 0.0
    private fun feeOk(d: Data) = paid(d) >= need(d)

    /** 1 📤 Submitted Online → 2 🏛 Received by ITTC (as the Tracker's approval card) */
    private fun steps(activity: Activity, d: Data): View {
        val p = IttcTheme.of(activity)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val l = d.lead
        fun step(title: String, done: Boolean, right: String, ready: Boolean) {
            val row = IttcDUi.row(activity)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(IttcDUi.text(activity, (if (done) "✓ " else "○ ") + title, 13f, if (done) p.call else p.text2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(IttcDUi.text(activity, right, 12f, if (done) p.call else if (ready) p.primary else p.muted, bold = ready))
            box.addView(row, IttcDUi.lp(activity, top = 2f))
        }
        step(
            activity.getString(R.string.ittc_d_step_submitted), l.submittedAt != null,
            l.submittedAt?.let { IttcAdmitApi.day(it) } ?: activity.getString(R.string.ittc_d_not_yet), false,
        )
        val ok = feeOk(d)
        step(
            activity.getString(R.string.ittc_d_step_received), l.receivedAt != null,
            l.receivedAt?.let { IttcAdmitApi.day(it) } ?: when {
                l.submittedAt == null -> activity.getString(R.string.ittc_d_after_submitted)
                ok -> activity.getString(R.string.ittc_d_ready)
                else -> activity.getString(R.string.ittc_d_more_needed, IttcAdmitApi.money(need(d) - paid(d)))
            },
            l.submittedAt != null && ok && l.receivedAt == null,
        )
        return box
    }
}

/** 🎨 Small view builders for Part D's screens and sheets (IttcTheme colours, 16 dp cards, pills, 44 dp targets) */
internal object IttcDUi {
    enum class Btn { PRIMARY, CALL, SOFT, GHOST, DANGER }
    enum class Tone { GOOD, WAIT, BAD, INFO, MUTED }

    fun lp(context: Context, top: Float = 0f, start: Float = 0f, width: Int = ViewGroup.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = IttcTheme.dp(context, top)
            marginStart = IttcTheme.dp(context, start)
        }

    fun text(context: Context, s: CharSequence, sp: Float = 14f, color: Int = IttcTheme.of(context).text, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun title(context: Context, s: CharSequence) = text(context, s, 19f, IttcTheme.of(context).text, bold = true)
    fun label(context: Context, s: CharSequence) = text(context, s, 13f, IttcTheme.of(context).text2, bold = true)
    fun hint(context: Context, s: CharSequence) = text(context, s, 12f, IttcTheme.of(context).muted)

    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(IttcTheme.of(context).divider)
        minimumHeight = IttcTheme.dp(context, 1f)
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        IttcTheme.card(this)
        val pad = IttcTheme.dp(context, 16f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = IttcTheme.dp(context, 12f)
        }
    }

    /** A soft box inside a card / sheet */
    fun box(context: Context, fill: Int = IttcTheme.of(context).surfaceAlt, stroke: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = IttcTheme.rounded(context, fill, 12f, stroke)
        val pad = IttcTheme.dp(context, 12f)
        setPadding(pad, pad, pad, pad)
    }

    fun loadingCard(context: Context): View = card(context).apply {
        gravity = Gravity.CENTER
        addView(progress(context))
    }

    fun progress(context: Context) = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = IttcTheme.tint(IttcTheme.of(context).primary)
        layoutParams = LinearLayout.LayoutParams(IttcTheme.dp(context, 28f), IttcTheme.dp(context, 28f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    fun pill(context: Context, s: String, tone: Tone): TextView {
        val p = IttcTheme.of(context)
        val (bg, fg) = when (tone) {
            Tone.GOOD -> IttcTheme.stageColors(context, "admitted")
            Tone.WAIT -> IttcTheme.stageColors(context, "interested")
            Tone.BAD -> p.dangerSoft to p.danger
            Tone.INFO -> IttcTheme.stageColors(context, "new")
            Tone.MUTED -> IttcTheme.stageColors(context, "lost")
        }
        return text(context, s, 12f, fg, bold = true).apply {
            IttcTheme.pill(this, bg, fg)
            val h = IttcTheme.dp(context, 10f)
            val v = IttcTheme.dp(context, 4f)
            setPadding(h, v, h, v)
        }
    }

    fun button(context: Context, s: String, style: Btn, small: Boolean = false, onClick: () -> Unit): TextView {
        val p = IttcTheme.of(context)
        return text(context, s, if (small) 13f else 15f, p.text, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = IttcTheme.dp(context, 44f)
            val h = IttcTheme.dp(context, if (small) 14f else 18f)
            setPadding(h, 0, h, 0)
            when (style) {
                Btn.PRIMARY -> IttcTheme.button(this, p.primary, p.onPrimary)
                Btn.CALL -> IttcTheme.button(this, p.call, p.onPrimary)
                Btn.SOFT -> IttcTheme.button(this, p.primarySoft, p.primary)
                Btn.DANGER -> IttcTheme.button(this, p.dangerSoft, p.danger)
                Btn.GHOST -> {
                    background = null
                    setTextColor(p.muted)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    onClick()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** A choice chip (selected: filled; disabled: faded) */
    fun chip(context: Context, s: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit): TextView {
        val p = IttcTheme.of(context)
        return text(context, s, 13f, p.text, bold = on).apply {
            gravity = Gravity.CENTER
            minHeight = IttcTheme.dp(context, 40f)
            val h = IttcTheme.dp(context, 14f)
            setPadding(h, 0, h, 0)
            if (on) IttcTheme.pill(this, p.primary, p.onPrimary) else IttcTheme.pill(this, p.surface, p.text2, p.outline)
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            setOnClickListener { if (enabled) onClick() }
        }
    }

    /** A full-width choice row (a batch, a section) */
    fun option(context: Context, s: String, sub: String?, on: Boolean, enabled: Boolean, warn: Boolean, onClick: () -> Unit): View {
        val p = IttcTheme.of(context)
        val b = box(context, if (on) p.primarySoft else p.surface, if (on) p.primary else p.outline)
        b.minimumHeight = IttcTheme.dp(context, 44f)
        b.addView(text(context, (if (on) "● " else "○ ") + s, 14f, if (warn) p.danger else p.text, bold = on))
        if (!sub.isNullOrBlank()) b.addView(hint(context, sub))
        b.alpha = if (enabled) 1f else 0.45f
        b.isClickable = enabled
        b.setOnClickListener { if (enabled) onClick() }
        b.layoutParams = lp(context, top = 6f)
        return b
    }

    fun input(context: Context, hint: String, numeric: Boolean = false, multiline: Boolean = false, caps: Boolean = false): EditText {
        val p = IttcTheme.of(context)
        return EditText(context).apply {
            this.hint = hint
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = IttcTheme.rounded(context, p.surface, 12f, p.outline)
            val h = IttcTheme.dp(context, 12f)
            setPadding(h, h, h, h)
            minHeight = IttcTheme.dp(context, 48f)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                caps -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (multiline) {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
            } else {
                maxLines = 1
                isSingleLine = true
            }
            if (numeric) ittcAcceptBengaliDigits()
            layoutParams = lp(context, top = 6f)
        }
    }

    fun errorBox(context: Context): TextView {
        val p = IttcTheme.of(context)
        return text(context, "", 13f, p.danger).apply {
            background = IttcTheme.rounded(context, p.dangerSoft, 12f)
            val pad = IttcTheme.dp(context, 12f)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            layoutParams = lp(context, top = 12f)
        }
    }

    fun showError(view: TextView, message: String?) {
        view.text = message.orEmpty()
        view.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Paid / due bar (0..1) */
    fun bar(context: Context, part: Double): View {
        val p = IttcTheme.of(context)
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = IttcTheme.rounded(context, p.surfaceAlt, 999f)
        }
        val f = part.coerceIn(0.0, 1.0).toFloat()
        val h = IttcTheme.dp(context, 8f)
        track.addView(View(context).apply { background = IttcTheme.rounded(context, p.call, 999f) }, LinearLayout.LayoutParams(0, h, f))
        track.addView(View(context), LinearLayout.LayoutParams(0, h, 1f - f))
        track.layoutParams = lp(context, top = 8f)
        return track
    }

    /**
     * A bottom sheet: a scrolling column on the card colour with rounded top corners, opened full height.
     * build() fills the column.
     */
    fun sheet(activity: Activity, build: (column: LinearLayout, dialog: BottomSheetDialog) -> Unit): BottomSheetDialog {
        val p = IttcTheme.of(activity)
        val dialog = BottomSheetDialog(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IttcTheme.dp(activity, 20f)
            setPadding(pad, IttcTheme.dp(activity, 10f), pad, pad)
        }
        // the handle
        column.addView(
            View(activity).apply { background = IttcTheme.rounded(activity, p.outline, 999f) },
            LinearLayout.LayoutParams(IttcTheme.dp(activity, 40f), IttcTheme.dp(activity, 4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = IttcTheme.dp(activity, 12f)
            },
        )
        val scroll = NestedScrollView(activity).apply {
            val r = IttcTheme.dp(activity, 20f).toFloat()
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(scroll)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            try {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                dialog.behavior.skipCollapsed = true
                dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            } catch (_: Throwable) {
            }
        }
        build(column, dialog)
        return dialog
    }

    /** Is this activity still there to show something? */
    fun alive(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
}

/** A simple wrapping row (chips): children left to right, onto the next line when full */
internal class IttcDFlow(context: Context) : ViewGroup(context) {
    private val gap = IttcTheme.dp(context, 8f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        val childSpec = MeasureSpec.makeMeasureSpec(maxOf(maxW, 0), MeasureSpec.AT_MOST)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += lineH + gap
                lineH = 0
            }
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        val h = y + lineH + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += lineH + gap
                lineH = 0
            }
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) r - l - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(left, paddingTop + y, left + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

/** A number field that also takes Bengali digits (০-৯; read them with IttcAdmitApi.asciiDigits), on the number keyboard */
internal fun EditText.ittcAcceptBengaliDigits() {
    try {
        keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.০১২৩৪৫৬৭৮৯")
        setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    } catch (_: Throwable) {
    }
}
