package org.fossify.phone.direct

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🎓 3.5: admission, payments and papers in direct mode — the same server functions and tables the
 * Tracker uses (mark_admitted, record_payment, lead_papers_status, docready_request_create …), as the
 * signed-in person: the server checks who may do what and its message is shown as it is.
 * Every call waits for the network: only off the main thread. Throws [IttcApi.ApiError].
 */
object IttcAdmitApi {
    private const val CACHE_MS = 5 * 60_000L

    // ================================================================== settings
    data class Method(val name: String, val txn: Boolean)

    class Settings(
        val batchRequired: Boolean,
        val allowOverbooking: Boolean,
        /** "auto" | "manual" | "off" */
        val idMode: String,
        /** "block" | "warn" | "allow" */
        val papersOnAdmit: String,
        val feesOn: Boolean,
        val paymentsOn: Boolean,
        val methods: List<Method>,
        val minOnAdmit: Double,
        val backdateDays: Int,
        /** "off" | "optional" | "required" */
        val collegeReceipt: String,
        val receiptFields: Map<String, Boolean>,
        val receiptFooter: String,
        /** ৳ the default admission fee (Settings → Admission flow) */
        val admissionFeeDefault: Double,
        val instituteName: String,
        val instituteAddress: String,
        val institutePhone: String,
    ) {
        val payOn get() = feesOn && paymentsOn
        fun field(key: String) = receiptFields[key] ?: true
    }

    private val DEFAULT_METHODS = listOf(
        Method("Cash", false), Method("bKash", true), Method("Nagad", true),
        Method("Rocket", true), Method("Bank", true), Method("Card", false),
    )

    @Volatile private var settingsCache: Pair<Long, Settings>? = null

