package org.fossify.phone.activities

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcVisitFeeApi
import org.fossify.phone.direct.IttcVisitFeeApi.Negotiation
import org.fossify.phone.helpers.IttcTheme
import kotlin.math.roundToLong

/**
 * 💰 Fee & discount (ITTC Phone 3.4, direct mode) — as the Tracker's FeeCard: the course's fee
 * list, the discount in force, what is left to pay (🔒 the final fee once admitted), the latest
 * offer, every discount request with its answer; start the fee talk for a course, note "what was
 * said", ask for a discount (taka or %, a reason, a note: approved at once within my limit, else it
 * waits for approval) and take one back. Buttons only when the Tracker shows them (can_use_fees,
 * an open lead, an open fee talk); the server decides the rest and its message is shown.
 */
object IttcFeeSheet {
    private enum class Mode { VIEW, COURSES, DISCOUNT, EVENT }

    private class State(val activity: Activity, val leadId: String, val onDone: () -> Unit, val sheet: IttcCUi.Sheet) {
        var info: IttcVisitFeeApi.LeadInfo? = null
        var items: List<Negotiation> = emptyList()
        var setting: IttcVisitFeeApi.FeeSetting? = null
        var allowed = false
        var limit = 0.0
        var me: String? = null
        var tab: String? = null
        var courses: List<IttcVisitFeeApi.CourseFee>? = null
        var mode = Mode.VIEW
        var busy = false
        var changed = false

        // the discount form
        var feeId: String? = null
        var percent = false
        var value = ""
        var reason: String? = null
        var note = ""

        // "what was said"
        var eventKind = "lead_asked"

        val current get() = items.firstOrNull { it.id == tab } ?: items.lastOrNull()
        val canWork get() = allowed && info?.isOpen == true && current?.locked == false
    }

    fun show(activity: Activity, leadId: String, onDone: () -> Unit) {
        if (leadId.isBlank() || activity.isFinishing || activity.isDestroyed) return
        try {
            val st = State(activity, leadId, onDone, IttcCUi.sheet(activity))
            st.sheet.dialog.setOnDismissListener { if (st.changed) safe(onDone) }
            showLoading(st)
            IttcCUi.showSafely(activity, st.sheet.dialog)
            load(st)
        } catch (_: Throwable) {
        }
    }

    private fun safe(f: () -> Unit) = try {
        f()
    } catch (_: Throwable) {
    }

    private fun alive(st: State) = !st.activity.isFinishing && !st.activity.isDestroyed && st.sheet.dialog.isShowing

    private class Loaded(
        val info: IttcVisitFeeApi.LeadInfo?,
        val items: List<Negotiation>,
        val setting: IttcVisitFeeApi.FeeSetting,
        val allowed: Boolean,
        val limit: Double,
        val me: String?,
    )

    private fun load(st: State, thenTab: String? = null) {
        val app = st.activity.applicationContext
        ensureBackgroundThread {
            val result: Any = try {
                Loaded(
                    info = IttcVisitFeeApi.leadInfo(app, st.leadId),
                    items = IttcVisitFeeApi.negotiations(app, st.leadId),
                    setting = IttcVisitFeeApi.feeSetting(app),
                    allowed = IttcVisitFeeApi.canUseFees(app),
                    limit = IttcVisitFeeApi.discountLimit(app),
                    me = IttcVisitFeeApi.myId(app),
                )
            } catch (t: Throwable) {
                IttcCUi.errorText(app, t)
            }
            st.activity.runOnUiThread {
                if (!alive(st)) return@runOnUiThread
                if (result is Loaded) {
                    if (result.info == null) return@runOnUiThread showError(st, st.activity.getString(R.string.ittc_c_lead_not_found))
                    st.info = result.info
                    st.items = result.items
                    st.setting = result.setting
                    st.allowed = result.allowed
                    st.limit = result.limit
                    st.me = result.me
                    val want = thenTab ?: st.tab
                    st.tab = if (want != null && result.items.any { it.id == want }) want else result.items.lastOrNull()?.id
                    render(st)
                } else {
                    showError(st, result.toString())
                }
            }
        }
    }

