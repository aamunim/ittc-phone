package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcAdmitApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcLeadApi
import org.fossify.phone.helpers.IttcTheme

/**
 * 📄 3.5: a lead's admission papers — which are in (original / photocopy / not needed) and which
 * are missing — and DocReady's "Ask for papers": choose the papers (the ones not given yet are
 * ticked), how long the link stays open, then send the link by WhatsApp or SMS from this phone
 * (the Tracker's docready_request_create; a link that could not be sent is closed again).
 */
object IttcPapersSheet {
    private val DAYS = listOf(3, 7, 14)

    private class Data(
        val papers: List<IttcAdmitApi.Paper>,
        val may: IttcAdmitApi.May,
        val doc: IttcAdmitApi.DocReady?,
        val progress: Map<String, Int>,
        val open: List<IttcAdmitApi.DocRequest>,
        val name: String,
        val whatsapp: String?,
        val phone: String?,
        val college: String,
    )

    fun show(activity: Activity, leadId: String, leadName: String?, phone: String?, onDone: () -> Unit) {
        try {
            if (!IttcDirect.isActive(activity)) return
            var changed = false
            val dialog = IttcDUi.sheet(activity) { column, dialog ->
                column.addView(IttcDUi.title(activity, activity.getString(R.string.ittc_d_papers_title)))
                if (!leadName.isNullOrBlank()) column.addView(IttcDUi.hint(activity, leadName))
                val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(body, IttcDUi.lp(activity, top = 4f))
                fun load() {
                    body.removeAllViews()
                    body.addView(IttcDUi.progress(activity), IttcDUi.lp(activity, top = 24f, width = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    val app = activity.applicationContext
                    ensureBackgroundThread {
                        val r = try {
                            val may = IttcAdmitApi.may(app)
                            val lead = IttcAdmitApi.lead(app, leadId)
                            val doc = if (may.docReady) {
                                try {
                                    IttcAdmitApi.docReady(app)
                                } catch (_: Throwable) {
                                    null
                                }
                            } else null
                            Result.success(
                                Data(
                                    papers = IttcAdmitApi.papers(app, leadId),
                                    may = may,
                                    doc = doc,
                                    progress = if (doc != null) IttcAdmitApi.docProgress(app, leadId) else emptyMap(),
                                    open = if (doc != null) IttcAdmitApi.openRequests(app, leadId) else emptyList(),
                                    name = lead?.name ?: leadName.orEmpty(),
                                    whatsapp = lead?.whatsapp,
                                    phone = lead?.phone ?: phone,
                                    college = if (doc != null) IttcAdmitApi.college(app) else "",
                                ),
                            )
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                        activity.runOnUiThread {
                            if (!IttcDUi.alive(activity) || !dialog.isShowing) return@runOnUiThread
                            try {
                                body.removeAllViews()
                                val d = r.getOrNull()
                                if (d == null) {
                                    body.addView(IttcDUi.hint(activity, IttcAdmitApi.errorText(activity, r.exceptionOrNull() ?: Exception(), activity.getString(R.string.ittc_d_could_not_load))))
                                    // (a way to try again, as the Visit / Fee sheets)
                                    val row = IttcDUi.row(activity)
                                    row.addView(IttcDUi.button(activity, activity.getString(R.string.ittc_d_retry), IttcDUi.Btn.SOFT, small = true) { load() })
                                    body.addView(row, IttcDUi.lp(activity, top = 8f))
                                } else {
                                    fill(activity, body, leadId, d, dialog) { changed = true }
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

    private fun fill(activity: Activity, body: LinearLayout, leadId: String, d: Data, dialog: android.app.Dialog, changed: () -> Unit) {
        val p = IttcTheme.of(activity)

        // ---- the checklist
        val required = d.papers.filter { it.required }
        val inCount = required.count { it.status != null }
        body.addView(
            IttcDUi.text(
                activity,
                if (required.isEmpty()) activity.getString(R.string.ittc_d_papers_none)
                else activity.getString(R.string.ittc_d_papers_summary, inCount, required.size),
                15f, if (required.isNotEmpty() && inCount >= required.size) p.call else p.text, bold = true,
            ),
            IttcDUi.lp(activity, top = 8f),
        )
        val list = IttcDUi.box(activity)
        if (d.papers.isEmpty()) list.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_papers_empty)))
        // required first, then the others
        (required + d.papers.filter { !it.required }).forEach { paper ->
            val row = IttcDUi.row(activity)
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = IttcTheme.dp(activity, 32f)
            val (mark, color) = when (paper.status) {
                "received" -> "✓" to p.call
                "not_needed" -> "–" to p.muted
                else -> (if (paper.required) "✗" else "○") to (if (paper.required) p.danger else p.muted)
            }
            row.addView(
                // (the mark is a picture of the state written beside it: not read out)
                IttcDUi.text(activity, mark, 15f, color, bold = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                IttcDUi.lp(activity, width = IttcTheme.dp(activity, 24f)),
            )
            row.addView(
                IttcDUi.text(activity, paper.name + if (paper.required) " *" else "", 14f, if (paper.status == null) p.text else p.text2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val state = when (paper.status) {
                "received" -> when (paper.copy) {
                    "original" -> activity.getString(R.string.ittc_d_paper_original)
                    "photocopy" -> activity.getString(R.string.ittc_d_paper_photocopy)
                    else -> activity.getString(R.string.ittc_d_paper_received)
                }
                "not_needed" -> activity.getString(R.string.ittc_d_paper_not_needed)
                else -> activity.getString(if (paper.required) R.string.ittc_d_paper_missing else R.string.ittc_d_paper_optional)
            }
            row.addView(IttcDUi.text(activity, state, 12f, color))
            list.addView(row)
        }
        body.addView(list, IttcDUi.lp(activity, top = 8f))
        if (required.isNotEmpty()) body.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_papers_tick_hint)), IttcDUi.lp(activity, top = 4f))

        // ---- 🔗 DocReady: ask the student for papers
        val doc = d.doc
        if (doc == null || !d.may.docReady) return
        body.addView(IttcDUi.divider(activity), IttcDUi.lp(activity, top = 16f))
        body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_ask_title)), IttcDUi.lp(activity, top = 12f))
        if (!doc.enabled) {
            body.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_docready_off)), IttcDUi.lp(activity, top = 4f))
            return
        }
        if (doc.steps.isEmpty()) {
            body.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_docready_no_steps)), IttcDUi.lp(activity, top = 4f))
            return
        }
        body.addView(IttcDUi.hint(activity, activity.getString(R.string.ittc_d_ask_explain)), IttcDUi.lp(activity, top = 2f))