    /** The Tracker's settings (Admission, Fee & discount, Admission flow), kept 5 min; defaults where unset / unreadable */
    fun settings(context: Context): Settings {
        settingsCache?.let { (at, s) -> if (System.currentTimeMillis() - at < CACHE_MS) return s }
        val values = HashMap<String, Any?>()
        try {
            val arr = IttcApi.select(
                context,
                "app_settings?select=key,value&key=in.(admission,fees,admission_approval,message_templates,institution_name)",
            )
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                values[o.optString("key")] = if (o.isNull("value")) null else o.opt("value")
            }
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
        }
        val adm = values["admission"] as? JSONObject ?: JSONObject()
        val fees = values["fees"] as? JSONObject ?: JSONObject()
        val pay = fees.optJSONObject("payments") ?: JSONObject()
        val approval = values["admission_approval"] as? JSONObject ?: JSONObject()
        val inst = (values["message_templates"] as? JSONObject)?.optJSONObject("institute") ?: JSONObject()
        val methods = pay.optJSONArray("methods")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { m -> m.str("name")?.let { Method(it, m.optBoolean("txn", false)) } }
            }
        } ?: DEFAULT_METHODS
        val fields = HashMap<String, Boolean>()
        pay.optJSONObject("receipt")?.optJSONObject("fields")?.let { f ->
            f.keys().forEach { k -> fields[k] = f.optBoolean(k, true) }
        }
        val paperRule = adm.str("papers_on_admit")?.takeIf { it in setOf("block", "warn", "allow") } ?: "warn"
        val s = Settings(
            batchRequired = adm.optBoolean("batch_required", false),
            allowOverbooking = adm.optBoolean("allow_overbooking", false),
            idMode = adm.optJSONObject("student_id")?.str("mode") ?: "auto",
            papersOnAdmit = paperRule,
            feesOn = fees.optBoolean("enabled", true),
            paymentsOn = pay.optBoolean("enabled", true),
            methods = methods,
            minOnAdmit = pay.optDouble("min_on_admit", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            backdateDays = pay.optInt("backdate_days", 7),
            collegeReceipt = pay.str("college_receipt") ?: "optional",
            receiptFields = fields,
            receiptFooter = pay.optJSONObject("receipt")?.let { r -> if (r.has("footer")) r.optString("footer") else null } ?: "Thank you.",
            admissionFeeDefault = approval.optDouble("admission_min_paid", 1000.0).takeIf { !it.isNaN() } ?: 1000.0,
            instituteName = inst.str("name") ?: (values["institution_name"] as? String).orEmpty(),
            instituteAddress = inst.str("address").orEmpty(),
            institutePhone = inst.str("phone").orEmpty(),
        )
        settingsCache = System.currentTimeMillis() to s
        return s
    }

    // ================================================================== who may (cheap: kept 5 min)
    @Volatile private var mayCache: Pair<Long, Map<String, Boolean>>? = null

    class May(val admit: Boolean, val pay: Boolean, val docReady: Boolean)

    /** What the Tracker would show (the server checks again): can_mark_admitted, can_take_payments, DocReady use */
    fun may(context: Context): May {
        val kept = mayCache?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second
        val m = kept ?: run {
            fun ask(fn: String): Boolean = try {
                IttcApi.rpc(context, fn).trim() == "true"
            } catch (e: IttcApi.ApiError) {
                if (e.status == 0) throw e
                false
            }
            val doc = try {
                JSONObject(IttcApi.rpc(context, "my_docready_access")).optBoolean("use", false)
            } catch (e: IttcApi.ApiError) {
                if (e.status == 0) throw e
                false
            } catch (_: Throwable) {
                false
            }
            mapOf("admit" to ask("can_mark_admitted"), "pay" to ask("can_take_payments"), "doc" to doc).also {
                mayCache = System.currentTimeMillis() to it
            }
        }
        return May(m["admit"] == true, m["pay"] == true, m["doc"] == true)
    }

    /** After signing in as someone else, or a settings change */
    fun forget() {
        settingsCache = null
        mayCache = null
        brandCollege = null
    }

    // ================================================================== the lead
    class LeadState(
        val id: String,
        val name: String,
        val phone: String?,
        val whatsapp: String?,
        /** the stage's kind: "open" | "won" | "lost" | "cancelled" */
        val kind: String,
        val admittedAt: Long?,
        val cancelledAt: Long?,
        val admittedCourseId: String?,
        val admittedBatchId: String?,
        val admittedSectionId: String?,
        val studentRef: String?,
        val nuAdmissionId: String?,
        val feeStatus: String?,
        val feeFull: Double?,
        val feeFinal: Double?,
        val feePaid: Double?,
        val feeDue: Double?,
        val feeNextDue: String?,
        val papersRequired: Int,
        val papersReceived: Int,
        val submittedAt: Long?,
        val receivedAt: Long?,
        val admissionFeeNeed: Double?,
        /** courses the lead is interested in */
        val courseIds: List<String>,
    ) {
        val admitted get() = kind == "won" && admittedAt != null && cancelledAt == null
        val isOpen get() = kind == "open"
    }

    private const val LEAD_COLUMNS = "id,name,phone,whatsapp_number,stage_id,admitted_at,cancelled_at,admitted_course_id," +
        "admitted_batch_id,admitted_section_id,student_ref,nu_admission_id,fee_status,fee_full,fee_final,fee_paid,fee_due," +
        "fee_next_due,papers_required,papers_received,submitted_at,received_at,admission_fee_need"

    /** null: not found / not visible */
    fun lead(context: Context, leadId: String): LeadState? {
        val o = IttcApi.select(context, "leads?select=$LEAD_COLUMNS&id=eq.$leadId&limit=1").optJSONObject(0) ?: return null
        val stageId = o.str("stage_id")
        val kind = stageId?.let {
            try {
                IttcApi.select(context, "pipeline_stages?select=kind&id=eq.$it&limit=1").optJSONObject(0)?.str("kind")
            } catch (e: IttcApi.ApiError) {
                if (e.status == 0) throw e
                null
            }
        } ?: when {
            o.str("cancelled_at") != null -> "cancelled"
            o.str("admitted_at") != null -> "won"
            else -> "open"
        }
        val courses = try {
            val arr = IttcApi.select(context, "lead_courses?select=course_id&lead_id=eq.$leadId")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("course_id") }
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            emptyList()
        }
        return LeadState(
            id = leadId,
            name = o.str("name").orEmpty(),
            phone = o.str("phone"),
            whatsapp = o.str("whatsapp_number"),
            kind = kind,
            admittedAt = time(o.str("admitted_at")),
            cancelledAt = time(o.str("cancelled_at")),
            admittedCourseId = o.str("admitted_course_id"),
            admittedBatchId = o.str("admitted_batch_id"),
            admittedSectionId = o.str("admitted_section_id"),
            studentRef = o.str("student_ref"),
            nuAdmissionId = o.str("nu_admission_id"),
            feeStatus = o.str("fee_status"),
            feeFull = o.num("fee_full"),
            feeFinal = o.num("fee_final"),
            feePaid = o.num("fee_paid"),
            feeDue = o.num("fee_due"),
            feeNextDue = o.str("fee_next_due"),
            papersRequired = o.optInt("papers_required", 0),
            papersReceived = o.optInt("papers_received", 0),
            submittedAt = time(o.str("submitted_at")),
            receivedAt = time(o.str("received_at")),
            admissionFeeNeed = o.num("admission_fee_need"),
            courseIds = courses,
        )
    }

    /** ↩ The latest "Submitted / Received undone" (the officer's reason), after [since] */
    fun lastReturn(context: Context, leadId: String, since: Long?): Pair<String, String>? = try {
        val o = IttcApi.select(
            context,
            "lead_activity?select=details,created_at&lead_id=eq.$leadId&action=eq.ittc_undone&order=created_at.desc&limit=1",
        ).optJSONObject(0)
        val at = time(o?.str("created_at"))
        val d = o?.optJSONObject("details")
        if (d == null || (since != null && at != null && at < since)) null
        else (d.str("step") ?: "submitted") to d.str("reason").orEmpty()
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        null
    }

    /** One name from a table ("courses", "batches", "batch_sections") */
    fun nameOf(context: Context, table: String, id: String?): String? {
        if (id == null) return null
        return try {
            IttcApi.select(context, "$table?select=name&id=eq.$id&limit=1").optJSONObject(0)?.str("name")
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            null
        }
    }

    fun personName(context: Context, id: String?): String? {
        if (id == null) return null
        return try {
            IttcApi.select(context, "profiles?select=full_name&id=eq.$id&limit=1").optJSONObject(0)?.str("full_name")
        } catch (_: Throwable) {
            null
        }
    }

    // ================================================================== admit
    data class Named(val id: String, val name: String)

    data class Batch(
        val id: String,
        val name: String,
        val startDate: String?,
        val classTime: String?,
        val status: String,
        val seatCapacity: Int?,
        val seatsTaken: Int,
        val seatsLeft: Int?,
        val isFull: Boolean,
    ) {
        /** "Spring-27 · 12 of 40 left" / "Spring-27 · Full (40/40)" / "Spring-27" (as the Tracker) */
        fun label(): String = when {
            seatCapacity == null -> name
            isFull -> "$name · Full ($seatsTaken/$seatCapacity)"
            else -> "$name · $seatsLeft of $seatCapacity left"
        }

        fun canPick(allowOverbooking: Boolean) = status == "open" && (!isFull || allowOverbooking)
    }

    /** Active courses (the Tracker's order) */
    fun courses(context: Context): List<Named> {
        val arr = IttcApi.select(context, "courses?select=id,name&is_active=eq.true&order=sort_order.asc,name.asc")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> o.str("id")?.let { Named(it, o.str("name").orEmpty()) } }
        }
    }

    fun batches(context: Context, courseId: String): List<Batch> {
        val arr = JSONArray(IttcApi.rpc(context, "admission_batches", JSONObject().put("p_course_id", courseId)).ifBlank { "[]" })
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Batch(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                startDate = o.str("start_date"),
                classTime = o.str("class_time"),
                status = o.str("status") ?: "open",
                seatCapacity = if (o.isNull("seat_capacity")) null else o.optInt("seat_capacity"),
                seatsTaken = o.optInt("seats_taken", 0),
                seatsLeft = if (o.isNull("seats_left")) null else o.optInt("seats_left"),
                isFull = o.optBoolean("is_full", false),
            )
        }
    }

    /** 🏫 The sections of a batch (none: the batch has no sections) */
    fun sections(context: Context, batchId: String): List<Named> = try {
        val arr = IttcApi.select(
            context,
            "batch_sections?select=id,name&batch_id=eq.$batchId&is_active=eq.true&order=sort_order.asc,name.asc",
        )
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> o.str("id")?.let { Named(it, o.str("name").orEmpty()) } } }
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        emptyList()
    }

    /** What the next automatic student ID will be (null: none / the range is full) */
    fun previewRef(context: Context, courseId: String?, batchId: String?, sectionId: String?): String? = try {
        val args = JSONObject()
        courseId?.let { args.put("p_course", it) }
        batchId?.let { args.put("p_batch", it) }
        sectionId?.let { args.put("p_section", it) }
        IttcApi.rpc(context, "preview_student_ref", args).trim().removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "null" }
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        null
    }

    /** Required papers still missing for the course (null: could not check; the server checks on save) */
    fun missingPapers(context: Context, leadId: String, courseId: String?): List<String>? = try {
        val arr = JSONArray(
            IttcApi.rpc(context, "lead_missing_papers", JSONObject().put("p_lead_id", leadId).put("p_course_id", courseId ?: JSONObject.NULL))
                .ifBlank { "[]" },
        )
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("name") }
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        null
    } catch (_: Throwable) {
        null
    }

    /** The same, never throwing (null: could not check) */
    fun missingPapersSafe(context: Context, leadId: String, courseId: String?): List<String>? = try {
        missingPapers(context, leadId, courseId)
    } catch (_: Throwable) {
        null
    }

    data class Fee(val full: Double, val discount: Double, val final: Double, val negotiated: Boolean)

    /** The fee for admitting to a course: the negotiated one, or the course's full fee (as the Tracker) */
    fun admissionFee(context: Context, leadId: String, courseId: String): Fee {
        val list = IttcApi.select(context, "course_fees?select=amount&course_id=eq.$courseId")
        val courseTotal = (0 until list.length()).sumOf { list.optJSONObject(it)?.num("amount") ?: 0.0 }
        val n = IttcApi.select(
            context,
            "lead_negotiations?select=id,status,final_total,list_total,fee_discounts(amount,status,expires_at)" +
                "&lead_id=eq.$leadId&course_id=eq.$courseId&order=started_at.asc",
        ).optJSONObject(0) ?: return Fee(courseTotal, 0.0, courseTotal, false)
        val locked = n.str("status") == "locked"
        val listTotal = if (locked) n.num("list_total") ?: courseTotal else courseTotal
        val now = System.currentTimeMillis()
        val ds = n.optJSONArray("fee_discounts") ?: JSONArray()
        val discount = (0 until ds.length()).sumOf { i ->
            val d = ds.optJSONObject(i) ?: return@sumOf 0.0
            val exp = time(d.str("expires_at"))
            if (d.str("status") == "approved" && (locked || exp == null || exp > now)) d.num("amount") ?: 0.0 else 0.0
        }
        val finalTotal = if (locked) n.num("final_total") else null
        val final = finalTotal ?: maxOf(listTotal - discount, 0.0)
        return Fee(listTotal, if (finalTotal != null) maxOf(listTotal - finalTotal, 0.0) else discount, final, true)
    }

    data class AdmitResult(val studentRef: String?, val seatsLeft: Int?)

    /** mark_admitted (the Tracker's own); dayMs null = now */
    fun markAdmitted(
        context: Context,
        leadId: String,
        courseId: String,
        batchId: String?,
        sectionId: String?,
        dayMs: Long?,
        studentRef: String?,
        note: String?,
    ): AdmitResult {
        val args = JSONObject()
            .put("p_lead_id", leadId)
            .put("p_course_id", courseId)
            .put("p_batch_id", batchId ?: JSONObject.NULL)
            .put("p_admitted_at", dayMs?.let { isoAt(it) } ?: JSONObject.NULL)
            .put("p_student_ref", studentRef?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("p_note", note?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        sectionId?.let { args.put("p_section_id", it) }
        val text = IttcApi.rpc(context, "mark_admitted", args).trim()
        val o = try {
            JSONObject(text)
        } catch (_: Throwable) {
            JSONObject()
        }
        // (the lists on the phone show the new stage at once)
        IttcDirect.refreshAsync(context, force = true, delayMs = 500)
        return AdmitResult(o.str("student_ref"), if (o.isNull("seats_left") || !o.has("seats_left")) null else o.optInt("seats_left"))
    }

    // ================================================================== payments
    data class Payment(
        val id: String,
        val receiptNo: String,
        val amount: Double,
        val method: String,
        val txnId: String?,
        val paidAt: Long,
        val note: String?,
        val receivedBy: String?,
        val voided: Boolean,
        val refund: Boolean,
        val collegeNo: String?,
    )

    data class Installment(val seq: Int, val dueDate: String, val amount: Double)

    /** An installment with how much of it the payments so far cover (earliest first) */
    data class InstallmentState(val item: Installment, val covered: Double, val state: String)

    class Payments(val payments: List<Payment>, val installments: List<Installment>) {
        /** payments − refunds (void ones left out) */
        val paid: Double get() = payments.filter { !it.voided }.sumOf { if (it.refund) -it.amount else it.amount }

        fun states(): List<InstallmentState> {
            var left = paid
            val today = LocalDate.now().toString()
            return installments.sortedBy { it.seq }.map { i ->
                val covered = minOf(i.amount, maxOf(left, 0.0))
                left -= i.amount
                val state = when {
                    covered >= i.amount -> "paid"
                    covered > 0 && i.dueDate < today -> "late"
                    covered > 0 -> "part"
                    i.dueDate < today -> "late"
                    else -> "due"
                }
                InstallmentState(i, covered, state)
            }
        }
    }

    fun payments(context: Context, leadId: String): Payments {
        val p = IttcApi.select(context, "fee_payments?select=*&lead_id=eq.$leadId&order=paid_at.desc")
        val list = (0 until p.length()).mapNotNull { i ->
            val o = p.optJSONObject(i) ?: return@mapNotNull null
            Payment(
                id = o.str("id") ?: return@mapNotNull null,
                receiptNo = o.str("receipt_no").orEmpty(),
                amount = o.num("amount") ?: 0.0,
                method = o.str("method").orEmpty(),
                txnId = o.str("txn_id"),
                paidAt = time(o.str("paid_at")) ?: 0L,
                note = o.str("note"),
                receivedBy = o.str("received_by"),
                voided = o.str("voided_at") != null,
                refund = o.str("kind") == "refund",
                collegeNo = o.str("college_receipt_no"),
            )
        }
        val inst = try {
            val a = IttcApi.select(context, "fee_installments?select=seq,due_date,amount&lead_id=eq.$leadId&order=seq.asc")
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o -> Installment(o.optInt("seq"), o.str("due_date").orEmpty(), o.num("amount") ?: 0.0) }
            }
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            emptyList()
        }
        return Payments(list, inst)
    }

    data class PayResult(val id: String?, val receiptNo: String, val due: Double?)

    /** record_payment (the Tracker's own); dayMs null = now */
    fun recordPayment(
        context: Context,
        leadId: String,
        amount: Double,
        method: String,
        txnId: String?,
        dayMs: Long?,
        note: String?,
        collegeReceipt: String?,
    ): PayResult {
        val args = JSONObject()
            .put("p_lead_id", leadId)
            .put("p_amount", amount)
            .put("p_method", method)
        txnId?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_txn_id", it) }
        dayMs?.let { args.put("p_paid_at", isoAt(it)) }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_note", it) }
        collegeReceipt?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_college_receipt", it) }
        val o = try {
            JSONObject(IttcApi.rpc(context, "record_payment", args))
        } catch (e: IttcApi.ApiError) {
            throw e
        } catch (_: Throwable) {
            JSONObject()
        }
        return PayResult(o.str("id"), o.str("receipt_no").orEmpty(), o.num("due"))
    }

    // ================================================================== papers
    data class Paper(val name: String, val required: Boolean, /** null = not received */ val status: String?, val copy: String?)

    fun papers(context: Context, leadId: String): List<Paper> {
        val arr = JSONArray(IttcApi.rpc(context, "lead_papers_status", JSONObject().put("p_lead_id", leadId)).ifBlank { "[]" })
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Paper(
                name = o.str("name") ?: return@mapNotNull null,
                required = o.optBoolean("is_required", false),
                status = if (o.str("record_id") != null) o.str("status") ?: "received" else null,
                copy = o.str("copy_type"),
            )
        }
    }

    // ================================================================== DocReady link
    data class Step(val id: String, val name: String, val multiple: Boolean)

    class DocReady(val enabled: Boolean, val steps: List<Step>)

    fun docReady(context: Context): DocReady {
        val o = JSONObject(IttcApi.rpc(context, "docready_setting").ifBlank { "{}" })
        val a = o.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { s -> s.str("id")?.let { Step(it, s.str("name") ?: it, s.optBoolean("multiple", false)) } }
        }
        return DocReady(o.optBoolean("enabled", true), steps)
    }

    /** step id → files the student / staff already gave */
    fun docProgress(context: Context, leadId: String): Map<String, Int> = try {
        val o = JSONObject(IttcApi.rpc(context, "docready_progress", JSONObject().put("p_lead", leadId).put("p_folder", JSONObject.NULL)).ifBlank { "{}" })
        o.keys().asSequence().associateWith { k -> o.optJSONObject(k)?.optInt("count", 0) ?: 0 }
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    data class DocRequest(val id: String, val token: String, val expiresAt: Long, val via: String?)

    /** Links still open (not closed, not run out) */
    fun openRequests(context: Context, leadId: String): List<DocRequest> = try {
        val arr = IttcApi.select(
            context,
            "docready_requests?select=id,token,expires_at,closed_at,sent_via&lead_id=eq.$leadId&order=created_at.desc&limit=10",
        )
        val now = System.currentTimeMillis()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val exp = time(o.str("expires_at")) ?: return@mapNotNull null
            if (o.str("closed_at") != null || exp <= now) null
            else DocRequest(o.str("id") ?: return@mapNotNull null, o.str("token").orEmpty(), exp, o.str("sent_via"))
        }
    } catch (e: IttcApi.ApiError) {
        if (e.status == 0) throw e
        emptyList()
    }

    /** docready_request_create → the new link's (id, url) */
    fun createRequest(context: Context, leadId: String, stepIds: List<String>, days: Int, via: String): Pair<String, String> {
        val args = JSONObject()
            .put("p_lead", leadId)
            .put("p_steps", JSONArray(stepIds))
            .put("p_days", days)
            .put("p_via", via)
        val o = JSONObject(IttcApi.rpc(context, "docready_request_create", args))
        val token = o.str("token") ?: throw IttcApi.ApiError(500, "The link was not made.")
        return (o.str("id") ?: "") to docsLink(token)
    }

    fun closeRequest(context: Context, id: String) {
        IttcApi.rpc(context, "docready_request_close", JSONObject().put("p_id", id))
    }

    fun docsLink(token: String) = "${IttcLeadApi.WEB}/docs/$token"

    @Volatile private var brandCollege: String? = null

    /** The college's name (Settings → Branding; the Tracker's own words when unset) */
    fun college(context: Context): String {
        brandCollege?.let { return it }
        val name = try {
            JSONObject(IttcApi.rpc(context, "app_brand").ifBlank { "{}" }).str("college")
        } catch (_: Throwable) {
            null
        } ?: "Ideal Teachers' Training College"
        brandCollege = name
        return name
    }

    // ================================================================== small helpers
    /** "৳1,25,000" (the Tracker's en-IN grouping) */
    fun money(n: Double): String {
        val v = Math.round(n)
        val neg = v < 0
        val s = Math.abs(v).toString()
        val out = if (s.length <= 3) s else {
            val head = s.substring(0, s.length - 3)
            val grouped = head.reversed().chunked(2).joinToString(",").reversed()
            "$grouped,${s.substring(s.length - 3)}"
        }
        return (if (neg) "-৳" else "৳") + out
    }

    /** "18 Nov 2026" */
    fun day(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(ms))

    /** "2026-11-18" → "18 Nov 2026" */
    fun dayOf(isoDay: String?): String = try {
        val d = LocalDate.parse(isoDay)
        day(d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    } catch (_: Throwable) {
        isoDay.orEmpty()
    }

    /** Is this day today (on this phone)? */
    fun isToday(ms: Long): Boolean =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

    /** A chosen day with the time now (as the Tracker's date pickers keep the time) */
    private fun isoAt(dayMs: Long): String {
        val d = java.time.Instant.ofEpochMilli(dayMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return ZonedDateTime.of(d, LocalTime.now(), ZoneId.systemDefault()).toOffsetDateTime().toString()
    }

    fun time(s: String?): Long? = org.fossify.phone.helpers.IttcBridge.parseTimeOf(s)

    /** The server's message, or a plain one */
    fun errorText(e: Throwable, fallback: String): String = when {
        e is IttcApi.ApiError && e.status == 0 -> "No internet connection. Please try again."
        e is IttcApi.ApiError && e.message?.contains("leads_student_ref_unique") == true -> "That student ID is already used."
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.num(k: String): Double? =
        if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
}