    // ------------------------------------------------------------------ views
    private fun title(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val n = st.current
        val pill = when {
            n == null -> null
            n.locked -> Triple(a.getString(R.string.ittc_c_fee_final), IttcTheme.stageColors(a, "admitted").first, IttcTheme.stageColors(a, "admitted").second)
            else -> Triple(a.getString(R.string.ittc_c_fee_negotiating), p.primarySoft, p.primary)
        }
        st.sheet.body.addView(IttcCUi.header(a, a.getString(R.string.ittc_c_fee_title), pill?.first, pill?.second ?: 0, pill?.third ?: 0).apply {
            (this as? LinearLayout)?.getChildAt(0)?.let { (it as? TextView)?.textSize = 20f }
        })
        st.info?.name?.takeIf { it.isNotBlank() }?.let {
            st.sheet.body.addView(IttcCUi.text(a, it, 14f, p.muted), IttcCUi.topMargin(a, 2))
        }
    }

    private fun showLoading(st: State) {
        st.sheet.body.removeAllViews()
        title(st)
        st.sheet.body.addView(
            ProgressBar(st.activity).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(IttcCUi.dp(st.activity, 32), IttcCUi.dp(st.activity, 32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = IttcCUi.dp(st.activity, 24)
                bottomMargin = IttcCUi.dp(st.activity, 24)
            },
        )
    }

    private fun showError(st: State, message: String) {
        val a = st.activity
        st.sheet.body.removeAllViews()
        title(st)
        st.sheet.body.addView(IttcCUi.text(a, message, 14f, IttcTheme.of(a).danger), IttcCUi.topMargin(a, 16))
        val row = IttcCUi.row(a)
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_retry), IttcCUi.Style.OUTLINE) {
            showLoading(st)
            load(st)
        }, IttcCUi.weight(a))
        st.sheet.body.addView(row, IttcCUi.topMargin(a, 12))
    }

    private fun render(st: State) {
        if (!alive(st)) return
        st.sheet.body.removeAllViews()
        title(st)
        when (st.mode) {
            Mode.COURSES -> renderCourses(st)
            Mode.DISCOUNT -> st.current?.let { renderDiscount(st, it) } ?: renderView(st)
            Mode.EVENT -> st.current?.let { renderEvent(st, it) } ?: renderView(st)
            Mode.VIEW -> renderView(st)
        }
    }

    private fun go(st: State, mode: Mode) {
        if (st.busy) return
        st.mode = mode
        if (mode == Mode.DISCOUNT) {
            st.feeId = null
            st.percent = false
            st.value = ""
            st.reason = null
            st.note = ""
        }
        if (mode == Mode.EVENT) st.eventKind = "lead_asked"
        render(st)
    }

    private fun renderView(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        val info = st.info ?: return

        if (st.items.isEmpty()) {
            val hint = when {
                !st.allowed -> a.getString(R.string.ittc_c_fee_off_for_you)
                !info.isOpen -> a.getString(R.string.ittc_c_fee_closed)
                else -> a.getString(R.string.ittc_c_fee_start_hint)
            }
            body.addView(IttcCUi.text(a, hint, 14f, p.muted), IttcCUi.topMargin(a, 14))
        }

        // course tabs (more than one)
        if (st.items.size > 1) {
            val chips = st.items.map { n ->
                IttcCUi.chip(a, n.courseName, n.id == st.current?.id) {
                    st.tab = n.id
                    render(st)
                }
            }
            body.addView(IttcCUi.chipRow(a, chips), IttcCUi.topMargin(a, 12))
        }

        st.current?.let { negotiation(st, body, it) }

        if (st.canWork) {
            val r = IttcCUi.row(a)
            r.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_fee_ask_discount), IttcCUi.Style.PRIMARY) { go(st, Mode.DISCOUNT) }, IttcCUi.weight(a))
            r.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_fee_what_said), IttcCUi.Style.OUTLINE) { go(st, Mode.EVENT) }, IttcCUi.weight(a))
            body.addView(r, IttcCUi.topMargin(a, 14))
        }
        if (st.allowed && info.isOpen) {
            body.addView(
                IttcCUi.button(a, a.getString(if (st.items.isEmpty()) R.string.ittc_c_fee_start else R.string.ittc_c_fee_another), IttcCUi.Style.GHOST) {
                    go(st, Mode.COURSES)
                }.apply { setTextColor(p.primary) },
                IttcCUi.topMargin(a, 6),
            )
        }
    }

    /** One course: fee list, totals, the latest offer, the discounts, what was said */
    private fun negotiation(st: State, body: LinearLayout, n: Negotiation) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val t = n.totals()
        val box = IttcCUi.card(a).apply { background = IttcTheme.rounded(a, p.surfaceAlt, 16f) }
        fun line(label: String, value: String, strong: Boolean = false, color: Int = p.text) {
            val r = IttcCUi.row(a)
            r.addView(IttcCUi.text(a, label, if (strong) 15f else 14f, if (strong) p.text else p.text2, bold = strong), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(IttcCUi.text(a, value, if (strong) 16f else 14f, color, bold = strong))
            box.addView(r, IttcCUi.topMargin(a, 4))
        }
        n.feeRows().forEach { f ->
            val off = if (f.id != null) n.discounts.filter { it.feeId == f.id && n.isActive(it) }.sumOf { it.amount } else 0.0
            line(f.name, (if (off > 0) "−${IttcVisitFeeApi.money(off)}  " else "") + IttcVisitFeeApi.money(f.amount))
        }
        box.addView(IttcCUi.divider(a))
        line(a.getString(R.string.ittc_c_fee_full), IttcVisitFeeApi.money(t.list))
        if (t.discount > 0) {
            val pct = Math.round(t.discount / maxOf(t.list, 1.0) * 1000) / 10.0
            line(a.getString(R.string.ittc_c_fee_discount_pct, pctText(pct)), "−" + IttcVisitFeeApi.money(t.discount), color = IttcTheme.stageColors(a, "admitted").second)
        }
        line(a.getString(if (n.locked) R.string.ittc_c_fee_final_admitted else R.string.ittc_c_fee_to_pay), IttcVisitFeeApi.money(t.finalFee), strong = true)
        if (t.pending > 0) {
            box.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_fee_pending_more, IttcVisitFeeApi.money(t.pending)), 13f, IttcTheme.stageColors(a, "interested").second), IttcCUi.topMargin(a, 6))
        }
        body.addView(box, IttcCUi.topMargin(a, 12))

        // the latest offer / ask
        val offer = n.lastOf("offer")
        val asked = n.lastOf("lead_asked")
        if (offer?.amount != null || asked?.amount != null) {
            val parts = listOfNotNull(
                offer?.amount?.let { a.getString(R.string.ittc_c_fee_offer_line, IttcVisitFeeApi.money(it)) },
                asked?.amount?.let { a.getString(R.string.ittc_c_fee_asked_line, IttcVisitFeeApi.money(it)) },
            )
            body.addView(IttcCUi.text(a, parts.joinToString("  ·  "), 14f, p.text2, bold = true), IttcCUi.topMargin(a, 10))
        }

        // discounts, newest first
        if (n.discounts.isNotEmpty()) {
            body.addView(IttcCUi.section(a, a.getString(R.string.ittc_c_fee_discounts)), IttcCUi.topMargin(a, 18))
            n.discounts.reversed().take(8).forEach { d -> body.addView(discountRow(st, n, d), IttcCUi.topMargin(a, 8)) }
        }

        // what was said, newest first
        if (n.events.isNotEmpty()) {
            body.addView(IttcCUi.section(a, a.getString(R.string.ittc_c_fee_said)), IttcCUi.topMargin(a, 18))
            n.events.reversed().take(5).forEach { e ->
                val what = if (e.kind == "note") e.note.orEmpty() else "${eventLabel(a, e.kind)} ${IttcVisitFeeApi.money(e.amount ?: 0.0)}"
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(IttcCUi.text(a, what, 14f, p.text))
                if (e.kind != "note") e.note?.let { col.addView(IttcCUi.text(a, "“$it”", 13f, p.text2)) }
                col.addView(IttcCUi.text(a, listOfNotNull(e.by, if (e.at > 0) IttcCUi.dayText(e.at) else null).joinToString(" · "), 12f, p.muted))
                body.addView(col, IttcCUi.topMargin(a, 8))
            }
        }
    }

    private fun discountRow(st: State, n: Negotiation, d: IttcVisitFeeApi.Discount): View {
        val a = st.activity
        val p = IttcTheme.of(a)
        val (label, bg, fg) = IttcCUi.discountStatus(a, n, d)
        val expired = d.status == "approved" && !n.isActive(d)
        // (as the Tracker: the person who asked, or someone who may give discounts, while it counts)
        val mayCancel = st.canWork && (d.status == "pending" || (d.status == "approved" && !expired)) && (d.requestedBy == st.me || st.limit > 0)
        val card = IttcCUi.card(a).apply {
            background = IttcTheme.rounded(a, p.surface, 14f, p.outline)
            setPadding(IttcCUi.dp(a, 12), IttcCUi.dp(a, 10), IttcCUi.dp(a, 12), IttcCUi.dp(a, 10))
        }
        val top = IttcCUi.row(a)
        val title = a.getString(R.string.ittc_c_fee_off_on, IttcVisitFeeApi.money(d.amount), d.feeName ?: a.getString(R.string.ittc_c_fee_whole)) +
            if (d.kind == "percent") " (${pctText(d.value)}%)" else ""
        top.addView(IttcCUi.text(a, title, 14f, p.text, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(IttcCUi.pill(a, label, bg, fg))
        card.addView(top)
        val meta = listOfNotNull(d.reason, d.requesterName, if (d.requestedAt > 0) IttcCUi.dayText(d.requestedAt) else null).joinToString(" · ")
        if (meta.isNotEmpty()) card.addView(IttcCUi.text(a, meta, 12f, p.muted), IttcCUi.topMargin(a, 2))
        d.note?.let { card.addView(IttcCUi.text(a, "“$it”", 13f, p.text2), IttcCUi.topMargin(a, 2)) }
        if (d.status != "pending" && d.decidedBy != null && d.decidedBy != d.requestedBy) {
            val verb = a.getString(
                when (d.status) {
                    "approved" -> R.string.ittc_c_disc_by_approved
                    "rejected" -> R.string.ittc_c_disc_by_answered
                    else -> R.string.ittc_c_disc_by_taken
                },
                d.deciderName ?: a.getString(R.string.ittc_c_someone),
            ) + (d.decisionNote?.let { ": “$it”" } ?: "")
            card.addView(IttcCUi.text(a, verb, 12f, p.text2), IttcCUi.topMargin(a, 2))
        }
        if (d.status == "approved" && !expired && d.expiresAt != null && !n.locked) {
            card.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_disc_valid_till, IttcCUi.dayText(d.expiresAt)), 12f, p.muted), IttcCUi.topMargin(a, 2))
        }
        if (mayCancel) {
            card.addView(
                IttcCUi.button(a, a.getString(R.string.ittc_c_disc_take_back), IttcCUi.Style.GHOST) { confirmTakeBack(st, d) }.apply {
                    setTextColor(p.danger)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                },
                IttcCUi.topMargin(a, 2),
            )
        }
        return card
    }

    // ------------------------------------------------------------------ start for a course
    private fun renderCourses(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_fee_pick_course), 16f, p.text, bold = true), IttcCUi.topMargin(a, 14))
        body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_fee_pick_hint), 13f, p.muted), IttcCUi.topMargin(a, 2))
        val list = st.courses
        if (list == null) {
            body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_loading), 13f, p.muted), IttcCUi.topMargin(a, 12))
            val app = a.applicationContext
            val ids = st.info?.courseIds.orEmpty()
            ensureBackgroundThread {
                val r: Any = try {
                    IttcVisitFeeApi.coursesWithFees(app, ids)
                } catch (t: Throwable) {
                    IttcCUi.errorText(app, t)
                }
                a.runOnUiThread {
                    if (!alive(st) || st.mode != Mode.COURSES) return@runOnUiThread
                    if (r is List<*>) {
                        st.courses = r.filterIsInstance<IttcVisitFeeApi.CourseFee>()
                        render(st)
                    } else {
                        a.toast(r.toString())
                        go(st, Mode.VIEW)
                    }
                }
            }
        } else {
            val used = st.items.map { it.courseId }.toSet()
            val mine = st.info?.courseIds.orEmpty()
            val left = list.filter { it.id !in used }
            if (left.isEmpty()) body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_fee_no_courses), 13f, p.muted), IttcCUi.topMargin(a, 12))
            left.forEach { c ->
                val row = IttcCUi.row(a).apply {
                    background = IttcTheme.rounded(a, p.surface, 14f, p.outline)
                    setPadding(IttcCUi.dp(a, 14), 0, IttcCUi.dp(a, 14), 0)
                    minimumHeight = IttcCUi.dp(a, 48)
                }
                row.addView(IttcCUi.text(a, (if (c.id in mine) "★ " else "") + c.name, 14f, p.text, bold = c.id in mine).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(IttcCUi.text(a, IttcVisitFeeApi.money(c.total), 14f, p.text2))
                row.setOnClickListener { start(st, c) }
                body.addView(row, IttcCUi.topMargin(a, 8))
            }
        }
        val back = IttcCUi.row(a)
        back.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_back), IttcCUi.Style.GHOST) { go(st, Mode.VIEW) }, IttcCUi.weight(a))
        body.addView(back, IttcCUi.topMargin(a, 12))
    }

    private fun start(st: State, c: IttcVisitFeeApi.CourseFee) {
        if (st.busy) return
        st.busy = true
        val a = st.activity
        val app = a.applicationContext
        a.toast(a.getString(R.string.ittc_c_fee_starting, c.name))
        ensureBackgroundThread {
            var id: String? = null
            val error = try {
                id = IttcVisitFeeApi.startNegotiation(app, st.leadId, c.id)
                null
            } catch (t: Throwable) {
                IttcCUi.errorText(app, t)
            }
            a.runOnUiThread {
                st.busy = false
                if (error != null) return@runOnUiThread a.toast(error)
                st.changed = true
                a.toast(a.getString(R.string.ittc_c_fee_started))
                IttcDirect.refreshAsync(app, force = true, delayMs = 500)
                if (!alive(st)) return@runOnUiThread
                st.mode = Mode.VIEW
                showLoading(st)
                load(st, thenTab = id)
            }
        }
    }

    // ------------------------------------------------------------------ ask for a discount
    private fun renderDiscount(st: State, n: Negotiation) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        val setting = st.setting ?: IttcVisitFeeApi.FeeSetting(true, emptyList(), true)
        val t = n.totals()
        body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_disc_title, n.courseName), 16f, p.text, bold = true), IttcCUi.topMargin(a, 14))

        // on: the whole fee or one fee item
        body.addView(IttcCUi.section(a, a.getString(R.string.ittc_c_disc_on)), IttcCUi.topMargin(a, 12))
        val onChips = arrayListOf<View>(
            IttcCUi.chip(a, a.getString(R.string.ittc_c_disc_whole, IttcVisitFeeApi.money(t.list)), st.feeId == null) {
                st.feeId = null
                render(st)
            },
        )
        n.courseFees.forEach { f ->
            if (f.id != null) {
                onChips += IttcCUi.chip(a, "${f.name} · ${IttcVisitFeeApi.money(f.amount)}", st.feeId == f.id) {
                    st.feeId = f.id
                    render(st)
                }
            }
        }
        body.addView(IttcCUi.chipRow(a, onChips), IttcCUi.topMargin(a, 6))

        // taka or %, and how much
        val amountRow = IttcCUi.row(a)
        amountRow.addView(IttcCUi.chip(a, a.getString(R.string.ittc_c_disc_taka), !st.percent) {
            st.percent = false
            render(st)
        }, IttcCUi.wrap(a, 6))
        amountRow.addView(IttcCUi.chip(a, a.getString(R.string.ittc_c_disc_percent), st.percent) {
            st.percent = true
            render(st)
        }, IttcCUi.wrap(a, 8))
        val value = IttcCUi.input(a, if (st.percent) "10" else "3000", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText(st.value)
        }
        amountRow.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(amountRow, IttcCUi.topMargin(a, 12))

        // what it comes to (as the Tracker's preview)
        val preview = IttcCUi.text(a, "", 13f, p.text).apply {
            setPadding(IttcCUi.dp(a, 12), IttcCUi.dp(a, 10), IttcCUi.dp(a, 12), IttcCUi.dp(a, 10))
            visibility = View.GONE
        }
        body.addView(preview, IttcCUi.topMargin(a, 8))
        val base = if (st.feeId == null) t.list else n.courseFees.firstOrNull { it.id == st.feeId }?.amount ?: 0.0
        fun amountOf(text: String): Double {
            val v = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
            return ((if (st.percent) base * v / 100 else v) * 100).roundToLong() / 100.0
        }
        fun updatePreview() {
            val amount = amountOf(st.value)
            if (amount <= 0) {
                preview.visibility = View.GONE
                return
            }
            val pctAfter = if (t.list > 0) ((t.discount + amount) * 10000 / t.list).roundToLong() / 100.0 else 0.0
            val autoOk = st.limit >= pctAfter
            val (wBg, wFg) = IttcTheme.stageColors(a, "interested")
            val (gBg, gFg) = IttcTheme.stageColors(a, "admitted")
            preview.text = a.getString(
                R.string.ittc_c_disc_preview,
                IttcVisitFeeApi.money(amount), IttcVisitFeeApi.money(maxOf(t.list - t.discount - amount, 0.0)), pctText(pctAfter),
            ) + "\n" + if (autoOk) a.getString(R.string.ittc_c_disc_auto) else a.getString(R.string.ittc_c_disc_needs, pctText(st.limit))
            preview.background = IttcTheme.rounded(a, if (autoOk) gBg else wBg, 12f)
            preview.setTextColor(if (autoOk) gFg else wFg)
            preview.visibility = View.VISIBLE
        }
        value.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                st.value = s?.toString().orEmpty()
                updatePreview()
            }
        })
        updatePreview()

        // the reason
        body.addView(IttcCUi.section(a, a.getString(if (setting.reasonRequired) R.string.ittc_c_disc_reason_req else R.string.ittc_c_disc_reason)), IttcCUi.topMargin(a, 14))
        val reasonChips = setting.reasons.map { r ->
            IttcCUi.chip(a, r, st.reason == r) {
                st.reason = if (st.reason == r) null else r
                render(st)
            }
        }
        body.addView(IttcCUi.chipRow(a, reasonChips), IttcCUi.topMargin(a, 6))
        val note = IttcCUi.noteInput(a, a.getString(R.string.ittc_c_note_optional)).apply { setText(st.note) }
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                st.note = s?.toString().orEmpty()
            }
        })
        body.addView(note, IttcCUi.topMargin(a, 10))
        val err = IttcCUi.text(a, "", 13f, p.danger).apply { visibility = View.GONE }
        body.addView(err, IttcCUi.topMargin(a, 8))

        val row = IttcCUi.row(a)
        lateinit var save: TextView
        save = IttcCUi.button(a, a.getString(R.string.ittc_c_disc_send), IttcCUi.Style.PRIMARY) {
            val amount = amountOf(st.value)
            val v = st.value.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
            when {
                amount <= 0 -> showErr(err, a.getString(R.string.ittc_c_disc_type))
                amount > base -> showErr(err, a.getString(R.string.ittc_c_disc_too_much, IttcVisitFeeApi.money(base)))
                setting.reasonRequired && st.reason == null -> showErr(err, a.getString(R.string.ittc_c_disc_reason_needed))
                else -> send(st, save, err) {
                    val r = IttcVisitFeeApi.requestDiscount(a, n.id, st.feeId, if (st.percent) "percent" else "amount", v, st.reason, st.note)
                    a.getString(if (r.status == "approved") R.string.ittc_c_disc_done_approved else R.string.ittc_c_disc_done_sent, IttcVisitFeeApi.money(r.amount))
                }
            }
        }
        row.addView(save, IttcCUi.weight(a))
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_back), IttcCUi.Style.GHOST) { go(st, Mode.VIEW) }, IttcCUi.weight(a))
        body.addView(row, IttcCUi.topMargin(a, 12))
    }

    // ------------------------------------------------------------------ what was said
    private fun renderEvent(st: State, n: Negotiation) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_said_title), 16f, p.text, bold = true), IttcCUi.topMargin(a, 14))
        val kinds = listOf("lead_asked", "offer", "quoted", "note")
        val chips = kinds.map { k ->
            IttcCUi.chip(a, eventLabel(a, k), st.eventKind == k) {
                st.eventKind = k
                render(st)
            }
        }
        body.addView(IttcCUi.chipRow(a, chips), IttcCUi.topMargin(a, 10))
        val isNote = st.eventKind == "note"
        val amount = IttcCUi.input(a, a.getString(R.string.ittc_c_said_amount), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        if (!isNote) body.addView(amount, IttcCUi.topMargin(a, 12))
        val note = IttcCUi.noteInput(a, a.getString(if (isNote) R.string.ittc_c_said_note else R.string.ittc_c_said_note_optional))
        body.addView(note, IttcCUi.topMargin(a, 10))
        val err = IttcCUi.text(a, "", 13f, p.danger).apply { visibility = View.GONE }
        body.addView(err, IttcCUi.topMargin(a, 8))
        val row = IttcCUi.row(a)
        lateinit var save: TextView
        save = IttcCUi.button(a, a.getString(R.string.ittc_c_save), IttcCUi.Style.PRIMARY) {
            val v = amount.text?.toString().orEmpty().filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
            val text = note.text?.toString()?.trim().orEmpty()
            when {
                !isNote && v <= 0 -> showErr(err, a.getString(R.string.ittc_c_said_amount_needed))
                isNote && text.isEmpty() -> showErr(err, a.getString(R.string.ittc_c_said_note_needed))
                else -> send(st, save, err) {
                    IttcVisitFeeApi.addEvent(a, n.id, st.eventKind, if (isNote) null else v, text)
                    a.getString(R.string.ittc_c_saved)
                }
            }
        }
        row.addView(save, IttcCUi.weight(a))
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_back), IttcCUi.Style.GHOST) { go(st, Mode.VIEW) }, IttcCUi.weight(a))
        body.addView(row, IttcCUi.topMargin(a, 12))
    }

    private fun confirmTakeBack(st: State, d: IttcVisitFeeApi.Discount) {
        val a = st.activity
        IttcCUi.confirm(
            a, a.getString(R.string.ittc_c_disc_take_back_q),
            a.getString(R.string.ittc_c_disc_take_back_text, IttcVisitFeeApi.money(d.amount), d.feeName ?: a.getString(R.string.ittc_c_fee_whole)),
            a.getString(R.string.ittc_c_disc_take_back), a.getString(R.string.ittc_c_keep_it),
        ) {
            if (st.busy) return@confirm
            st.busy = true
            val app = a.applicationContext
            ensureBackgroundThread {
                val error = try {
                    IttcVisitFeeApi.cancelDiscount(app, d.id)
                    null
                } catch (t: Throwable) {
                    IttcCUi.errorText(app, t)
                }
                a.runOnUiThread {
                    st.busy = false
                    if (error != null) return@runOnUiThread a.toast(error)
                    st.changed = true
                    a.toast(a.getString(R.string.ittc_c_disc_taken_back))
                    if (!alive(st)) return@runOnUiThread
                    st.mode = Mode.VIEW
                    showLoading(st)
                    load(st)
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Send it off the main thread; on success a short message, then the sheet shows the new state */
    private fun send(st: State, button: TextView, err: TextView, work: () -> String) {
        if (st.busy) return
        st.busy = true
        IttcCUi.setBusy(button, true)
        err.visibility = View.GONE
        val app = st.activity.applicationContext
        ensureBackgroundThread {
            var message: String? = null
            val error = try {
                message = work()
                null
            } catch (t: Throwable) {
                IttcCUi.errorText(app, t)
            }
            st.activity.runOnUiThread {
                st.busy = false
                if (st.activity.isFinishing || st.activity.isDestroyed) return@runOnUiThread
                IttcCUi.setBusy(button, false)
                if (error != null) return@runOnUiThread showErr(err, error)
                st.changed = true
                message?.takeIf { it.isNotBlank() }?.let { st.activity.toast(it) }
                IttcDirect.refreshAsync(app, force = true, delayMs = 500)
                if (!alive(st)) return@runOnUiThread
                st.mode = Mode.VIEW
                showLoading(st)
                load(st)
            }
        }
    }

    private fun showErr(err: TextView, message: String) {
        err.text = message
        err.visibility = View.VISIBLE
    }

    private fun eventLabel(a: Activity, kind: String): String = a.getString(
        when (kind) {
            "quoted" -> R.string.ittc_c_said_quoted
            "lead_asked" -> R.string.ittc_c_said_asked
            "offer" -> R.string.ittc_c_said_offer
            else -> R.string.ittc_c_said_note_kind
        },
    )

    /** 10.0 → "10", 12.5 → "12.5" */
    private fun pctText(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
