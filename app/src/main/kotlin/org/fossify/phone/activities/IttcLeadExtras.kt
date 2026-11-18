package org.fossify.phone.activities

import android.app.Activity
import android.content.Context
import android.graphics.Color
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcVisitFeeApi
import org.fossify.phone.helpers.IttcTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 🏫💰 The lead page's Visit card and Fee card (ITTC Phone 3.4, direct mode): a status line or two
 * and small buttons that open [IttcVisitSheet] / [IttcFeeSheet]. Filled off the main thread; safe to
 * call again to reload (an older load that comes back late is dropped); never crashes the page.
 */
object IttcLeadExtras {
    /** Fill [container] with the Visit card and the Fee card (cards the Tracker hides stay away) */
    fun bind(activity: Activity, container: LinearLayout, leadId: String) {
        try {
            bindNow(activity, container, leadId)
        } catch (_: Throwable) {
        }
    }

    private class Loaded(
        val info: IttcVisitFeeApi.LeadInfo,
        val visits: List<IttcVisitFeeApi.Visit>,
        val visitSetting: IttcVisitFeeApi.VisitSetting,
        val negotiations: List<IttcVisitFeeApi.Negotiation>,
        val feesAllowed: Boolean,
    )

    private fun bindNow(activity: Activity, container: LinearLayout, leadId: String) {
        if (leadId.isBlank()) return
        val gen = ((container.getTag(R.id.ittc_c_extras_gen) as? Int) ?: 0) + 1
        container.setTag(R.id.ittc_c_extras_gen, gen)
        // (the first time: a quiet placeholder; a reload keeps the old cards until the new ones come)
        if (container.childCount == 0) {
            container.addView(IttcCUi.loadingCard(activity), IttcCUi.cardParams(activity))
        }
        val app = activity.applicationContext
        ensureBackgroundThread {
            val result: Any = try {
                val info = IttcVisitFeeApi.leadInfo(app, leadId) ?: throw IttcApi.ApiError(404, activity.getString(R.string.ittc_c_lead_not_found))
                Loaded(
                    info = info,
                    visits = IttcVisitFeeApi.visits(app, leadId),
                    visitSetting = IttcVisitFeeApi.visitSetting(app),
                    negotiations = IttcVisitFeeApi.negotiations(app, leadId),
                    feesAllowed = IttcVisitFeeApi.canUseFees(app),
                )
            } catch (t: Throwable) {
                IttcCUi.errorText(activity, t)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (container.getTag(R.id.ittc_c_extras_gen) != gen) return@runOnUiThread
                try {
                    container.removeAllViews()
                    if (result is Loaded) {
                        visitCard(activity, container, leadId, result)?.let { container.addView(it, IttcCUi.cardParams(activity)) }
                        feeCard(activity, container, leadId, result)?.let { container.addView(it, IttcCUi.cardParams(activity)) }
                    } else {
                        container.addView(errorCard(activity, container, leadId, result.toString()), IttcCUi.cardParams(activity))
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun reload(activity: Activity, container: LinearLayout, leadId: String): () -> Unit = {
        bind(activity, container, leadId)
    }

    // ------------------------------------------------------------------ 🏫 visit
    private fun visitCard(activity: Activity, container: LinearLayout, leadId: String, d: Loaded): View? {
        val p = IttcTheme.of(activity)
        val planned = d.visits.firstOrNull { it.status == "planned" }
        val last = d.visits.firstOrNull { it.status != "planned" }
        // (as the Tracker: switched off and nothing to show, or a closed lead that never had a visit)
        if (!d.visitSetting.enabled && d.visits.isEmpty()) return null
        if (!d.info.isOpen && d.visits.isEmpty()) return null

        val card = IttcCUi.card(activity)
        val (label, bg, fg) = when {
            planned != null -> Triple(activity.getString(R.string.ittc_c_visit_planned), p.primarySoft, p.primary)
            last != null -> IttcCUi.visitStatus(activity, last.status)
            else -> Triple(activity.getString(R.string.ittc_c_visit_not_planned), p.surfaceAlt, p.muted)
        }
        card.addView(IttcCUi.header(activity, activity.getString(R.string.ittc_c_visit_title), label, bg, fg))

        val line = when {
            planned != null -> {
                val at = planned.plannedAt
                val overdue = at != null && at < System.currentTimeMillis()
                val text = (at?.let { "📅 " + IttcCUi.whenText(it) } ?: activity.getString(R.string.ittc_c_visit_no_date)) +
                    if (overdue) " · " + activity.getString(R.string.ittc_c_overdue) else ""
                IttcCUi.text(activity, text, 14f, if (overdue) p.danger else p.text, bold = true)
            }
            last != null -> {
                val at = if (last.status == "visited") last.visitedAt else last.plannedAt
                IttcCUi.text(
                    activity,
                    activity.getString(R.string.ittc_c_visit_last, IttcCUi.visitStatus(activity, last.status).first, at?.let { IttcCUi.whenText(it) } ?: "—"),
                    13f, p.muted,
                )
            }
            else -> IttcCUi.text(activity, activity.getString(R.string.ittc_c_visit_none_hint), 13f, p.muted)
        }
        card.addView(line, IttcCUi.topMargin(activity, 6))
        planned?.note?.let { card.addView(IttcCUi.text(activity, it, 13f, p.text2), IttcCUi.topMargin(activity, 2)) }

        val open = { IttcVisitSheet.show(activity, leadId, d.info.name, reload(activity, container, leadId)) }
        val buttons = IttcCUi.row(activity)
        when {
            planned != null -> {
                buttons.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_visit_came), IttcCUi.Style.CALL) { open() }, IttcCUi.weight(activity))
                buttons.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_visit_manage), IttcCUi.Style.OUTLINE) { open() }, IttcCUi.weight(activity))
            }
            d.info.isOpen && d.visitSetting.enabled ->
                buttons.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_visit_book), IttcCUi.Style.PRIMARY) { open() }, IttcCUi.weight(activity))
            else ->
                buttons.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_visit_history), IttcCUi.Style.OUTLINE) { open() }, IttcCUi.weight(activity))
        }
        card.addView(buttons, IttcCUi.topMargin(activity, 10))
        if (planned != null) card.background = IttcTheme.rounded(activity, p.surface, 16f, p.primary)
        return card
    }

    // ------------------------------------------------------------------ 💰 fee
    private fun feeCard(activity: Activity, container: LinearLayout, leadId: String, d: Loaded): View? {
        val p = IttcTheme.of(activity)
        // (as the Tracker: off for this person, or a closed lead that never talked about the fee)
        if ((!d.feesAllowed || !d.info.isOpen) && d.negotiations.isEmpty()) return null
        val n = d.negotiations.lastOrNull()
        val card = IttcCUi.card(activity)
        val waiting = n?.discounts?.any { it.status == "pending" } == true
        val (label, bg, fg) = when {
            n == null -> Triple(activity.getString(R.string.ittc_c_fee_not_started), p.surfaceAlt, p.muted)
            n.locked -> Triple(activity.getString(R.string.ittc_c_fee_final), IttcTheme.stageColors(activity, "admitted").first, IttcTheme.stageColors(activity, "admitted").second)
            waiting -> Triple(activity.getString(R.string.ittc_c_fee_waiting), IttcTheme.stageColors(activity, "interested").first, IttcTheme.stageColors(activity, "interested").second)
            else -> Triple(activity.getString(R.string.ittc_c_fee_negotiating), p.primarySoft, p.primary)
        }
        card.addView(IttcCUi.header(activity, activity.getString(R.string.ittc_c_fee_title), label, bg, fg))

        if (n == null) {
            card.addView(IttcCUi.text(activity, activity.getString(R.string.ittc_c_fee_start_hint), 13f, p.muted), IttcCUi.topMargin(activity, 6))
        } else {
            val t = n.totals()
            val main = if (n.locked) {
                activity.getString(R.string.ittc_c_fee_final_line, IttcVisitFeeApi.money(t.finalFee))
            } else {
                activity.getString(R.string.ittc_c_fee_to_pay_line, IttcVisitFeeApi.money(t.finalFee))
            }
            card.addView(IttcCUi.text(activity, main, 15f, p.text, bold = true), IttcCUi.topMargin(activity, 6))
            val parts = arrayListOf(n.courseName, activity.getString(R.string.ittc_c_fee_full_short, IttcVisitFeeApi.money(t.list)))
            if (t.discount > 0) parts += activity.getString(R.string.ittc_c_fee_off_short, IttcVisitFeeApi.money(t.discount))
            card.addView(IttcCUi.text(activity, parts.joinToString(" · "), 13f, p.muted), IttcCUi.topMargin(activity, 2))
            n.lastOf("offer")?.amount?.let {
                card.addView(IttcCUi.text(activity, activity.getString(R.string.ittc_c_fee_offer_line, IttcVisitFeeApi.money(it)), 13f, p.text2), IttcCUi.topMargin(activity, 2))
            }
            n.discounts.lastOrNull()?.let { last ->
                val (s, _, color) = IttcCUi.discountStatus(activity, n, last)
                card.addView(
                    IttcCUi.text(activity, activity.getString(R.string.ittc_c_fee_last_request, IttcVisitFeeApi.money(last.amount), s), 13f, color),
                    IttcCUi.topMargin(activity, 2),
                )
            }
        }
        val canWork = d.feesAllowed && d.info.isOpen && n?.locked != true
        val buttons = IttcCUi.row(activity)
        val open = { IttcFeeSheet.show(activity, leadId, reload(activity, container, leadId)) }
        if (canWork) {
            buttons.addView(
                IttcCUi.button(activity, activity.getString(if (n == null) R.string.ittc_c_fee_start else R.string.ittc_c_fee_ask_discount), IttcCUi.Style.PRIMARY) { open() },
                IttcCUi.weight(activity),
            )
        }
        buttons.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_fee_details), IttcCUi.Style.OUTLINE) { open() }, IttcCUi.weight(activity))
        card.addView(buttons, IttcCUi.topMargin(activity, 10))
        return card
    }

    private fun errorCard(activity: Activity, container: LinearLayout, leadId: String, message: String): View {
        val p = IttcTheme.of(activity)
        val card = IttcCUi.card(activity)
        card.addView(IttcCUi.text(activity, activity.getString(R.string.ittc_c_extras_error, message), 13f, p.danger))
        val row = IttcCUi.row(activity)
        row.addView(IttcCUi.button(activity, activity.getString(R.string.ittc_c_retry), IttcCUi.Style.OUTLINE) { bind(activity, container, leadId) }, IttcCUi.weight(activity))
        card.addView(row, IttcCUi.topMargin(activity, 8))
        return card
    }
}

