package org.fossify.phone.activities

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcAdmitApi
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * 💵 3.5: an admitted student's payments — the fee, paid and due, the installment plan, the
 * receipts, and "Take payment" (the Tracker's record_payment: amount, method, transaction ID, the
 * college's receipt no., date, note; the receipt number comes from the server). Each receipt can
 * be shared. Before admission the sheet shows the fee and that payments come after admitting.
 */
object IttcPaymentSheet {
    private class Data(
        val lead: IttcAdmitApi.LeadState,
        val settings: IttcAdmitApi.Settings,
        val may: IttcAdmitApi.May,
        val pays: IttcAdmitApi.Payments?,
    )

    fun show(activity: Activity, leadId: String, onDone: () -> Unit) {
        try {
            if (!IttcDirect.isActive(activity)) return
            var changed = false
            val dialog = IttcDUi.sheet(activity) { column, dialog ->
                column.addView(IttcDUi.title(activity, activity.getString(R.string.ittc_d_payments_title)))
                val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(body, IttcDUi.lp(activity, top = 4f))
                /** [notice]: a message shown on top of the fresh list */
                fun load(notice: String? = null) {
                    body.removeAllViews()
                    body.addView(IttcDUi.progress(activity), IttcDUi.lp(activity, top = 24f, width = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    val app = activity.applicationContext
                    ensureBackgroundThread {
                        val r = try {
                            val lead = IttcAdmitApi.lead(app, leadId)
                            Result.success(
                                lead?.let { Data(it, IttcAdmitApi.settings(app), IttcAdmitApi.may(app), if (it.admitted || it.admittedAt != null) IttcAdmitApi.payments(app, leadId) else null) },
                            )
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                        activity.runOnUiThread {
                            if (!IttcDUi.alive(activity) || !dialog.isShowing) return@runOnUiThread
                            try {
                                body.removeAllViews()
                                val d = r.getOrNull()
                                if (notice != null) {
                                    body.addView(IttcDUi.errorBox(activity).also { IttcDUi.showError(it, notice) })
                                }
                                if (d == null) {
                                    body.addView(IttcDUi.hint(activity, IttcAdmitApi.errorText(r.exceptionOrNull() ?: Exception(), activity.getString(R.string.ittc_d_not_found))))
                                } else {
                                    fill(activity, body, d, dialog, reload = { load(it) }) {
                                        changed = true
                                        load()
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                load()
            }
            dialog.setOnDismissListener { if (changed) onDone() }
            dialog.show()
        } catch (_: Throwable) {
        }
    }

    private fun fill(activity: Activity, body: LinearLayout, d: Data, dialog: android.app.Dialog, reload: (String) -> Unit, saved: () -> Unit) {
        val p = IttcTheme.of(activity)
        val lead = d.lead
        body.addView(IttcDUi.hint(activity, listOfNotNull(lead.name, lead.studentRef?.let { activity.getString(R.string.ittc_d_student_id, it) }).joinToString(" · ")))

        // a cancelled admission still shows its payments / refunds (no new ones)
        val cancelledWithPays = !lead.admitted && d.pays?.payments?.isNotEmpty() == true
        if (!lead.admitted && !cancelledWithPays) {
            val box = IttcDUi.box(activity)
            val final = lead.feeFinal
            box.addView(IttcDUi.text(activity, if (final != null && final > 0) activity.getString(R.string.ittc_d_fee_is, IttcAdmitApi.money(final)) else activity.getString(R.string.ittc_d_fee_unknown), 16f, p.text, bold = true))
            if ((lead.feeFull ?: 0.0) > (final ?: 0.0) && final != null) {
                box.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_fee_from, IttcAdmitApi.money(lead.feeFull ?: 0.0), IttcAdmitApi.money((lead.feeFull ?: 0.0) - final))))
            }
            box.addView(IttcDUi.text(activity, activity.getString(R.string.ittc_d_pay_after_admit), 13f, p.text2), IttcDUi.lp(activity, top = 6f))
            body.addView(box, IttcDUi.lp(activity, top = 12f))
            if (lead.isOpen && d.may.admit) {
                body.addView(IttcDUi.button(activity, activity.getString(R.string.ittc_d_admit_btn), IttcDUi.Btn.PRIMARY) {
                    dialog.dismiss()
                    IttcAdmitActivity.open(activity, lead.id)
                }, IttcDUi.lp(activity, top = 12f))
            }
            return
        }

        val pays = d.pays ?: IttcAdmitApi.Payments(emptyList(), emptyList())
        val final = lead.feeFinal ?: 0.0
        val paid = pays.paid
        val due = maxOf(final - paid, 0.0)
        if (!d.settings.payOn && pays.payments.isEmpty()) {
            body.addView(IttcDUi.text(activity, activity.getString(R.string.ittc_d_payments_off), 14f, p.text2), IttcDUi.lp(activity, top = 12f))
            return
        }

        // ---- paid / due
        val sum = IttcDUi.box(activity)
        val nums = IttcDUi.row(activity)
        fun stat(label: String, value: String, color: Int) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(IttcDUi.hint(activity, label))
            addView(IttcDUi.text(activity, value, 17f, color, bold = true))
        }
        nums.addView(stat(activity.getString(R.string.ittc_d_fee), IttcAdmitApi.money(final), p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nums.addView(stat(activity.getString(R.string.ittc_d_paid), IttcAdmitApi.money(paid), p.call), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nums.addView(
            stat(activity.getString(R.string.ittc_d_due), if (due > 0) IttcAdmitApi.money(due) else activity.getString(R.string.ittc_d_paid_in_full), if (due > 0) p.danger else p.call),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        sum.addView(nums)
        sum.addView(IttcDUi.bar(activity, if (final > 0) paid / final else 0.0))
        body.addView(sum, IttcDUi.lp(activity, top = 12f))

        // ---- installments
        val plan = pays.states()
        if (plan.isNotEmpty()) {
            body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_installments)), IttcDUi.lp(activity, top = 14f))
            plan.forEach { s ->
                val row = IttcDUi.row(activity)
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(IttcDUi.text(activity, "${s.item.seq}. ${IttcAdmitApi.dayOf(s.item.dueDate)}", 13f, p.text2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(IttcDUi.text(activity, IttcAdmitApi.money(s.item.amount), 13f, p.text, bold = true))
                val (label, color) = when (s.state) {
                    "paid" -> activity.getString(R.string.ittc_d_inst_paid) to p.call
                    "part" -> activity.getString(R.string.ittc_d_inst_part) to p.primary
                    "late" -> activity.getString(R.string.ittc_d_inst_late) to p.danger
                    else -> activity.getString(R.string.ittc_d_inst_due) to p.muted
                }
                row.addView(IttcDUi.text(activity, label, 12f, color, bold = true), IttcDUi.lp(activity, start = 10f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
                body.addView(row, IttcDUi.lp(activity, top = 4f))
            }
        }

        // ---- take a payment (the people the Tracker lets; the server checks again)
        if (lead.admitted && d.may.pay && d.settings.payOn && due > 0) {
            body.addView(IttcDUi.divider(activity), IttcDUi.lp(activity, top = 14f))
            body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_take_payment)), IttcDUi.lp(activity, top = 12f))
            val next = plan.firstOrNull { it.state != "paid" }?.let { it.item.amount - it.covered }
            IttcDPayForm(activity, d.settings, due, next, admitted = true).also { form ->
                form.addTo(body)
                val error = IttcDUi.errorBox(activity)
                body.addView(error)
                var busy = false
                val save = IttcDUi.button(activity, activity.getString(R.string.ittc_d_save_payment), IttcDUi.Btn.CALL) {}
                save.setOnClickListener {
                    if (busy) return@setOnClickListener
                    val v = form.validate(due)
                    if (v != null) return@setOnClickListener IttcDUi.showError(error, v)
                    IttcDUi.showError(error, null)
                    busy = true
                    save.alpha = 0.5f
                    val app = activity.applicationContext
                    val amountNow = form.amount()
                    // the payments there before this attempt (the list on screen)
                    val knownIds = pays.payments.map { it.id }.toSet()
                    var notConfirmed = false
                    ensureBackgroundThread {
                        val r: Result<IttcAdmitApi.PayResult> = try {
                            Result.success(
                                IttcAdmitApi.recordPayment(app, lead.id, amountNow, form.method!!, form.txn(), form.dayMs(), form.note(), form.college()),
                            )
                        } catch (e: Throwable) {
                            if (e is IttcApi.ApiError && e.status == 0) {
                                // The reply was lost: it may be saved. Look before letting it be sent again.
                                try {
                                    val found = IttcAdmitApi.findJustRecorded(app, lead.id, amountNow, knownIds)
                                    if (found != null) {
                                        Result.success(found)
                                    } else {
                                        notConfirmed = true
                                        Result.failure(IllegalStateException(app.getString(R.string.ittc_d_pay_not_confirmed)))
                                    }
                                } catch (e2: Throwable) {
                                    if (e2 is IttcApi.ApiError && e2.status == 0) {
                                        Result.failure(e) // still no internet: the original error
                                    } else {
                                        notConfirmed = true
                                        Result.failure(IllegalStateException(app.getString(R.string.ittc_d_pay_not_confirmed)))
                                    }
                                }
                            } else {
                                Result.failure(e)
                            }
                        }
                        activity.runOnUiThread {
                            busy = false
                            save.alpha = 1f
                            if (!IttcDUi.alive(activity)) return@runOnUiThread
                            r.onFailure {
                                if (notConfirmed) {
                                    // it may be in: show the list as it is now, the message on top
                                    reload(activity.getString(R.string.ittc_d_pay_not_confirmed))
                                } else {
                                    IttcDUi.showError(error, IttcAdmitApi.errorText(it, activity.getString(R.string.ittc_d_could_not_save)))
                                }
                            }
                            r.onSuccess { res ->
                                val amount = IttcAdmitApi.money(amountNow)
                                activity.toast(activity.getString(R.string.ittc_d_payment_saved, amount, res.receiptNo))
                                // Offer the receipt straight away (as the Tracker)
                                try {
                                    AlertDialog.Builder(activity)
                                        .setTitle(R.string.ittc_d_payment_saved_title)
                                        .setMessage("${res.receiptNo} · $amount")
                                        .setPositiveButton(R.string.ittc_d_share_receipt) { _, _ -> IttcReceipt.shareById(activity, lead.id, res.id, res.receiptNo) }
                                        .setNegativeButton(R.string.ittc_d_done, null)
                                        .show()
                                } catch (_: Throwable) {
                                }
                                saved()
                            }
                        }
                    }
                }
                body.addView(save, IttcDUi.lp(activity, top = 12f))
            }
        } else if (lead.admitted && due > 0 && !d.may.pay) {
            body.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_cant_take)), IttcDUi.lp(activity, top = 12f))
        }

        // ---- receipts
        if (pays.payments.isNotEmpty()) {
            body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_receipts)), IttcDUi.lp(activity, top = 16f))
            pays.payments.take(8).forEach { pay ->
                val row = IttcDUi.row(activity)
                row.gravity = Gravity.CENTER_VERTICAL
                val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val head = (if (pay.refund) "↩ " else "🧾 ") + "${pay.receiptNo} · ${IttcAdmitApi.money(pay.amount)}"
                col.addView(IttcDUi.text(activity, head, 14f, if (pay.voided) p.muted else p.text, bold = true).apply {
                    if (pay.voided) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                })
                val meta = listOfNotNull(
                    IttcAdmitApi.day(pay.paidAt), pay.method.takeIf { it.isNotBlank() }, pay.txnId,
                    pay.collegeNo?.let { activity.getString(R.string.ittc_d_college_no_short, it) },
                    if (pay.voided) activity.getString(R.string.ittc_d_void) else null,
                ).joinToString(" · ")
                col.addView(IttcDUi.hint(activity, meta))
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(IttcDUi.button(activity, activity.getString(R.string.ittc_d_share), IttcDUi.Btn.SOFT, small = true) {
                    IttcReceipt.share(activity, lead.id, pay, pays)
                })
                body.addView(row, IttcDUi.lp(activity, top = 8f))
            }
        }
    }
}

/**
 * A payment form (amount, method, transaction ID, college receipt no., date, note) with the
 * Tracker's checks. Used by the Payments sheet and by Admit (the first payment).
 */
internal class IttcDPayForm(
    private val activity: Activity,
    private val settings: IttcAdmitApi.Settings,
    due: Double,
    next: Double?,
    private val admitted: Boolean,
) {
    var method: String? = settings.methods.firstOrNull()?.name
    private var dayMs: Long? = null
    private val p = IttcTheme.of(activity)
    val amountInput = IttcDUi.input(activity, activity.getString(R.string.ittc_d_amount_hint, IttcAdmitApi.money(due)), numeric = true)
    private val methods = IttcDFlow(activity)
    private val txnInput = IttcDUi.input(activity, activity.getString(R.string.ittc_d_txn_hint), caps = true)
    private val collegeInput = IttcDUi.input(
        activity,
        activity.getString(if (settings.collegeReceipt == "required") R.string.ittc_d_college_required else R.string.ittc_d_college_optional),
        caps = true,
    )
    private val dateButton = IttcDUi.button(activity, "", IttcDUi.Btn.SOFT, small = true) { pickDay() }
    private val dateHint = IttcDUi.hint(activity, activity.getString(R.string.ittc_d_backdate_hint, settings.backdateDays))
    private val noteInput = IttcDUi.input(activity, activity.getString(R.string.ittc_d_note_optional), multiline = true)

    init {
        next?.takeIf { it > 0 && it <= due }?.let { amountInput.setText(Math.round(it).toString()) }
        drawMethods()
        showDay()
    }

    /** Out of the box it was in (the Admit screen draws its fee part again) */
    fun detach() {
        listOf(amountInput, methods, txnInput, collegeInput, dateButton, dateHint, noteInput).forEach { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    fun addTo(parent: LinearLayout, withDate: Boolean = true, withNote: Boolean = true) {
        detach()
        parent.addView(amountInput)
        parent.addView(methods, IttcDUi.lp(activity, top = 8f))
        parent.addView(txnInput)
        if (settings.collegeReceipt != "off") parent.addView(collegeInput)
        if (withDate) {
            parent.addView(dateButton, IttcDUi.lp(activity, top = 8f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.addView(dateHint, IttcDUi.lp(activity, top = 2f))
        }
        if (withNote) parent.addView(noteInput)
        txnInput.visibility = if (methodInfo()?.txn == true) View.VISIBLE else View.GONE
    }

    private fun methodInfo() = settings.methods.firstOrNull { it.name == method }

    private fun drawMethods() {
        methods.removeAllViews()
        settings.methods.forEach { m ->
            methods.addView(IttcDUi.chip(activity, m.name, m.name == method) {
                method = m.name
                drawMethods()
                txnInput.visibility = if (m.txn) View.VISIBLE else View.GONE
            })
        }
    }

    private fun showDay() {
        val d = dayMs
        dateButton.text = if (d == null) activity.getString(R.string.ittc_d_paid_today) else "📅 " + IttcAdmitApi.day(d)
    }

    private fun pickDay() {
        val c = Calendar.getInstance().apply { dayMs?.let { timeInMillis = it } }
        try {
            DatePickerDialog(activity, { _, y, m, day ->
                val ms = Calendar.getInstance().apply { set(y, m, day, 12, 0, 0) }.timeInMillis
                dayMs = if (IttcAdmitApi.isToday(ms)) null else ms
                showDay()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                val now = System.currentTimeMillis()
                datePicker.maxDate = now
                // (an admin may go further back on the Tracker; here: as far as the setting allows)
                datePicker.minDate = now - settings.backdateDays.coerceAtLeast(0) * 24L * 60 * 60 * 1000
            }.show()
        } catch (_: Throwable) {
        }
    }

    /** (Bengali digits ০-৯ are read as 0-9) */
    fun amount(): Double = IttcAdmitApi.asciiDigits(amountInput.text.toString()).replace(NOT_NUMBER, "").toDoubleOrNull() ?: 0.0
    fun txn(): String? = if (methodInfo()?.txn == true) txnInput.text.toString().trim().ifEmpty { null } else null
    fun college(): String? = if (settings.collegeReceipt == "off") null else collegeInput.text.toString().trim().ifEmpty { null }
    fun note(): String? = noteInput.text.toString().trim().ifEmpty { null }
    fun dayMs(): Long? = dayMs

    private companion object {
        val NOT_NUMBER = Regex("[^0-9.]")
    }

    /** The Tracker's checks; null = fine */
    fun validate(due: Double, least: Double = 0.0): String? {
        val v = amount()
        return when {
            least > 0 && v < least -> activity.getString(R.string.ittc_d_err_least, IttcAdmitApi.money(least))
            !(v > 0) -> activity.getString(R.string.ittc_d_err_amount)
            v > due -> activity.getString(if (admitted) R.string.ittc_d_err_more_than_due else R.string.ittc_d_err_more_than_fee, IttcAdmitApi.money(due))
            method == null -> activity.getString(R.string.ittc_d_err_method)
            methodInfo()?.txn == true && txnInput.text.toString().isBlank() -> activity.getString(R.string.ittc_d_err_txn, method)
            settings.collegeReceipt == "required" && collegeInput.text.toString().isBlank() -> activity.getString(R.string.ittc_d_err_college)
            else -> null
        }
    }
}

/**
 * 🧾 A money receipt as the Tracker writes it (Settings → Fee & discount → receipt fields and
 * footer): shared as a picture (PNG drawn on a Canvas) when the app has its file provider,
 * otherwise as text — always with the text alongside.
 */
internal object IttcReceipt {
    /** The app's file provider for shared files (res/xml/ittc_d_file_paths.xml) */
    private fun authority(context: Context) = "${context.packageName}.ittcfiles"

    class Data(
        val settings: IttcAdmitApi.Settings,
        val student: String,
        val studentId: String?,
        val course: String?,
        val batch: String?,
        val payment: IttcAdmitApi.Payment,
        val receivedBy: String?,
        val finalFee: Double,
        val paidSoFar: Double,
    )

    /** A payment just saved (its id from record_payment, else found by its receipt no.) */
    fun shareById(activity: Activity, leadId: String, paymentId: String?, receiptNo: String) {
        val app = activity.applicationContext
        ensureBackgroundThread {
            try {
                val pays = IttcAdmitApi.payments(app, leadId)
                val pay = pays.payments.firstOrNull { (paymentId != null && it.id == paymentId) || it.receiptNo == receiptNo }
                    ?: throw IllegalStateException(app.getString(R.string.ittc_d_not_found))
                build(activity, leadId, pay, pays)
            } catch (e: Throwable) {
                activity.runOnUiThread { if (IttcDUi.alive(activity)) activity.toast(IttcAdmitApi.errorText(e, activity.getString(R.string.ittc_d_could_not_share))) }
            }
        }
    }

    fun share(activity: Activity, leadId: String, payment: IttcAdmitApi.Payment, pays: IttcAdmitApi.Payments) {
        ensureBackgroundThread {
            try {
                build(activity, leadId, payment, pays)
            } catch (e: Throwable) {
                activity.runOnUiThread { if (IttcDUi.alive(activity)) activity.toast(IttcAdmitApi.errorText(e, activity.getString(R.string.ittc_d_could_not_share))) }
            }
        }
    }

    /** (background) the receipt's details, then the share sheet */
    private fun build(activity: Activity, leadId: String, pay: IttcAdmitApi.Payment, pays: IttcAdmitApi.Payments) {
        val app = activity.applicationContext
        val lead = IttcAdmitApi.lead(app, leadId) ?: throw IllegalStateException(app.getString(R.string.ittc_d_not_found))
        // "Paid so far" as of this receipt: this payment and the ones before it (as the Tracker)
        val upto = pays.payments.filter { !it.voided && (it.paidAt < pay.paidAt || (it.paidAt == pay.paidAt && it.receiptNo <= pay.receiptNo)) }
            .sumOf { it.amount }
        val d = Data(
            settings = IttcAdmitApi.settings(app),
            student = lead.name,
            studentId = lead.studentRef,
            course = IttcAdmitApi.nameOf(app, "courses", lead.admittedCourseId),
            batch = IttcAdmitApi.nameOf(app, "batches", lead.admittedBatchId),
            payment = pay,
            receivedBy = IttcAdmitApi.personName(app, pay.receivedBy),
            finalFee = lead.feeFinal ?: 0.0,
            paidSoFar = if (pay.voided) pays.paid else upto,
        )
        val text = text(d)
        val uri = try {
            if (app.packageManager.resolveContentProvider(authority(app), 0) != null) {
                val dir = File(app.cacheDir, "ittc_receipts").apply { mkdirs() }
                // keep the last few (a share sheet may still be reading an earlier one)
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                val file = File(dir, pay.receiptNo.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "receipt" } + ".png")
                val bmp = image(app, d)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                FileProvider.getUriForFile(app, authority(app), file)
            } else null
        } catch (_: Throwable) {
            null
        }
        activity.runOnUiThread {
            if (!IttcDUi.alive(activity)) return@runOnUiThread
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (uri != null) {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        type = "text/plain"
                    }
                }
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.ittc_d_share_receipt)))
            } catch (_: Throwable) {
                activity.toast(R.string.ittc_d_could_not_share)
            }
        }
    }

    /** The receipt as text (the Tracker's receiptText) */
    fun text(d: Data): String {
        val s = d.settings
        val p = d.payment
        val lines = ArrayList<String?>()
        if (s.field("institute") && s.instituteName.isNotBlank()) lines += "*${s.instituteName}*"
        if (s.field("address") && s.instituteAddress.isNotBlank()) lines += s.instituteAddress
        if (s.field("phone") && s.institutePhone.isNotBlank()) lines += "📞 ${s.institutePhone}"
        lines += ""
        if (p.voided) lines += "✕ *VOID* — this receipt is cancelled"
        lines += if (p.refund) "↩ *Refund* ${p.receiptNo}" else "🧾 *Money receipt* ${p.receiptNo}"
        lines += "Date: ${IttcAdmitApi.day(p.paidAt)}"
        lines += ""
        lines += "Student: ${d.student}"
        if (s.field("student_id") && d.studentId != null) lines += "Student ID: ${d.studentId}"
        if (s.field("course") && d.course != null) lines += "Course: ${d.course}"
        if (s.field("batch") && d.batch != null) lines += "Batch: ${d.batch}"
        lines += ""
        lines += "*Amount: ${IttcAdmitApi.money(p.amount)}*"
        if (s.field("method")) lines += "${if (p.refund) "Returned by" else "Paid by"}: ${p.method}"
        if (s.field("txn") && p.txnId != null) lines += "Transaction ID: ${p.txnId}"
        if (s.field("college_receipt") && p.collegeNo != null) lines += "College receipt no.: ${p.collegeNo}"
        if (p.refund && p.note != null) lines += "Reason: ${p.note}" else if (s.field("note") && p.note != null) lines += "Note: ${p.note}"
        if (s.field("paid_due")) {
            lines += ""
            lines += "Total fee: ${IttcAdmitApi.money(d.finalFee)}"
            lines += "Paid so far: ${IttcAdmitApi.money(d.paidSoFar)}"
            lines += "Due: ${IttcAdmitApi.money(maxOf(d.finalFee - d.paidSoFar, 0.0))}"
        }
        if (s.field("received_by") && d.receivedBy != null) lines += "\nReceived by: ${d.receivedBy}"
        if (s.receiptFooter.isNotBlank()) lines += "\n${s.receiptFooter}"
        return lines.filterNotNull().joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** The receipt drawn as a picture (always on the light colours: it is a paper) */
    fun image(context: Context, d: Data): Bitmap {
        val conf = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val light = context.createConfigurationContext(conf)
        val cText = light.getColor(R.color.ittc_text)
        val cMuted = light.getColor(R.color.ittc_text_muted)
        val cPrimary = light.getColor(R.color.ittc_primary)
        val cSoft = light.getColor(R.color.ittc_primary_soft)
        val cPaper = light.getColor(R.color.ittc_surface)
        val cLine = light.getColor(R.color.ittc_outline)
        val cDanger = light.getColor(R.color.ittc_danger)
        val cGood = light.getColor(R.color.ittc_stage_admitted_fg)

        val s = d.settings
        val p = d.payment
        val w = 1080
        val pad = 72f
        val inner = (w - 2 * pad).toInt()

        fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        // Blocks drawn top to bottom: (layout, gap before)
        class Block(val layout: StaticLayout?, val gap: Float, val rule: Boolean = false, val boxed: Boolean = false)
        val blocks = ArrayList<Block>()
        fun add(t: String, tp: TextPaint, gap: Float = 8f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, boxed: Boolean = false) {
            blocks += Block(StaticLayout.Builder.obtain(t, 0, t.length, tp, inner - if (boxed) 48 else 0).setAlignment(align).build(), gap, boxed = boxed)
        }
        fun rule(gap: Float = 28f) {
            blocks += Block(null, gap, rule = true)
        }
        val center = Layout.Alignment.ALIGN_CENTER
        if (s.field("institute") && s.instituteName.isNotBlank()) add(s.instituteName, paint(52f, cPrimary, true), 0f, center)
        if (s.field("address") && s.instituteAddress.isNotBlank()) add(s.instituteAddress, paint(30f, cMuted), 10f, center)
        if (s.field("phone") && s.institutePhone.isNotBlank()) add("📞 ${s.institutePhone}", paint(30f, cMuted), 6f, center)
        rule()
        add(if (p.refund) "REFUND" else "MONEY RECEIPT", paint(40f, cText, true), 24f, center)
        add("${p.receiptNo}  ·  ${IttcAdmitApi.day(p.paidAt)}", paint(32f, cMuted), 8f, center)
        if (p.voided) add("✕ VOID — this receipt is cancelled", paint(34f, cDanger, true), 16f, center)
        rule()
        val rows = ArrayList<Pair<String, String>>()
        rows += "Student" to d.student
        if (s.field("student_id") && d.studentId != null) rows += "Student ID" to d.studentId
        if (s.field("course") && d.course != null) rows += "Course" to d.course
        if (s.field("batch") && d.batch != null) rows += "Batch" to d.batch
        if (s.field("method")) rows += (if (p.refund) "Returned by" else "Paid by") to p.method
        if (s.field("txn") && p.txnId != null) rows += "Transaction ID" to p.txnId
        if (s.field("college_receipt") && p.collegeNo != null) rows += "College receipt no." to p.collegeNo
        if (p.refund && p.note != null) rows += "Reason" to p.note else if (s.field("note") && p.note != null) rows += "Note" to p.note
        rows.forEachIndexed { i, (k, v) -> add("$k:  $v", paint(34f, cText), if (i == 0) 28f else 14f) }
        add("Amount  ${IttcAdmitApi.money(p.amount)}", paint(56f, if (p.voided) cMuted else cGood, true), 36f, center, boxed = true)
        if (s.field("paid_due")) {
            add(
                "Total fee ${IttcAdmitApi.money(d.finalFee)}   ·   Paid ${IttcAdmitApi.money(d.paidSoFar)}   ·   Due ${IttcAdmitApi.money(maxOf(d.finalFee - d.paidSoFar, 0.0))}",
                paint(30f, cText), 28f, center,
            )
        }
        if (s.field("received_by") && d.receivedBy != null) add("Received by: ${d.receivedBy}", paint(30f, cMuted), 28f)
        if (s.receiptFooter.isNotBlank()) {
            rule()
            add(s.receiptFooter, paint(30f, cMuted), 24f, center)
        }

        var h = pad
        blocks.forEach { b -> h += b.gap + (b.layout?.height ?: 2) + if (b.boxed) 48 else 0 }
        h += pad
        val bmp = Bitmap.createBitmap(w, h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cPaper)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cLine; strokeWidth = 2f }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cSoft }
        // a coloured band on top
        c.drawRect(0f, 0f, w.toFloat(), 14f, Paint().apply { color = cPrimary })
        var y = pad
        blocks.forEach { b ->
            y += b.gap
            val l = b.layout
            when {
                b.rule -> {
                    c.drawLine(pad, y, w - pad, y, linePaint)
                    y += 2
                }
                l != null && b.boxed -> {
                    c.drawRoundRect(RectF(pad, y, w - pad, y + l.height + 48), 28f, 28f, boxPaint)
                    c.save()
                    c.translate(pad + 24, y + 24)
                    l.draw(c)
                    c.restore()
                    y += l.height + 48
                }
                l != null -> {
                    c.save()
                    c.translate(pad, y)
                    l.draw(c)
                    c.restore()
                    y += l.height
                }
            }
        }
        if (p.voided) {
            c.save()
            c.rotate(-30f, w / 2f, h / 2f)
            val stamp = paint(160f, cDanger, true).apply { alpha = 60; textAlign = Paint.Align.CENTER }
            c.drawText("VOID", w / 2f, h / 2f, stamp)
            c.restore()
        }
        return bmp
    }
}
