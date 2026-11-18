package org.fossify.phone.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcVisitFeeApi
import org.fossify.phone.helpers.IttcTheme
import java.util.Calendar

/**
 * 🏫 Campus visit (ITTC Phone 3.4, direct mode) — exactly what the Tracker's VisitCard allows:
 * book a visit (quick days or a date + time, a note), see the planned / last visits, mark the
 * planned one Came / Did not come, Reschedule it (open leads) or Cancel it; "Came today" for a
 * walk-in. The server's plan_visit / finish_visit / cancel_visit check everything.
 */
object IttcVisitSheet {
    private enum class Mode { VIEW, PLAN, VISITED, MISSED }

    private class State(
        val activity: Activity,
        val leadId: String,
        var leadName: String?,
        val onDone: () -> Unit,
        val sheet: IttcCUi.Sheet,
    ) {
        var info: IttcVisitFeeApi.LeadInfo? = null
        var visits: List<IttcVisitFeeApi.Visit> = emptyList()
        var setting: IttcVisitFeeApi.VisitSetting? = null
        var mode = Mode.VIEW
        var pickedAt: Long? = null
        /** The note being typed (kept when the sheet is drawn again, e.g. after picking a day) */
        var note = ""
        var busy = false
        var changed = false
        val planned get() = visits.firstOrNull { it.status == "planned" }
    }

    fun show(activity: Activity, leadId: String, leadName: String?, onDone: () -> Unit) {
        if (leadId.isBlank() || activity.isFinishing || activity.isDestroyed) return
        try {
            val st = State(activity, leadId, leadName, onDone, IttcCUi.sheet(activity))
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

    private fun load(st: State) {
        val app = st.activity.applicationContext
        ensureBackgroundThread {
            val error: String? = try {
                val info = IttcVisitFeeApi.leadInfo(app, st.leadId)
                val visits = IttcVisitFeeApi.visits(app, st.leadId)
                val setting = IttcVisitFeeApi.visitSetting(app)
                st.activity.runOnUiThread {
                    st.info = info
                    st.visits = visits
                    st.setting = setting
                    if (st.leadName.isNullOrBlank()) st.leadName = info?.name
                }
                if (info == null) st.activity.getString(R.string.ittc_c_lead_not_found) else null
            } catch (t: Throwable) {
                IttcCUi.errorText(app, t)
            }
            st.activity.runOnUiThread {
                if (!alive(st)) return@runOnUiThread
                if (error != null) showError(st, error) else render(st)
            }
        }
    }

    private fun alive(st: State) = !st.activity.isFinishing && !st.activity.isDestroyed && st.sheet.dialog.isShowing

    // ------------------------------------------------------------------ views
    private fun title(st: State) {
        val p = IttcTheme.of(st.activity)
        val body = st.sheet.body
        body.addView(IttcCUi.text(st.activity, st.activity.getString(R.string.ittc_c_visit_title), 20f, p.text, bold = true))
        st.leadName?.takeIf { it.isNotBlank() }?.let {
            body.addView(IttcCUi.text(st.activity, it, 14f, p.muted), IttcCUi.topMargin(st.activity, 2))
        }
    }

    private fun showLoading(st: State) {
        st.sheet.body.removeAllViews()
        title(st)
        st.sheet.body.addView(
            IttcCUi.spinner(st.activity),
            LinearLayout.LayoutParams(IttcCUi.dp(st.activity, 28), IttcCUi.dp(st.activity, 28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = IttcCUi.dp(st.activity, 24)
                bottomMargin = IttcCUi.dp(st.activity, 24)
            },
        )
    }

    private fun showError(st: State, message: String) {
        val a = st.activity
        val p = IttcTheme.of(a)
        st.sheet.body.removeAllViews()
        title(st)
        st.sheet.body.addView(IttcCUi.text(a, message, 14f, p.danger), IttcCUi.topMargin(a, 16))
        val row = IttcCUi.row(a)
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_retry), IttcCUi.Style.OUTLINE) {
            showLoading(st)
            load(st)
        }, IttcCUi.weight(a))
        st.sheet.body.addView(row, IttcCUi.topMargin(a, 12))
    }