/**
 * 🧩 Part C's small view kit (cards, pills, buttons, chips, fields, the bottom sheet), all from
 * [IttcTheme]'s palette — built in code, light (no nested heavy layouts).
 */
internal object IttcCUi {
    enum class Style { PRIMARY, CALL, OUTLINE, DANGER, GHOST }

    fun dp(c: Context, v: Int) = IttcTheme.dp(c, v.toFloat())

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(c).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(c, 16)
        setPadding(pad, dp(c, 14), pad, dp(c, 14))
        IttcTheme.card(this)
    }

    fun cardParams(c: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, dp(c, 6), 0, dp(c, 6))
    }

    fun topMargin(c: Context, v: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(c, v)
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun weight(c: Context) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(c, 8)
    }

    fun wrap(c: Context, end: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginEnd = dp(c, end)
    }

    /** "🏫 Campus visit ……… [Planned]" */
    fun header(c: Context, title: String, pillText: String?, pillBg: Int, pillFg: Int): View {
        val p = IttcTheme.of(c)
        val r = row(c)
        r.addView(text(c, title, 16f, p.text, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (pillText != null) r.addView(pill(c, pillText, pillBg, pillFg))
        return r
    }

    fun pill(c: Context, s: String, bg: Int, fg: Int, outline: Int = 0): TextView = text(c, s, 12f, fg, bold = true).apply {
        setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
        IttcTheme.pill(this, bg, fg, outline)
    }

    /** A round button, ≥ 44 dp tall */
    fun button(c: Context, label: String, style: Style, onClick: () -> Unit): TextView {
        val p = IttcTheme.of(c)
        return text(c, label, 14f, p.text, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 44)
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            maxLines = 2
            when (style) {
                Style.PRIMARY -> IttcTheme.button(this, p.primary, p.onPrimary)
                Style.CALL -> IttcTheme.button(this, p.call, if (p.dark) p.onPrimary else Color.WHITE)
                Style.DANGER -> IttcTheme.button(this, p.dangerSoft, p.danger)
                Style.OUTLINE -> {
                    background = IttcTheme.rounded(c, p.surface, 999f, p.outline)
                    setTextColor(p.primary)
                }
                Style.GHOST -> {
                    background = null
                    setTextColor(p.muted)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // (no double taps while a request runs)
                if (!isEnabled) return@setOnClickListener
                onClick()
            }
        }
    }

    fun setBusy(v: TextView, busy: Boolean) {
        v.isEnabled = !busy
        v.alpha = if (busy) 0.5f else 1f
    }

    /** A choice chip (on: filled primary) */
    fun chip(c: Context, label: String, on: Boolean, onClick: () -> Unit): TextView {
        val p = IttcTheme.of(c)
        return text(c, label, 13f, if (on) p.onPrimary else p.text, bold = on).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 40)
            setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6))
            background = if (on) IttcTheme.rounded(c, p.primary, 999f) else IttcTheme.rounded(c, p.surface, 999f, p.outline)
            setOnClickListener { onClick() }
        }
    }

    /** Chips that wrap: a horizontal scroller (simple and light) */
    fun chipRow(c: Context, chips: List<View>): View {
        val line = row(c)
        chips.forEach { line.addView(it, wrap(c)) }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(line)
        }
    }

    fun input(c: Context, hint: String, type: Int, lines: Int = 1): EditText {
        val p = IttcTheme.of(c)
        return EditText(c).apply {
            this.hint = hint
            inputType = type
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = IttcTheme.rounded(c, p.surfaceAlt, 12f, p.outline)
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
            minHeight = dp(c, 44)
            if (lines > 1) {
                minLines = lines
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            } else {
                maxLines = 1
            }
        }
    }

    fun noteInput(c: Context, hint: String) =
        input(c, hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 2)

    fun loadingCard(c: Context): View {
        val p = IttcTheme.of(c)
        return card(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ProgressBar(c).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)))
            addView(text(c, c.getString(R.string.ittc_c_loading), 13f, p.muted).apply { gravity = Gravity.CENTER }, topMargin(c, 6))
        }
    }

    fun section(c: Context, label: String): TextView {
        val p = IttcTheme.of(c)
        return text(c, label.uppercase(Locale.ENGLISH), 12f, p.muted, bold = true).apply { letterSpacing = 0.06f }
    }

    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(IttcTheme.of(c).divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)).apply {
            topMargin = dp(c, 8)
            bottomMargin = dp(c, 8)
        }
    }

    // ------------------------------------------------------------------ the sheet
    class Sheet(val dialog: BottomSheetDialog, val body: LinearLayout)

    /** A bottom sheet with a rounded top, a handle, a scrolling body; keyboard pushes it up */
    fun sheet(activity: Activity): Sheet {
        val p = IttcTheme.of(activity)
        val dialog = BottomSheetDialog(activity)
        val r = dp(activity, 20).toFloat()
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        holder.addView(
            View(activity).apply { background = IttcTheme.rounded(activity, p.outline, 999f) },
            LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, 10)
            },
        )
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 28))
        }
        holder.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(holder)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            try {
                // (our own rounded surface instead of the theme's sheet colour)
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
            } catch (_: Throwable) {
            }
        }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return Sheet(dialog, body)
    }

    fun showSafely(activity: Activity, dialog: BottomSheetDialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            dialog.show()
        } catch (_: Throwable) {
        }
    }

    /** A yes / no question (the Tracker's Alert) */
    fun confirm(activity: Activity, title: String, message: String, yes: String, no: String, onYes: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = text(activity, message, 15f, IttcTheme.of(activity).text).apply {
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
        }
        activity.getAlertDialogBuilder()
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(no, null)
            .apply { activity.setupDialogStuff(view, this, titleText = title) { _: AlertDialog -> } }
    }

    /** The readable message of a failure (the server's own words) */
    fun errorText(c: Context, t: Throwable): String = when {
        t is IttcApi.ApiError && t.status == 0 -> c.getString(R.string.ittc_c_no_internet)
        !t.message.isNullOrBlank() -> t.message!!
        else -> c.getString(R.string.ittc_c_failed)
    }

    // ------------------------------------------------------------------ words
    private val DAY = SimpleDateFormat("d MMM", Locale.ENGLISH)
    private val DAY_YEAR = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
    private val TIME = SimpleDateFormat("h:mm a", Locale.ENGLISH)

    /** "Today, 11:00 AM" / "Tomorrow, 11:00 AM" / "Tue 18 Nov, 11:00 AM" */
    @Synchronized
    fun whenText(ms: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        val days = dayNumber(d) - dayNumber(now)
        val day = when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Yesterday"
            d.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> DAY.format(Date(ms))
            else -> DAY_YEAR.format(Date(ms))
        }
        return "$day, ${TIME.format(Date(ms))}"
    }

    @Synchronized
    fun dayText(ms: Long): String = DAY.format(Date(ms))

    /** The local day as a number (today − tomorrow = 1, also across a year end) */
    private fun dayNumber(c: Calendar): Long =
        Math.floorDiv(c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET), 86_400_000L)

    /** A visit's status: (label, pill bg, pill fg) */
    fun visitStatus(c: Context, status: String): Triple<String, Int, Int> {
        val p = IttcTheme.of(c)
        val (gBg, gFg) = IttcTheme.stageColors(c, "admitted")
        return when (status) {
            "visited" -> Triple(c.getString(R.string.ittc_c_visit_visited), gBg, gFg)
            "missed" -> Triple(c.getString(R.string.ittc_c_visit_missed), p.dangerSoft, p.danger)
            "cancelled" -> Triple(c.getString(R.string.ittc_c_visit_cancelled), p.surfaceAlt, p.muted)
            else -> Triple(c.getString(R.string.ittc_c_visit_planned), p.primarySoft, p.primary)
        }
    }

    /** A discount's status: (label, pill bg, pill fg) — Approved / Waiting / Not approved / Taken back / Ran out */
    fun discountStatus(c: Context, n: IttcVisitFeeApi.Negotiation, d: IttcVisitFeeApi.Discount): Triple<String, Int, Int> {
        val p = IttcTheme.of(c)
        val (gBg, gFg) = IttcTheme.stageColors(c, "admitted")
        val (wBg, wFg) = IttcTheme.stageColors(c, "interested")
        val expired = d.status == "approved" && !n.isActive(d)
        return when {
            expired -> Triple(c.getString(R.string.ittc_c_disc_expired), p.surfaceAlt, p.muted)
            d.status == "approved" -> Triple(c.getString(R.string.ittc_c_disc_approved), gBg, gFg)
            d.status == "pending" -> Triple(c.getString(R.string.ittc_c_disc_pending), wBg, wFg)
            d.status == "rejected" -> Triple(c.getString(R.string.ittc_c_disc_rejected), p.dangerSoft, p.danger)
            else -> Triple(c.getString(R.string.ittc_c_disc_cancelled), p.surfaceAlt, p.muted)
        }
    }
}