        // the ones not given yet are ticked
        val picked = LinkedHashSet(doc.steps.filter { (d.progress[it.id] ?: 0) == 0 && !it.multiple }.map { it.id })
        var days = 7
        val preview = IttcDUi.text(activity, "", 13f, p.text2)
        val firstName = d.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        fun pickedNames() = doc.steps.filter { it.id in picked }.joinToString(", ") { it.name }
        // the Tracker's own words (Bangla), with the link
        fun message(url: String) = "$firstName, ${d.college}-এ ভর্তির জন্য এই কাগজগুলো দিন: ${pickedNames()}। এই লিংক খুলে ছবি তুলে পাঠান: $url"
        fun redrawPreview() {
            preview.text = message(IttcAdmitApi.docsLink("…"))
        }

        val steps = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun drawSteps() {
            steps.removeAllViews()
            doc.steps.forEach { s ->
                val on = s.id in picked
                val have = d.progress[s.id] ?: 0
                val t = IttcDUi.text(
                    activity,
                    (if (on) "☑ " else "☐ ") + s.name + if (have > 0) activity.getString(R.string.ittc_d_has_n, have) else "",
                    14f, p.text,
                )
                t.minHeight = activity.resources.getDimensionPixelSize(R.dimen.ittc_touch)
                t.gravity = Gravity.CENTER_VERTICAL
                t.background = IttcTheme.pressable(activity, IttcTheme.rounded(activity, p.surface, 8f), p.text)
                // (read as a ticked / not ticked choice, not as "☑")
                t.isSelected = on
                t.contentDescription = s.name + if (have > 0) activity.getString(R.string.ittc_d_has_n, have) else ""
                t.setOnClickListener {
                    if (on) picked.remove(s.id) else picked.add(s.id)
                    drawSteps()
                    redrawPreview()
                }
                steps.addView(t)
            }
        }
        drawSteps()
        body.addView(steps, IttcDUi.lp(activity, top = 6f))