    private fun render(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        body.removeAllViews()
        title(st)
        val info = st.info ?: return
        val enabled = st.setting?.enabled != false
        val planned = st.planned

        when (st.mode) {
            Mode.PLAN -> return renderPlan(st)
            Mode.VISITED, Mode.MISSED -> return renderFinish(st)
            Mode.VIEW -> Unit
        }

        // the planned visit (or none)
        val box = IttcCUi.card(a).apply {
            background = IttcTheme.rounded(a, if (planned != null) p.primarySoft else p.surfaceAlt, 16f)
        }
        if (planned != null) {
            val at = planned.plannedAt
            val overdue = at != null && at < System.currentTimeMillis()
            box.addView(IttcCUi.header(a, a.getString(R.string.ittc_c_visit_upcoming), a.getString(R.string.ittc_c_visit_planned), p.surface, p.primary))
            box.addView(
                IttcCUi.text(
                    a,
                    (at?.let { "📅 " + IttcCUi.whenText(it) } ?: a.getString(R.string.ittc_c_visit_no_date)) +
                        if (overdue) " · " + a.getString(R.string.ittc_c_overdue) else "",
                    16f, if (overdue) p.danger else p.text, bold = true,
                ),
                IttcCUi.topMargin(a, 8),
            )
            planned.note?.let { box.addView(IttcCUi.text(a, it, 14f, p.text2), IttcCUi.topMargin(a, 4)) }
            planned.by?.let { box.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_by, it), 12f, p.muted), IttcCUi.topMargin(a, 4)) }
        } else {
            box.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_visit_not_planned), 15f, p.text, bold = true))
            val hint = when {
                !info.isOpen -> a.getString(R.string.ittc_c_visit_closed_hint)
                !enabled -> a.getString(R.string.ittc_c_visit_off_hint)
                else -> a.getString(R.string.ittc_c_visit_none_hint)
            }
            box.addView(IttcCUi.text(a, hint, 13f, p.muted), IttcCUi.topMargin(a, 4))
        }
        body.addView(box, IttcCUi.topMargin(a, 14))

        // actions (as the Tracker: Came / Did not come and Cancel for a planned visit; Reschedule
        // only for an open lead; Book / Came today for an open lead when visits are on)
        if (planned != null) {
            val r1 = IttcCUi.row(a)
            r1.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_visit_came), IttcCUi.Style.CALL) { go(st, Mode.VISITED) }, IttcCUi.weight(a))
            r1.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_visit_not_came), IttcCUi.Style.DANGER) { go(st, Mode.MISSED) }, IttcCUi.weight(a))
            body.addView(r1, IttcCUi.topMargin(a, 12))
            val r2 = IttcCUi.row(a)
            if (info.isOpen) {
                r2.addView(
                    IttcCUi.button(
                        a, a.getString(if (planned.plannedAt != null) R.string.ittc_c_visit_reschedule else R.string.ittc_c_visit_set_date),
                        IttcCUi.Style.OUTLINE,
                    ) { go(st, Mode.PLAN) },
                    IttcCUi.weight(a),
                )
            }
            r2.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_visit_cancel), IttcCUi.Style.OUTLINE) { confirmCancel(st) }, IttcCUi.weight(a))
            body.addView(r2, IttcCUi.topMargin(a, 8))
        } else if (info.isOpen && enabled) {
            val r = IttcCUi.row(a)
            r.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_visit_book), IttcCUi.Style.PRIMARY) { go(st, Mode.PLAN) }, IttcCUi.weight(a))
            r.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_visit_came_today), IttcCUi.Style.OUTLINE) { go(st, Mode.VISITED) }, IttcCUi.weight(a))
            body.addView(r, IttcCUi.topMargin(a, 12))
        }

        // earlier visits
        val past = st.visits.filter { it.status != "planned" }
        if (past.isNotEmpty()) {
            body.addView(IttcCUi.section(a, a.getString(R.string.ittc_c_visit_earlier)), IttcCUi.topMargin(a, 20))
            past.take(6).forEach { v ->
                val (label, bg, fg) = IttcCUi.visitStatus(a, v.status)
                val line = IttcCUi.row(a).apply { gravity = Gravity.TOP }
                line.addView(IttcCUi.pill(a, label, bg, fg), IttcCUi.wrap(a, 10))
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                val at = if (v.status == "visited") v.visitedAt else v.plannedAt
                val meta = listOfNotNull(at?.let { IttcCUi.whenText(it) } ?: a.getString(R.string.ittc_c_visit_no_date), v.by).joinToString(" · ")
                col.addView(IttcCUi.text(a, meta, 13f, p.text2))
                v.note?.let { col.addView(IttcCUi.text(a, it, 13f, p.text).apply { maxLines = 3 }) }
                line.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(line, IttcCUi.topMargin(a, 10))
            }
        }
    }

    /** The note field shows [State.note] and keeps it up to date */
    private fun keepNote(st: State, input: android.widget.EditText): android.widget.EditText {
        input.setText(st.note)
        input.setSelection(input.text?.length ?: 0)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                st.note = e?.toString().orEmpty()
            }
        })
        return input
    }

    private fun go(st: State, mode: Mode) {
        if (st.busy) return
        if (st.mode != mode) st.note = ""
        st.mode = mode
        st.pickedAt = null
        render(st)
    }

    // ------------------------------------------------------------------ book / reschedule
    private fun renderPlan(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        val planned = st.planned
        body.addView(
            IttcCUi.text(a, a.getString(if (planned?.plannedAt != null) R.string.ittc_c_visit_change_title else R.string.ittc_c_visit_plan_title), 16f, p.text, bold = true),
            IttcCUi.topMargin(a, 14),
        )
        planned?.plannedAt?.let { body.addView(IttcCUi.text(a, a.getString(R.string.ittc_c_visit_now, IttcCUi.whenText(it)), 13f, p.muted), IttcCUi.topMargin(a, 2)) }

        body.addView(IttcCUi.section(a, a.getString(R.string.ittc_c_visit_when)), IttcCUi.topMargin(a, 14))
        // quick days at 11 am (as the Tracker); a pick changes the chips and the date button in place
        // (the note keeps its text and focus)
        val quick = ArrayList<Pair<Long, TextView>>()
        lateinit var pick: TextView
        fun showPicked() {
            val at = st.pickedAt
            quick.forEach { (time, chip) -> IttcCUi.setChipOn(chip, time == at) }
            pick.text = at?.let { "📅 " + IttcCUi.whenText(it) } ?: a.getString(R.string.ittc_c_visit_pick)
            IttcCUi.setStyle(pick, if (at != null) IttcCUi.Style.PRIMARY else IttcCUi.Style.OUTLINE)
        }
        listOf(1 to R.string.ittc_c_visit_q_tomorrow, 2 to R.string.ittc_c_visit_q_2days, 7 to R.string.ittc_c_visit_q_week).forEach { (days, label) ->
            val at = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, days)
                set(Calendar.HOUR_OF_DAY, 11)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            quick += at to IttcCUi.chip(a, a.getString(label), st.pickedAt == at) {
                st.pickedAt = at
                showPicked()
            }
        }
        body.addView(IttcCUi.chipRow(a, quick.map { it.second }), IttcCUi.topMargin(a, 8))
        pick = IttcCUi.button(a, "", IttcCUi.Style.OUTLINE) {
            pickDateTime(a, st.pickedAt ?: planned?.plannedAt) { at ->
                st.pickedAt = at
                if (alive(st)) showPicked()
            }
        }
        showPicked()
        body.addView(pick, IttcCUi.topMargin(a, 10))

        val note = keepNote(st, IttcCUi.noteInput(a, a.getString(R.string.ittc_c_visit_note_hint)))
        body.addView(note, IttcCUi.topMargin(a, 12))
        val err = IttcCUi.text(a, "", 13f, p.danger).apply { visibility = View.GONE }
        body.addView(err, IttcCUi.topMargin(a, 8))

        val row = IttcCUi.row(a)
        lateinit var save: TextView
        save = IttcCUi.button(a, a.getString(R.string.ittc_c_save), IttcCUi.Style.PRIMARY) {
            val at = st.pickedAt
            when {
                at == null -> showErr(err, a.getString(R.string.ittc_c_visit_pick_needed))
                at < System.currentTimeMillis() - 5 * 60_000 -> showErr(err, a.getString(R.string.ittc_c_visit_future))
                else -> send(st, save, err, {
                    IttcVisitFeeApi.planVisit(a, st.leadId, at, note.text?.toString())
                    a.getString(if (planned != null) R.string.ittc_c_visit_moved_to else R.string.ittc_c_visit_planned_for, IttcCUi.whenText(at))
                })
            }
        }
        row.addView(save, IttcCUi.weight(a))
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_back), IttcCUi.Style.GHOST) { go(st, Mode.VIEW) }, IttcCUi.weight(a))
        body.addView(row, IttcCUi.topMargin(a, 12))
    }

    // ------------------------------------------------------------------ came / did not come
    private fun renderFinish(st: State) {
        val a = st.activity
        val p = IttcTheme.of(a)
        val body = st.sheet.body
        val visited = st.mode == Mode.VISITED
        val walkIn = st.planned == null
        body.addView(
            IttcCUi.text(
                a,
                a.getString(if (visited) (if (walkIn) R.string.ittc_c_visit_came_today_title else R.string.ittc_c_visit_came_title) else R.string.ittc_c_visit_missed_title),
                16f, p.text, bold = true,
            ),
            IttcCUi.topMargin(a, 14),
        )
        body.addView(
            IttcCUi.text(a, a.getString(if (visited) R.string.ittc_c_visit_came_hint else R.string.ittc_c_visit_missed_hint), 13f, p.muted),
            IttcCUi.topMargin(a, 4),
        )
        val note = keepNote(st, IttcCUi.noteInput(a, a.getString(if (visited) R.string.ittc_c_visit_came_note_hint else R.string.ittc_c_visit_missed_note_hint)))
        body.addView(note, IttcCUi.topMargin(a, 12))
        val err = IttcCUi.text(a, "", 13f, p.danger).apply { visibility = View.GONE }
        body.addView(err, IttcCUi.topMargin(a, 8))
        val row = IttcCUi.row(a)
        lateinit var save: TextView
        save = IttcCUi.button(a, a.getString(if (visited) R.string.ittc_c_visit_save_visit else R.string.ittc_c_save), if (visited) IttcCUi.Style.CALL else IttcCUi.Style.PRIMARY) {
            send(st, save, err, {
                val r = IttcVisitFeeApi.finishVisit(a, st.leadId, if (visited) "visited" else "missed", note.text?.toString())
                val parts = arrayListOf(a.getString(if (visited) R.string.ittc_c_visit_saved else R.string.ittc_c_visit_marked_missed))
                r.movedTo?.let { parts += a.getString(R.string.ittc_c_moved_to, it) }
                r.followUpAt?.let { parts += a.getString(R.string.ittc_c_next_call, IttcCUi.whenText(it)) }
                parts.joinToString(" ")
            })
        }
        row.addView(save, IttcCUi.weight(a))
        row.addView(IttcCUi.button(a, a.getString(R.string.ittc_c_back), IttcCUi.Style.GHOST) { go(st, Mode.VIEW) }, IttcCUi.weight(a))
        body.addView(row, IttcCUi.topMargin(a, 12))
    }

    private fun confirmCancel(st: State) {
        val a = st.activity
        IttcCUi.confirm(
            a, a.getString(R.string.ittc_c_visit_cancel_q), a.getString(R.string.ittc_c_visit_cancel_text),
            a.getString(R.string.ittc_c_visit_cancel), a.getString(R.string.ittc_c_keep_it),
        ) {
            if (st.busy) return@confirm
            st.busy = true
            val app = a.applicationContext
            ensureBackgroundThread {
                val error = try {
                    IttcVisitFeeApi.cancelVisit(app, st.leadId)
                    null
                } catch (t: Throwable) {
                    IttcCUi.errorText(app, t)
                }
                a.runOnUiThread {
                    st.busy = false
                    if (error != null) {
                        a.toast(error)
                    } else {
                        finished(st, a.getString(R.string.ittc_c_visit_cancelled_msg))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Send it off the main thread; on success a short message and the sheet closes */
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
                if (error != null) {
                    showErr(err, error)
                } else {
                    finished(st, message.orEmpty())
                }
            }
        }
    }

    private fun finished(st: State, message: String) {
        st.changed = true
        if (message.isNotBlank()) st.activity.toast(message)
        // (the stage / next call may have moved: the kept lists come again)
        IttcDirect.refreshAsync(st.activity.applicationContext, force = true, delayMs = 500)
        try {
            if (st.sheet.dialog.isShowing) st.sheet.dialog.dismiss() else safe(st.onDone)
        } catch (_: Throwable) {
        }
    }

    private fun showErr(err: TextView, message: String) {
        err.text = message
        err.visibility = View.VISIBLE
    }

    /** A date, then a time (today onwards) */
    private fun pickDateTime(a: Activity, initial: Long?, done: (Long) -> Unit) {
        if (a.isFinishing || a.isDestroyed) return
        val c = Calendar.getInstance().apply { initial?.let { timeInMillis = it } }
        try {
            DatePickerDialog(a, { _, y, mo, d ->
                TimePickerDialog(a, { _, h, mi ->
                    val at = Calendar.getInstance().apply {
                        set(y, mo, d, h, mi, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    done(at)
                }, if (initial != null) c.get(Calendar.HOUR_OF_DAY) else 11, if (initial != null) c.get(Calendar.MINUTE) else 0, false).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        } catch (_: Throwable) {
        }
    }
}
