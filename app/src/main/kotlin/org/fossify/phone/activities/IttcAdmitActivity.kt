package org.fossify.phone.activities

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcDActivityAdmitBinding
import org.fossify.phone.direct.IttcAdmitApi
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcTheme
import java.util.Calendar

/**
 * 🎓 3.5: Admit — the Tracker's "Mark admitted" sheet on the phone: the course (the lead's
 * interested ones first, ★), the batch (seats left; full ones greyed unless overbooking is on),
 * the section when the batch has sections, the papers check for that course (block / warn), the
 * fee and a first payment (the least one Settings asks for), the admission date, the student ID
 * (automatic preview, or typed) and a note — with the Tracker's checks, saved by mark_admitted and
 * record_payment. Then: the student ID, the payment's receipt (share it) and where the admission is.
 */
class IttcAdmitActivity : SimpleActivity() {
    companion object {
        private const val EXTRA_LEAD = "ittc_lead_id"

        fun open(context: Context, leadId: String) {
            try {
                val i = Intent(context, IttcAdmitActivity::class.java).putExtra(EXTRA_LEAD, leadId)
                if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (_: Throwable) {
            }
        }
    }

    private val binding by viewBinding(IttcDActivityAdmitBinding::inflate)
    private var leadId = ""

    // ---- what the server said
    private var lead: IttcAdmitApi.LeadState? = null
    private var settings: IttcAdmitApi.Settings? = null
    private var may: IttcAdmitApi.May? = null
    private var courses = listOf<IttcAdmitApi.Named>()

    // ---- the choices
    private var courseId: String? = null
    private var batches = listOf<IttcAdmitApi.Batch>()
    private var batchesLoading = false
    private var batchId: String? = null
    private var sections = listOf<IttcAdmitApi.Named>()
    private var sectionId: String? = null
    private var missing: List<String>? = null
    private var missingLoading = false
    private var fee: IttcAdmitApi.Fee? = null
    private var payNow = false
    private var admittedDay: Long? = null // null = today
    private var refPreview: String? = null
    private var refFull = false
    private var ownRef = false
    private var saving = false
    private var done = false

    /** Answers for an older course / batch choice are dropped */
    private var courseToken = 0
    private var batchToken = 0

    // ---- the form's parts (kept, so typing is never lost when a part is drawn again)
    private lateinit var courseBox: LinearLayout
    private lateinit var papersBox: LinearLayout
    private lateinit var batchBox: LinearLayout
    private lateinit var sectionBox: LinearLayout
    private lateinit var feeBox: LinearLayout
    private lateinit var dateButton: TextView
    private lateinit var idBox: LinearLayout
    private lateinit var refInput: android.widget.EditText
    private lateinit var noteInput: android.widget.EditText
    private lateinit var errorView: TextView
    private lateinit var saveButton: TextView
    private var payForm: IttcDPayForm? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leadId = intent.getStringExtra(EXTRA_LEAD).orEmpty()
        if (leadId.isBlank() || !IttcDirect.isSignedIn(this)) {
            finish()
            return
        }
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomImeAndSystem = listOf(ittcDAdmitScroll))
            setupMaterialScrollListener(ittcDAdmitScroll, ittcDAdmitAppbar)
            val p = IttcTheme.of(this@IttcAdmitActivity)
            ittcDAdmitCoordinator.setBackgroundColor(p.bg)
            ittcDAdmitScroll.setBackgroundColor(p.bg)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcDAdmitAppbar, NavigationIcon.Arrow)
    }

    private fun alive() = !isFinishing && !isDestroyed

    private val holder get() = binding.ittcDAdmitHolder

    // =================================================================== load
    private fun load() {
        holder.removeAllViews()
        holder.addView(IttcDUi.loadingCard(this))
        ensureBackgroundThread {
            val r = try {
                val l = IttcAdmitApi.lead(applicationContext, leadId)
                val s = IttcAdmitApi.settings(applicationContext, strict = true)
                val m = IttcAdmitApi.may(applicationContext)
                val c = if (l != null && l.isOpen && m.admit) IttcAdmitApi.courses(applicationContext) else emptyList()
                val preview = if (l != null && l.isOpen && m.admit && s.idMode == "auto") IttcAdmitApi.previewRef(applicationContext, null, null, null) else null
                Result.success(listOf(l, s, m, c, preview))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                holder.removeAllViews()
                r.onFailure { e -> message(IttcAdmitApi.errorText(e, getString(R.string.ittc_d_could_not_load)), retry = true) }
                r.onSuccess { list ->
                    @Suppress("UNCHECKED_CAST")
                    val l = list[0] as IttcAdmitApi.LeadState?
                    settings = list[1] as IttcAdmitApi.Settings
                    may = list[2] as IttcAdmitApi.May
                    @Suppress("UNCHECKED_CAST")
                    courses = list[3] as List<IttcAdmitApi.Named>
                    refPreview = list[4] as String?
                    lead = l
                    when {
                        l == null -> message(getString(R.string.ittc_d_not_found))
                        l.admitted -> showAdmitted(l)
                        !l.isOpen -> message(getString(if (l.kind == "cancelled") R.string.ittc_d_reopen_cancelled else R.string.ittc_d_reopen_first))
                        may?.admit != true -> message(getString(R.string.ittc_d_not_allowed))
                        else -> buildForm(l)
                    }
                }
            }
        }
    }

    private fun message(text: String, retry: Boolean = false) {
        val card = IttcDUi.card(this)
        lead?.let { card.addView(IttcDUi.title(this, it.name)) }
        card.addView(IttcDUi.text(this, text, 14f, IttcTheme.of(this).text2), IttcDUi.lp(this, top = 6f))
        if (retry) card.addView(IttcDUi.button(this, getString(R.string.ittc_d_retry), IttcDUi.Btn.SOFT, small = true) { load() }, IttcDUi.lp(this, top = 10f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(card)
    }

    /** Already admitted: where the admission is, the fee and papers (the card) */
    private fun showAdmitted(l: IttcAdmitApi.LeadState) {
        val card = IttcDUi.card(this)
        card.addView(IttcDUi.title(this, l.name))
        card.addView(IttcDUi.text(this, getString(R.string.ittc_d_already_admitted), 14f, IttcTheme.of(this).text2), IttcDUi.lp(this, top = 4f))
        holder.addView(card)
        val status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(status)
        IttcAdmitCard.bind(this, status, leadId)
    }

    // =================================================================== the form
    private fun buildForm(l: IttcAdmitApi.LeadState) {
        val p = IttcTheme.of(this)
        val s = settings ?: return

        val head = IttcDUi.card(this)
        head.addView(IttcDUi.title(this, getString(R.string.ittc_d_mark_admitted)))
        head.addView(IttcDUi.text(this, l.name, 15f, p.text2), IttcDUi.lp(this, top = 2f))
        holder.addView(head)

        val form = IttcDUi.card(this)
        courseBox = section(form)
        papersBox = section(form)
        batchBox = section(form)
        sectionBox = section(form)
        feeBox = section(form)

        // date
        val dateSection = section(form)
        dateSection.addView(IttcDUi.label(this, getString(R.string.ittc_d_admission_date)))
        dateButton = IttcDUi.button(this, "", IttcDUi.Btn.SOFT, small = true) { pickDay() }
        dateSection.addView(dateButton, IttcDUi.lp(this, top = 6f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
        drawDay()

        // student ID
        idBox = section(form)
        refInput = IttcDUi.input(this, getString(R.string.ittc_d_ref_hint), caps = true)
        refInput.setText(l.studentRef.orEmpty())

        // note
        val noteSection = section(form)
        noteSection.addView(IttcDUi.label(this, getString(R.string.ittc_d_note_label)))
        noteInput = IttcDUi.input(this, getString(R.string.ittc_d_note_example), multiline = true)
        noteSection.addView(noteInput)

        errorView = IttcDUi.errorBox(this)
        form.addView(errorView)
        saveButton = IttcDUi.button(this, getString(R.string.ittc_d_mark_admitted), IttcDUi.Btn.PRIMARY) { save(false) }
        form.addView(saveButton, IttcDUi.lp(this, top = 16f))
        form.addView(IttcDUi.button(this, getString(R.string.ittc_d_cancel), IttcDUi.Btn.GHOST) { finish() }, IttcDUi.lp(this, top = 4f))
        holder.addView(form)

        // Only one interested course: pick it
        val interested = courses.filter { it.id in l.courseIds }
        payNow = s.minOnAdmit > 0
        drawCourses()
        drawId()
        if (interested.size == 1) pickCourse(interested[0].id)
    }

    private fun section(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this, IttcDUi.lp(this@IttcAdmitActivity, top = if (parent.childCount == 0) 0f else 14f))
    }

    private fun drawCourses() {
        val l = lead ?: return
        courseBox.removeAllViews()
        courseBox.addView(IttcDUi.label(this, getString(R.string.ittc_d_course_req)))
        if (courses.isEmpty()) {
            courseBox.addView(IttcDUi.text(this, getString(R.string.ittc_d_no_courses), 13f, IttcTheme.of(this).danger), IttcDUi.lp(this, top = 4f))
            return
        }
        // interested courses first
        val sorted = courses.filter { it.id in l.courseIds } + courses.filter { it.id !in l.courseIds }
        val flow = IttcDFlow(this)
        sorted.forEach { c ->
            val star = if (c.id in l.courseIds) "★ " else ""
            flow.addView(IttcDUi.chip(this, star + c.name, c.id == courseId) { pickCourse(c.id) })
        }
        courseBox.addView(flow, IttcDUi.lp(this, top = 6f))
        if (l.courseIds.isNotEmpty()) courseBox.addView(IttcDUi.hint(this, getString(R.string.ittc_d_star_hint)), IttcDUi.lp(this, top = 4f))
    }

    private fun pickCourse(id: String) {
        if (saving || id == courseId) return
        courseId = id
        batchId = null
        batches = emptyList()
        sections = emptyList()
        sectionId = null
        fee = null
        missing = null
        val s = settings ?: return
        batchesLoading = true
        missingLoading = s.papersOnAdmit != "allow"
        drawCourses()
        drawBatches()
        drawSections()
        drawPapers()
        drawFee()
        val token = ++courseToken
        val app = applicationContext
        ensureBackgroundThread {
            val list = try {
                IttcAdmitApi.batches(app, id)
            } catch (_: Throwable) {
                emptyList()
            }
            val f = try {
                IttcAdmitApi.admissionFee(app, leadId, id).takeIf { it.full > 0 }
            } catch (_: Throwable) {
                null
            }
            val miss = if (s.papersOnAdmit != "allow") IttcAdmitApi.missingPapersSafe(app, leadId, id) else null
            runOnUiThread {
                if (!alive() || token != courseToken || done) return@runOnUiThread
                batches = list
                batchesLoading = false
                fee = f
                missing = miss
                missingLoading = false
                drawBatches()
                drawPapers()
                drawFee()
                // Only one batch that still has room: pick it
                val usable = list.filter { !it.isFull && it.status == "open" }
                if (usable.size == 1) pickBatch(usable[0].id) else refreshPreview()
            }
        }
    }

    private fun drawPapers() {
        val s = settings ?: return
        papersBox.removeAllViews()
        if (courseId == null || s.papersOnAdmit == "allow") return
        val p = IttcTheme.of(this)
        val miss = missing
        when {
            missingLoading -> papersBox.addView(IttcDUi.hint(this, getString(R.string.ittc_d_checking_papers)))
            miss == null -> Unit // could not check: the server checks on save
            miss.isEmpty() -> papersBox.addView(IttcDUi.box(this, IttcTheme.stageColors(this, "admitted").first).apply {
                addView(IttcDUi.text(this@IttcAdmitActivity, getString(R.string.ittc_d_papers_all_in), 13f, IttcTheme.stageColors(this@IttcAdmitActivity, "admitted").second, bold = true))
            })
            else -> {
                val block = s.papersOnAdmit == "block"
                val box = IttcDUi.box(this, p.dangerSoft)
                box.addView(
                    IttcDUi.text(
                        this, (if (block) "⛔ " else "⚠️ ") + resources.getQuantityString(R.plurals.ittc_d_papers_missing_n, miss.size, miss.size),
                        14f, p.danger, bold = true,
                    ),
                )
                miss.forEach { box.addView(IttcDUi.text(this, "• $it", 13f, p.text2)) }
                box.addView(IttcDUi.hint(this, getString(if (block) R.string.ittc_d_papers_block_hint else R.string.ittc_d_papers_warn_hint)), IttcDUi.lp(this, top = 4f))
                if (block) {
                    box.addView(IttcDUi.button(this, getString(R.string.ittc_d_papers), IttcDUi.Btn.SOFT, small = true) {
                        IttcPapersSheet.show(this, leadId, lead?.name, lead?.whatsapp ?: lead?.phone) { recheckPapers() }
                    }, IttcDUi.lp(this, top = 8f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                papersBox.addView(box)
            }
        }
    }

    private fun recheckPapers() {
        val id = courseId ?: return
        val token = courseToken
        missingLoading = true
        drawPapers()
        val app = applicationContext
        ensureBackgroundThread {
            val miss = IttcAdmitApi.missingPapersSafe(app, leadId, id)
            runOnUiThread {
                if (!alive() || token != courseToken || done) return@runOnUiThread
                missing = miss
                missingLoading = false
                drawPapers()
            }
        }
    }

    private fun drawBatches() {
        val s = settings ?: return
        batchBox.removeAllViews()
        if (courseId == null) return
        batchBox.addView(IttcDUi.label(this, getString(if (s.batchRequired) R.string.ittc_d_batch_req else R.string.ittc_d_batch_optional)))
        when {
            batchesLoading -> batchBox.addView(IttcDUi.progress(this), IttcDUi.lp(this, top = 6f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
            batches.isEmpty() -> batchBox.addView(IttcDUi.hint(this, getString(R.string.ittc_d_no_batches)), IttcDUi.lp(this, top = 4f))
            else -> {
                if (!s.batchRequired) {
                    batchBox.addView(IttcDUi.option(this, getString(R.string.ittc_d_no_batch_yet), null, batchId == null, true, false) { pickBatch(null) })
                }
                batches.forEach { b ->
                    val sub = listOfNotNull(
                        b.startDate?.let { getString(R.string.ittc_d_starts, IttcAdmitApi.dayOf(it)) },
                        b.classTime,
                        b.status.takeIf { it != "open" },
                    ).joinToString(" · ")
                    batchBox.addView(
                        IttcDUi.option(this, b.label(), sub, batchId == b.id, b.canPick(s.allowOverbooking), b.isFull && s.allowOverbooking) { pickBatch(b.id) },
                    )
                }
                if (batches.any { it.isFull }) {
                    batchBox.addView(
                        IttcDUi.hint(this, getString(if (s.allowOverbooking) R.string.ittc_d_full_overbook else R.string.ittc_d_full_greyed)),
                        IttcDUi.lp(this, top = 4f),
                    )
                }
            }
        }
    }

    private fun pickBatch(id: String?) {
        if (saving) return
        batchId = id
        sectionId = null
        sections = emptyList()
        drawBatches()
        drawSections()
        val token = ++batchToken
        if (id == null) {
            refreshPreview()
            return
        }
        val app = applicationContext
        ensureBackgroundThread {
            val list = try {
                IttcAdmitApi.sections(app, id)
            } catch (_: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (!alive() || token != batchToken || done) return@runOnUiThread
                sections = list
                if (list.size == 1) sectionId = list[0].id
                drawSections()
                refreshPreview()
            }
        }
    }

    private fun drawSections() {
        sectionBox.removeAllViews()
        if (batchId == null || sections.isEmpty()) return
        sectionBox.addView(IttcDUi.label(this, getString(R.string.ittc_d_section_req)))
        sections.forEach { sec ->
            sectionBox.addView(IttcDUi.option(this, getString(R.string.ittc_d_section_n, sec.name), null, sectionId == sec.id, true, false) {
                if (saving) return@option
                sectionId = sec.id
                drawSections()
                refreshPreview()
            })
        }
    }

    // ---- 💰 the fee and a payment now
    private fun payOn() = settings?.payOn == true && fee != null
    private fun mayPay() = payOn() && may?.pay == true
    private fun minPay() = if (payOn()) minOf(settings?.minOnAdmit ?: 0.0, fee?.final ?: 0.0) else 0.0

    private fun drawFee() {
        feeBox.removeAllViews()
        val f = fee ?: run {
            payForm = null
            return
        }
        val s = settings ?: return
        val p = IttcTheme.of(this)
        val box = IttcDUi.box(this, p.surfaceAlt)
        val row = IttcDUi.row(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(IttcDUi.label(this, getString(R.string.ittc_d_fee_label)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IttcDUi.text(this, IttcAdmitApi.money(f.final), 19f, p.text, bold = true))
        box.addView(row)
        if (f.discount > 0) {
            box.addView(IttcDUi.hint(this, getString(R.string.ittc_d_fee_discount, IttcAdmitApi.money(f.full), IttcAdmitApi.money(f.discount))))
        }
        val min = minPay()
        if (mayPay()) {
            val toggle = IttcDUi.text(
                this,
                (if (payNow) "☑ " else "☐ ") + getString(R.string.ittc_d_pay_now) + if (min > 0) getString(R.string.ittc_d_pay_least, IttcAdmitApi.money(min)) else "",
                14f, p.text, bold = true,
            )
            toggle.minHeight = IttcTheme.dp(this, 44f)
            toggle.gravity = Gravity.CENTER_VERTICAL
            toggle.setOnClickListener {
                if (min == 0.0 && !saving) {
                    payNow = !payNow
                    drawFee()
                }
            }
            box.addView(toggle, IttcDUi.lp(this, top = 6f))
            if (payNow) {
                val form = payForm ?: IttcDPayForm(this, s, f.final, null, admitted = false).also { payForm = it }
                form.amountInput.hint = getString(R.string.ittc_d_amount_up_to, IttcAdmitApi.money(f.final))
                form.addTo(box, withDate = false, withNote = false)
            }
        }
        if (min > 0 && !mayPay()) {
            box.addView(IttcDUi.text(this, getString(R.string.ittc_d_min_cant_pay, IttcAdmitApi.money(min)), 13f, p.danger), IttcDUi.lp(this, top = 6f))
        }
        feeBox.addView(box)
    }

    // ---- 📅 the admission date
    private fun drawDay() {
        dateButton.text = "📅 " + IttcAdmitApi.day(admittedDay ?: System.currentTimeMillis())
    }

    private fun pickDay() {
        if (saving) return
        val c = Calendar.getInstance().apply { admittedDay?.let { timeInMillis = it } }
        try {
            DatePickerDialog(this, { _, y, m, d ->
                val ms = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
                admittedDay = if (IttcAdmitApi.isToday(ms)) null else ms
                drawDay()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        } catch (_: Throwable) {
        }
    }

    // ---- 🎓 the student ID
    private fun refreshPreview() {
        val s = settings ?: return
        if (s.idMode != "auto") return
        val c = courseId
        val b = batchId
        val sec = sectionId
        val app = applicationContext
        ensureBackgroundThread {
            val r = try {
                IttcAdmitApi.previewRef(app, c, b, sec)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (!alive() || done || c != courseId || b != batchId || sec != sectionId) return@runOnUiThread
                refPreview = r
                refFull = c != null && r == null
                drawId()
            }
        }
    }

    private fun drawId() {
        val s = settings ?: return
        val l = lead ?: return
        idBox.removeAllViews()
        if (s.idMode == "off") return
        val p = IttcTheme.of(this)
        idBox.addView(IttcDUi.label(this, getString(if (s.idMode == "manual") R.string.ittc_d_student_id_req else R.string.ittc_d_student_id_label)))
        (refInput.parent as? ViewGroup)?.removeView(refInput)
        if (s.idMode == "auto" && !ownRef) {
            val box = IttcDUi.box(this)
            val warn = refFull && l.studentRef == null
            box.addView(
                IttcDUi.text(
                    this,
                    l.studentRef ?: if (refFull) getString(R.string.ittc_d_ref_full) else refPreview ?: getString(R.string.ittc_d_ref_auto),
                    16f, if (warn) p.danger else p.text, bold = true,
                ),
            )
            box.addView(IttcDUi.hint(this, getString(if (l.studentRef != null) R.string.ittc_d_ref_kept else R.string.ittc_d_ref_auto)))
            idBox.addView(box, IttcDUi.lp(this, top = 6f))
            idBox.addView(IttcDUi.button(this, getString(R.string.ittc_d_ref_type_own), IttcDUi.Btn.GHOST, small = true) {
                ownRef = true
                drawId()
            }, IttcDUi.lp(this, top = 2f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            idBox.addView(refInput)
        }
    }

    // =================================================================== save
    private fun showError(text: String?) {
        IttcDUi.showError(errorView, text)
        if (text != null) binding.ittcDAdmitScroll.post { binding.ittcDAdmitScroll.smoothScrollTo(0, errorView.top + (errorView.parent as View).top) }
    }

    private fun save(papersConfirmed: Boolean) {
        if (saving || done) return
        val s = settings ?: return
        val l = lead ?: return
        showError(null)
        val course = courseId ?: return showError(getString(R.string.ittc_d_err_course))
        if (s.batchRequired && batchId == null) return showError(getString(R.string.ittc_d_err_batch))
        if (batchId != null && sections.isNotEmpty() && sectionId == null) return showError(getString(R.string.ittc_d_err_section))
        if (s.idMode == "manual" && refInput.text.toString().isBlank()) return showError(getString(R.string.ittc_d_err_ref))
        // the payment now
        val min = minPay()
        val form = payForm
        if (min > 0 && !mayPay()) return showError(getString(R.string.ittc_d_err_min_cant, IttcAdmitApi.money(min)))
        val paying = payNow && mayPay() && form != null
        if (paying) {
            form!!.validate(fee?.final ?: 0.0, min)?.let { return showError(it) }
        }
        // papers
        val miss = missing.orEmpty()
        if (s.papersOnAdmit == "block" && miss.isNotEmpty()) {
            return showError(getString(R.string.ittc_d_err_papers_block, miss.joinToString(", ")))
        }
        if (missingLoading) return showError(getString(R.string.ittc_d_checking_papers))
        if (s.papersOnAdmit == "warn" && miss.isNotEmpty() && !papersConfirmed) {
            try {
                AlertDialog.Builder(this)
                    .setTitle(resources.getQuantityString(R.plurals.ittc_d_papers_not_received_n, miss.size, miss.size))
                    .setMessage(getString(R.string.ittc_d_admit_anyway_msg, miss.joinToString("\n• ")))
                    .setNegativeButton(R.string.ittc_d_go_back, null)
                    .setPositiveButton(R.string.ittc_d_admit_anyway) { _, _ -> save(true) }
                    .show()
            } catch (_: Throwable) {
            }
            return
        }

        hideKeyboard()
        saving = true
        saveButton.alpha = 0.5f
        saveButton.text = getString(R.string.ittc_d_saving)
        val ref = if (s.idMode == "manual" || (s.idMode == "auto" && ownRef)) refInput.text.toString() else null
        val note = noteInput.text.toString()
        val batch = batchId
        val section = sectionId
        val day = admittedDay
        val pay = if (paying) form else null
        val payArgs = pay?.let { listOf(it.amount(), it.method, it.txn(), it.college()) }
        val app = applicationContext
        ensureBackgroundThread {
            var result: IttcAdmitApi.AdmitResult? = null
            var error: String? = null
            var paid: IttcAdmitApi.PayResult? = null
            var payError: String? = null
            var payFirst = payArgs != null
            try {
                result = try {
                    IttcAdmitApi.markAdmitted(app, leadId, course, batch, section, day, ref, note)
                } catch (e: IttcApi.ApiError) {
                    if (e.status != 0) throw e
                    // The reply was lost: it may have been saved. Look again before saying it failed.
                    val now = try {
                        IttcAdmitApi.lead(app, leadId)
                    } catch (_: Throwable) {
                        null
                    }
                    if (now == null || !now.admitted) throw e
                    // markAdmitted's own refresh didn't run: bring the lists up to date
                    IttcDirect.refreshAsync(app, force = true)
                    // admitted: the first payment only if none is in yet
                    if (payFirst) {
                        payFirst = try {
                            IttcAdmitApi.payments(app, leadId).payments.none { !it.voided && !it.refund }
                        } catch (_: Throwable) {
                            false.also { payError = getString(R.string.ittc_d_pay_not_confirmed) }
                        }
                    }
                    IttcAdmitApi.AdmitResult(now.studentRef, null)
                }
                // the payment, now that the student is admitted (paid now, as the Tracker's AdmitSheet)
                if (payArgs != null && payFirst) {
                    val amount = payArgs[0] as Double
                    // the payments there before this attempt (null: couldn't be read)
                    val knownIds = try {
                        IttcAdmitApi.payments(app, leadId).payments.map { it.id }.toSet()
                    } catch (_: Throwable) {
                        null
                    }
                    try {
                        paid = IttcAdmitApi.recordPayment(
                            app, leadId, amount, payArgs[1] as String, payArgs[2] as String?, null,
                            getString(R.string.ittc_d_at_admission), payArgs[3] as String?,
                        )
                    } catch (e: Throwable) {
                        if (e is IttcApi.ApiError && e.status == 0) {
                            paid = try {
                                knownIds?.let { IttcAdmitApi.findJustRecorded(app, leadId, amount, it) }
                            } catch (_: Throwable) {
                                null
                            }
                            if (paid == null) payError = getString(R.string.ittc_d_pay_not_confirmed)
                        } else {
                            payError = IttcAdmitApi.errorText(e, getString(R.string.ittc_d_could_not_save))
                        }
                    }
                }
            } catch (e: Throwable) {
                error = IttcAdmitApi.errorText(e, getString(R.string.ittc_d_could_not_save_admission))
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                saving = false
                saveButton.alpha = 1f
                saveButton.text = getString(R.string.ittc_d_mark_admitted)
                val r = result
                if (r == null) {
                    showError(error)
                    return@runOnUiThread
                }
                done = true
                setResult(RESULT_OK)
                showDone(l, r, paid, (payArgs?.get(0) as Double?) ?: 0.0, payError, miss.size)
            }
        }
    }

    // =================================================================== done
    private fun showDone(l: IttcAdmitApi.LeadState, r: IttcAdmitApi.AdmitResult, paid: IttcAdmitApi.PayResult?, amount: Double, payError: String?, stillMissing: Int) {
        val p = IttcTheme.of(this)
        holder.removeAllViews()
        val card = IttcDUi.card(this)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(IttcDUi.text(this, "🎉", 48f, p.text).apply { gravity = Gravity.CENTER })
        card.addView(IttcDUi.title(this, getString(R.string.ittc_d_is_admitted, l.name)).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 4f))
        val place = listOfNotNull(courses.firstOrNull { it.id == courseId }?.name, batches.firstOrNull { it.id == batchId }?.name).joinToString(" · ")
        if (place.isNotEmpty()) card.addView(IttcDUi.text(this, place, 14f, p.call, bold = true).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 4f))
        r.studentRef?.let {
            card.addView(IttcDUi.pill(this, getString(R.string.ittc_d_student_id, it), IttcDUi.Tone.GOOD), IttcDUi.lp(this, top = 10f, width = ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        r.seatsLeft?.let {
            card.addView(IttcDUi.hint(this, if (it == 0) getString(R.string.ittc_d_batch_now_full) else resources.getQuantityString(R.plurals.ittc_d_seats_left_n, it, it)).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 6f))
        }
        if (paid != null) {
            card.addView(IttcDUi.text(this, getString(R.string.ittc_d_received_receipt, IttcAdmitApi.money(amount), paid.receiptNo), 14f, p.call, bold = true).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 10f))
            card.addView(IttcDUi.button(this, getString(R.string.ittc_d_share_receipt), IttcDUi.Btn.CALL) {
                IttcReceipt.shareById(this, leadId, paid.id, paid.receiptNo)
            }, IttcDUi.lp(this, top = 10f))
        }
        payError?.let {
            card.addView(IttcDUi.text(this, getString(R.string.ittc_d_admitted_pay_failed, it), 13f, p.danger).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 10f))
        }
        if (stillMissing > 0) {
            card.addView(IttcDUi.text(this, resources.getQuantityString(R.plurals.ittc_d_papers_to_collect_n, stillMissing, stillMissing), 13f, p.danger).apply { gravity = Gravity.CENTER }, IttcDUi.lp(this, top = 8f))
        }
        card.addView(IttcDUi.button(this, getString(R.string.ittc_d_done), IttcDUi.Btn.PRIMARY) { finish() }, IttcDUi.lp(this, top = 16f))
        holder.addView(card)

        // 🏛 where the admission is now (Submitted Online → Received by ITTC), the fee and papers
        holder.addView(IttcDUi.label(this, getString(R.string.ittc_d_approval_title)), IttcDUi.lp(this, top = 4f).apply { bottomMargin = IttcTheme.dp(this@IttcAdmitActivity, 6f) })
        val status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(status)
        IttcAdmitCard.bind(this, status, leadId)
        binding.ittcDAdmitScroll.scrollTo(0, 0)
    }
}