        body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_link_open_for)), IttcDUi.lp(activity, top = 8f))
        val dayChips = IttcDFlow(activity)
        fun drawDays() {
            dayChips.removeAllViews()
            DAYS.forEach { n ->
                dayChips.addView(IttcDUi.chip(activity, activity.getString(R.string.ittc_d_n_days, n), n == days) {
                    days = n
                    drawDays()
                })
            }
        }
        drawDays()
        body.addView(dayChips, IttcDUi.lp(activity, top = 6f))

        val previewBox = IttcDUi.box(activity)
        previewBox.addView(preview)
        redrawPreview()
        body.addView(previewBox, IttcDUi.lp(activity, top = 10f))

        val error = IttcDUi.errorBox(activity)
        body.addView(error)
        val buttons = IttcDUi.row(activity)
        var busy = false
        fun send(via: String) {
            if (busy) return
            if (picked.isEmpty()) return IttcDUi.showError(error, activity.getString(R.string.ittc_d_err_choose_paper))
            val number = if (via == "whatsapp") d.whatsapp ?: d.phone else d.phone ?: d.whatsapp
            if (number.isNullOrBlank()) return IttcDUi.showError(error, activity.getString(R.string.ittc_d_err_no_number))
            IttcDUi.showError(error, null)
            busy = true
            buttons.alpha = 0.5f
            val app = activity.applicationContext
            val stepIds = doc.steps.map { it.id }.filter { it in picked }
            ensureBackgroundThread {
                val r = try {
                    Result.success(IttcAdmitApi.createRequest(app, leadId, stepIds, days, via))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                activity.runOnUiThread {
                    busy = false
                    buttons.alpha = 1f
                    if (!IttcDUi.alive(activity)) return@runOnUiThread
                    r.onFailure { IttcDUi.showError(error, IttcAdmitApi.errorText(activity, it, activity.getString(R.string.ittc_d_could_not_make_link))) }
                    r.onSuccess { (id, url) ->
                        changed()
                        val text = message(url)
                        val opened = try {
                            val intent = if (via == "whatsapp") {
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${IttcLeadApi.waNumber(number)}?text=" + Uri.encode(text)))
                            } else {
                                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
                            }
                            activity.startActivity(intent)
                            true
                        } catch (_: Throwable) {
                            false
                        }
                        if (opened) {
                            dialog.dismiss()
                        } else {
                            // not sent: the new link is closed again (nobody has it)
                            IttcDUi.showError(error, activity.getString(R.string.ittc_d_no_app))
                            ensureBackgroundThread {
                                try {
                                    if (id.isNotEmpty()) IttcAdmitApi.closeRequest(app, id)
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
            }
        }
        buttons.addView(
            IttcDUi.button(activity, activity.getString(R.string.ittc_d_send_whatsapp), IttcDUi.Btn.CALL) { send("whatsapp") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            IttcDUi.button(activity, activity.getString(R.string.ittc_d_send_sms), IttcDUi.Btn.SOFT) { send("sms") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcTheme.dp(activity, 8f) },
        )
        body.addView(buttons, IttcDUi.lp(activity, top = 12f))

        // ---- links still open (can be closed)
        if (d.open.isNotEmpty()) {
            body.addView(IttcDUi.label(activity, activity.getString(R.string.ittc_d_open_links)), IttcDUi.lp(activity, top = 16f))
            d.open.forEach { req ->
                val row = IttcDUi.row(activity)
                row.gravity = Gravity.CENTER_VERTICAL
                val info = IttcDUi.hint(activity, activity.getString(R.string.ittc_d_link_until, IttcAdmitApi.day(req.expiresAt)) + (req.via?.let { " · $it" } ?: ""))
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val close = IttcDUi.button(activity, activity.getString(R.string.ittc_d_close_link), IttcDUi.Btn.GHOST, small = true) {}
                close.setOnClickListener {
                    close.isEnabled = false
                    val app = activity.applicationContext
                    ensureBackgroundThread {
                        val err = try {
                            IttcAdmitApi.closeRequest(app, req.id)
                            null
                        } catch (e: Throwable) {
                            IttcAdmitApi.errorText(activity, e, activity.getString(R.string.ittc_d_could_not_save))
                        }
                        activity.runOnUiThread {
                            if (!IttcDUi.alive(activity)) return@runOnUiThread
                            if (err != null) {
                                close.isEnabled = true
                                activity.toast(err)
                            } else {
                                changed()
                                row.alpha = 0.4f
                                close.text = activity.getString(R.string.ittc_d_closed)
                            }
                        }
                    }
                }
                row.addView(close)
                body.addView(row, IttcDUi.lp(activity, top = 4f))
            }
        }
    }
}
