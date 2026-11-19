package org.fossify.phone.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcStageMove
import org.fossify.phone.helpers.IttcTheme
import java.util.Calendar

/**
 * 🧭 ITTC 3.9: "Move Rahim?" — asked before the Leads board moves a lead to a stage that needs more
 * than a drop: the lead, from → to, then what the stage asks for (the same rules as the lead page's
 * stage change: a note — with the admin's quick notes as reason chips — and / or a date), Cancel /
 * Move. A stage that closes the lead says so. Admitted: the admit form instead of a bare move.
 */
internal object IttcStageMoveSheet {
    /**
     * [fromName]: the lead's stage now (null: none). [onMove] gets the note (null: none) and the date
     * (null: none) once the stage's rules are met; [onAdmit]: the admit form was asked for.
     */
    fun show(
        activity: Activity,
        l: IttcBridge.PhoneLead,
        fromName: String?,
        to: IttcBridge.Stage,
        presets: List<String>,
        onMove: (note: String?, due: Long?) -> Unit,
        onAdmit: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val c = activity
        val p = IttcTheme.of(c)
        val sheet = IttcCUi.sheet(c)
        val body = sheet.body
        val title = l.name.ifEmpty { l.callNumber.orEmpty() }

        // 🟦 the lead: avatar + "Move Rahim?"
        val head = IttcCUi.row(c)
        val (avBg, avFg) = IttcTheme.avatarColors(c, title)
        head.addView(
            IttcCUi.text(c, IttcTheme.initials(l.name), 15f, avFg, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(avBg)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(IttcCUi.dp(c, 44), IttcCUi.dp(c, 44)),
        )
        head.addView(
            IttcCUi.text(c, c.getString(R.string.ittc_bd_move_title, title), 18f, p.text, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcCUi.dp(c, 12) },
        )
        body.addView(head)

        // from → to (the stages' own colours)
        val path = IttcCUi.row(c).apply {
            contentDescription = c.getString(R.string.ittc_bd_from_to, fromName ?: "—", to.name)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val (fBg, fFg) = IttcTheme.stageColors(c, fromName)
        path.addView(
            IttcCUi.pill(c, fromName ?: "—", fBg, fFg).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f),
        )
        path.addView(
            IttcCUi.text(c, "→", 16f, p.muted, bold = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = IttcCUi.dp(c, 8)
                marginEnd = IttcCUi.dp(c, 8)
            },
        )
        val (tBg, tFg) = IttcTheme.stageColors(c, to.name)
        path.addView(
            IttcCUi.pill(c, to.name, tBg, tFg, tFg).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
        )
        body.addView(path, IttcCUi.topMargin(c, 14))

        val buttons = IttcCUi.row(c)
        val cancel = IttcCUi.button(c, c.getString(R.string.cancel), IttcCUi.Style.OUTLINE) { dismiss(sheet) }

        // 🎓 Admitted: the admit form (course, batch, fee, student ID) — as the lead page's "Admit"
        if (IttcStageMove.isAdmitted(to.name)) {
            body.addView(IttcCUi.text(c, c.getString(R.string.ittc_bd_admit_text), 14f, p.text2), IttcCUi.topMargin(c, 14))
            buttons.addView(cancel, IttcCUi.weight(c))
            buttons.addView(
                IttcCUi.button(c, c.getString(R.string.ittc_bd_open_admit), IttcCUi.Style.CALL) {
                    dismiss(sheet)
                    onAdmit()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f),
            )
            body.addView(buttons, IttcCUi.topMargin(c, 18))
            IttcCUi.showSafely(c, sheet.dialog)
            return
        }

        val closes = IttcStageMove.isClosed(to.name)
        if (closes) body.addView(IttcCUi.text(c, c.getString(R.string.ittc_bd_closed_text), 13f, p.muted), IttcCUi.topMargin(c, 12))

        // 📝 a note (the stage asks for one, or it closes the lead): the quick notes as reasons
        val wantsNote = to.noteRequired || closes
        val input = if (!wantsNote) null else IttcCUi.noteInput(
            c, c.getString(if (to.noteRequired) R.string.ittc_lp_note_hint else R.string.ittc_lp_note_optional),
        )
        if (input != null) {
            if (presets.isNotEmpty()) {
                body.addView(IttcCUi.section(c, c.getString(R.string.ittc_bd_reason)), IttcCUi.topMargin(c, 16))
                val chips = presets.map { preset ->
                    IttcCUi.chip(c, preset, false) {
                        // (a tap adds it, as the lead page's note does)
                        val now = input.text?.toString()?.trim().orEmpty()
                        val next = if (now.isEmpty()) preset else "$now. $preset"
                        input.setText(next)
                        input.setSelection(next.length)
                    }.apply { maxLines = 1 }
                }
                body.addView(IttcCUi.chipRow(c, chips), IttcCUi.topMargin(c, 8))
            }
            if (to.noteRequired) {
                body.addView(IttcCUi.text(c, c.getString(R.string.ittc_bd_note_required), 12f, p.muted), IttcCUi.topMargin(c, 12))
            }
            body.addView(input, IttcCUi.topMargin(c, if (to.noteRequired) 4 else 12))
        }

        // 📅 the stage's date ("Visit date"), as the lead page asks for it
        var due: Long? = null
        val dateName = (to.dateLabel ?: "date & time").lowercase()
        if (to.askDate) {
            val dateButton = IttcCUi.button(c, c.getString(R.string.ittc_bd_pick_date, dateName), IttcCUi.Style.OUTLINE) {}
            dateButton.setOnClickListener {
                pickDateTime(c, due) { at ->
                    due = at
                    dateButton.text = "📅 " + IttcCUi.whenText(at)
                }
            }
            body.addView(dateButton, IttcCUi.topMargin(c, 12))
        }

        buttons.addView(cancel, IttcCUi.weight(c))
        buttons.addView(
            IttcCUi.button(c, c.getString(R.string.ittc_bd_move), if (closes) IttcCUi.Style.DANGER else IttcCUi.Style.PRIMARY) {
                val note = input?.text?.toString()?.trim().orEmpty()
                if (to.noteRequired && note.isEmpty()) {
                    c.toast(R.string.ittc_lp_note_empty)
                    input?.requestFocus()
                    return@button
                }
                val at = due
                if (to.askDate && at == null) {
                    c.toast(c.getString(R.string.ittc_bd_date_first, dateName))
                    return@button
                }
                dismiss(sheet)
                onMove(note.takeIf { it.isNotEmpty() }, at)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        body.addView(buttons, IttcCUi.topMargin(c, 18))
        IttcCUi.showSafely(c, sheet.dialog)
    }

    private fun dismiss(sheet: IttcCUi.Sheet) {
        try {
            sheet.dialog.dismiss()
        } catch (_: Throwable) {
        }
    }

    /** A date, then a time (only later than now) — the lead page's picker */
    private fun pickDateTime(activity: Activity, initial: Long?, done: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { initial?.let { timeInMillis = it } }
        try {
            DatePickerDialog(activity, { _, y, mo, d ->
                TimePickerDialog(activity, { _, h, mi ->
                    val at = Calendar.getInstance().apply {
                        set(y, mo, d, h, mi, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    if (at > System.currentTimeMillis()) done(at) else activity.toast(R.string.ittc_a_in_past)
                }, if (initial != null) cal.get(Calendar.HOUR_OF_DAY) else 10, if (initial != null) cal.get(Calendar.MINUTE) else 0, false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        } catch (_: Throwable) {
        }
    }
}
