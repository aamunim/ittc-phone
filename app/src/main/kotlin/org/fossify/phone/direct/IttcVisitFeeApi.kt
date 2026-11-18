package org.fossify.phone.direct

import android.content.Context
import org.fossify.phone.helpers.IttcBridge
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 🏫💰🔎 ITTC Phone 3.4 (direct mode): campus visits, fee & discount and lead search, with the
 * Tracker's own functions and tables (plan_visit, finish_visit, cancel_visit, start_negotiation,
 * request_discount …). The server checks every permission and its message is shown as it is.
 * Every call waits for the network: only off the main thread. Throws [IttcApi.ApiError].
 */
object IttcVisitFeeApi {
    private const val CACHE_MS = 10 * 60_000L

    // ------------------------------------------------------------------ the lead
    /** What the sheets need to know about the lead: open / won / lost / cancelled, its courses */
    data class LeadInfo(val id: String, val name: String, val stageKind: String, val courseIds: List<String>) {
        val isOpen get() = stageKind == "open"
    }

    fun leadInfo(context: Context, leadId: String): LeadInfo? {
        val arr = IttcApi.select(
            context,
            "leads?select=id,name,pipeline_course_id,stage:pipeline_stages(kind),lead_courses(course_id)&id=eq.${enc(leadId)}&limit=1",
        )
        val o = arr.optJSONObject(0) ?: return null
        val courses = ArrayList<String>()
        o.str("pipeline_course_id")?.let { courses += it }
        o.optJSONArray("lead_courses")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.str("course_id")?.let { if (it !in courses) courses += it }
        }
        return LeadInfo(
            id = o.str("id") ?: leadId,
            name = o.str("name").orEmpty(),
            stageKind = o.optJSONObject("stage")?.str("kind") ?: "open",
            courseIds = courses,
        )
    }

    /** My user id (for "I asked for this discount") */
    fun myId(context: Context): String? = IttcAuth.session(context)?.userId

    // ------------------------------------------------------------------ 🏫 campus visits
    /** planned / visited / missed / cancelled; times in epoch ms */
    data class Visit(
        val id: String,
        val status: String,
        val plannedAt: Long?,
        val visitedAt: Long?,
        val note: String?,
        val by: String?,
    )

    data class VisitSetting(val enabled: Boolean, val movesToInterested: Boolean, val missedFollowUpDays: Int?)

    @Volatile private var visitSettingCache: Pair<Long, VisitSetting>? = null

    /** Settings → Campus visit (campus_visit_setting(), defaults as the Tracker) */
    fun visitSetting(context: Context): VisitSetting {
        visitSettingCache?.let { (at, s) -> if (System.currentTimeMillis() - at < CACHE_MS) return s }
        val s = try {
            val o = JSONObject(IttcApi.rpc(context, "campus_visit_setting"))
            VisitSetting(
                enabled = o.optBoolean("enabled", true),
                movesToInterested = o.optBoolean("visited_moves_to_interested", true),
                missedFollowUpDays = if (o.isNull("missed_follow_up_days")) null else o.optInt("missed_follow_up_days", 1),
            )
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            VisitSetting(true, true, 1)
        } catch (_: Throwable) {
            VisitSetting(true, true, 1)
        }
        visitSettingCache = System.currentTimeMillis() to s
        return s
    }

    /** All the lead's visits, newest first (lead_visits; the row security decides) */
    fun visits(context: Context, leadId: String): List<Visit> {
        val arr = IttcApi.select(
            context,
            "lead_visits?select=id,status,planned_at,visited_at,note,created_at," +
                "creator:created_by(full_name),updater:updated_by(full_name)" +
                "&lead_id=eq.${enc(leadId)}&order=created_at.desc&limit=50",
        )
        val out = ArrayList<Visit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Visit(
                id = o.str("id") ?: continue,
                status = o.str("status") ?: "planned",
                plannedAt = IttcBridge.parseTimeOf(o.str("planned_at")),
                visitedAt = IttcBridge.parseTimeOf(o.str("visited_at")),
                note = o.str("note"),
                by = o.optJSONObject("updater")?.str("full_name") ?: o.optJSONObject("creator")?.str("full_name"),
            )
        }
        return out
    }

    /** Book a visit, or move the planned one (plan_visit) */
    fun planVisit(context: Context, leadId: String, at: Long, note: String?) {
        val args = JSONObject().put("p_lead_id", leadId).put("p_at", IttcDirect.iso(at))
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_note", it) }
        IttcApi.rpc(context, "plan_visit", args)
    }

    /** The result of "came" / "did not come": the stage it moved to, the next call it set */
    data class FinishResult(val movedTo: String?, val followUpAt: Long?)

    /** The student came ("visited", also a walk-in) or did not come ("missed") (finish_visit) */
    fun finishVisit(context: Context, leadId: String, status: String, note: String?): FinishResult {
        val args = JSONObject().put("p_lead_id", leadId).put("p_status", status)
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_note", it) }
        val text = IttcApi.rpc(context, "finish_visit", args).trim()
        val o = try {
            JSONObject(text)
        } catch (_: Throwable) {
            JSONObject()
        }
        return FinishResult(o.str("moved_to"), IttcBridge.parseTimeOf(o.str("follow_up_at")))
    }

    /** Drop the planned visit (cancel_visit) */
    fun cancelVisit(context: Context, leadId: String) {
        IttcApi.rpc(context, "cancel_visit", JSONObject().put("p_lead_id", leadId))
    }

    // ------------------------------------------------------------------ 💰 fee & discount
    data class FeeItem(val id: String?, val name: String, val amount: Double)

    data class Discount(
        val id: String,
        val feeId: String?,
        val feeName: String?,
        val kind: String,
        val value: Double,
        val amount: Double,
        val reason: String?,
        val note: String?,
        /** pending / approved / rejected / cancelled */
        val status: String,
        val requestedBy: String?,
        val requesterName: String?,
        val requestedAt: Long,
        val decidedBy: String?,
        val deciderName: String?,
        val decisionNote: String?,
        val expiresAt: Long?,
    )

    /** quoted / lead_asked / offer / note */
    data class FeeEvent(val kind: String, val amount: Double?, val note: String?, val by: String?, val at: Long)

    data class Negotiation(
        val id: String,
        val courseId: String,
        val courseName: String,
        /** open / locked (admitted: the final fee is fixed) */
        val status: String,
        val finalTotal: Double?,
        val listTotal: Double?,
        val listItems: List<FeeItem>?,
        val courseFees: List<FeeItem>,
        val discounts: List<Discount>,
        val events: List<FeeEvent>,
    ) {
        val locked get() = status == "locked"

        /** An approved discount still counts (not run out) — as the Tracker's isActive */
        fun isActive(d: Discount) =
            d.status == "approved" && (locked || d.expiresAt == null || d.expiresAt > System.currentTimeMillis())

        /** The fee rows: as at admission once admitted, else the course's list now */
        fun feeRows(): List<FeeItem> = if (locked && listItems != null) listItems else courseFees

        val courseListTotal get() = courseFees.sumOf { it.amount }

        /** list (full fee), discount in force, pending discount, finalFee (what is left to pay) */
        fun totals(): Totals {
            val list = if (locked && listTotal != null) listTotal else courseListTotal
            val discount = discounts.filter { isActive(it) }.sumOf { it.amount }
            val pending = discounts.filter { it.status == "pending" }.sumOf { it.amount }
            val isFinal = locked && finalTotal != null
            val finalFee = if (isFinal) finalTotal!! else maxOf(list - discount, 0.0)
            return Totals(list, if (isFinal) maxOf(list - finalFee, 0.0) else discount, pending, finalFee)
        }

        /** The latest "we offered" / "lead asked" */
        fun lastOf(kind: String): FeeEvent? = events.lastOrNull { it.kind == kind }
    }

    data class Totals(val list: Double, val discount: Double, val pending: Double, val finalFee: Double)

    data class FeeSetting(val enabled: Boolean, val reasons: List<String>, val reasonRequired: Boolean)

    private val DEFAULT_REASONS = listOf("Financial problem", "Sibling", "Early bird", "Referral", "Merit", "Other")

    @Volatile private var feeSettingCache: Pair<Long, FeeSetting>? = null

    /** Settings → Fee & discount (app_settings 'fees'; the Tracker's defaults when not set) */
    fun feeSetting(context: Context): FeeSetting {
        feeSettingCache?.let { (at, s) -> if (System.currentTimeMillis() - at < CACHE_MS) return s }
        val v = try {
            IttcApi.select(context, "app_settings?select=value&key=eq.fees").optJSONObject(0)?.optJSONObject("value")
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            null
        } ?: JSONObject()
        val reasons = v.optJSONArray("reasons")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
        val s = FeeSetting(
            enabled = v.optBoolean("enabled", true),
            reasons = reasons ?: DEFAULT_REASONS,
            reasonRequired = v.optBoolean("reason_required", true),
        )
        feeSettingCache = System.currentTimeMillis() to s
        return s
    }

    @Volatile private var canUseCache: Triple<String, Long, Boolean>? = null

    /** May I use fee negotiation? (the server's own rule, can_use_fees; kept 5 minutes) */
    fun canUseFees(context: Context): Boolean {
        val uid = myId(context).orEmpty()
        canUseCache?.let { (who, at, v) -> if (who == uid && System.currentTimeMillis() - at < 5 * 60_000L) return v }
        val v = try {
            IttcApi.rpc(context, "can_use_fees", JSONObject()).trim() == "true"
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            false
        }
        canUseCache = Triple(uid, System.currentTimeMillis(), v)
        return v
    }

    /** My discount limit, % of the course fee (100 = no limit) */
    fun discountLimit(context: Context): Double =
        try {
            IttcApi.rpc(context, "discount_limit", JSONObject()).trim().trim('"').toDoubleOrNull() ?: 0.0
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            0.0
        }

    /** The lead's fee talks (one per course), oldest first */
    fun negotiations(context: Context, leadId: String): List<Negotiation> {
        val arr = IttcApi.select(
            context,
            "lead_negotiations?select=id,course_id,status,final_total,list_total,list_items,started_at," +
                "course:courses(id,name,course_fees(id,name,amount,sort_order))," +
                "negotiation_events(kind,amount,note,created_at,author:created_by(full_name))," +
                "fee_discounts(id,fee_id,fee_name,kind,value,amount,reason,note,status,requested_by,requested_at," +
                "decided_by,decision_note,expires_at,requester:requested_by(full_name),decider:decided_by(full_name))" +
                "&lead_id=eq.${enc(leadId)}&order=started_at",
        )
        val out = ArrayList<Negotiation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val course = o.optJSONObject("course")
            val fees = course?.optJSONArray("course_fees").objects()
                .sortedBy { it.optInt("sort_order", 0) }
                .map { FeeItem(it.str("id"), it.str("name").orEmpty(), it.optDouble("amount", 0.0)) }
            val listItems = o.optJSONArray("list_items")?.objects()
                ?.sortedBy { it.optInt("sort_order", 0) }
                ?.map { FeeItem(it.str("id"), it.str("name").orEmpty(), it.optDouble("amount", 0.0)) }
            val discounts = o.optJSONArray("fee_discounts").objects().mapNotNull { d ->
                Discount(
                    id = d.str("id") ?: return@mapNotNull null,
                    feeId = d.str("fee_id"),
                    feeName = d.str("fee_name"),
                    kind = d.str("kind") ?: "amount",
                    value = d.optDouble("value", 0.0),
                    amount = d.optDouble("amount", 0.0),
                    reason = d.str("reason"),
                    note = d.str("note"),
                    status = d.str("status") ?: "pending",
                    requestedBy = d.str("requested_by"),
                    requesterName = d.optJSONObject("requester")?.str("full_name"),
                    requestedAt = IttcBridge.parseTimeOf(d.str("requested_at")) ?: 0L,
                    decidedBy = d.str("decided_by"),
                    deciderName = d.optJSONObject("decider")?.str("full_name"),
                    decisionNote = d.str("decision_note"),
                    expiresAt = IttcBridge.parseTimeOf(d.str("expires_at")),
                )
            }.sortedBy { it.requestedAt }
            val events = o.optJSONArray("negotiation_events").objects().map { e ->
                FeeEvent(
                    kind = e.str("kind") ?: "note",
                    amount = if (e.isNull("amount")) null else e.optDouble("amount"),
                    note = e.str("note"),
                    by = e.optJSONObject("author")?.str("full_name"),
                    at = IttcBridge.parseTimeOf(e.str("created_at")) ?: 0L,
                )
            }.sortedBy { it.at }
            out += Negotiation(
                id = o.str("id") ?: continue,
                courseId = o.str("course_id").orEmpty(),
                courseName = course?.str("name") ?: "Course",
                status = o.str("status") ?: "open",
                finalTotal = if (o.isNull("final_total")) null else o.optDouble("final_total"),
                listTotal = if (o.isNull("list_total")) null else o.optDouble("list_total"),
                listItems = listItems,
                courseFees = fees,
                discounts = discounts,
                events = events,
            )
        }
        return out
    }

    data class CourseFee(val id: String, val name: String, val total: Double)

    /** Courses with a fee list (to start a fee talk), the lead's own courses first */
    fun coursesWithFees(context: Context, leadCourseIds: List<String>): List<CourseFee> {
        val arr = IttcApi.select(context, "courses?select=id,name,course_fees(amount)&is_active=eq.true&order=sort_order")
        return arr.objects().mapNotNull { c ->
            val fees = c.optJSONArray("course_fees").objects()
            if (fees.isEmpty()) return@mapNotNull null
            CourseFee(c.str("id") ?: return@mapNotNull null, c.str("name").orEmpty(), fees.sumOf { it.optDouble("amount", 0.0) })
        }.sortedBy { if (it.id in leadCourseIds) 0 else 1 }
    }

    /** Start (or open again) the fee talk for a course; New / Contacted → Interested (start_negotiation) */
    fun startNegotiation(context: Context, leadId: String, courseId: String): String =
        IttcApi.rpc(context, "start_negotiation", JSONObject().put("p_lead_id", leadId).put("p_course_id", courseId)).trim().trim('"')

    /** "We told the fee / Lead asked for / We offered ৳…", or a note (add_negotiation_event) */
    fun addEvent(context: Context, negotiationId: String, kind: String, amount: Double?, note: String?) {
        val args = JSONObject().put("p_negotiation_id", negotiationId).put("p_kind", kind)
        if (kind != "note" && amount != null) args.put("p_amount", amount)
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_note", it) }
        IttcApi.rpc(context, "add_negotiation_event", args)
    }

    /** status "approved" (within my limit) or "pending" (waits for approval) */
    data class DiscountResult(val status: String, val amount: Double, val percent: Double)

    /** Ask for a discount (request_discount): kind "amount" (taka) or "percent"; feeId null = whole fee */
    fun requestDiscount(
        context: Context,
        negotiationId: String,
        feeId: String?,
        kind: String,
        value: Double,
        reason: String?,
        note: String?,
    ): DiscountResult {
        val args = JSONObject()
            .put("p_negotiation_id", negotiationId)
            .put("p_fee_id", feeId ?: JSONObject.NULL)
            .put("p_kind", kind)
            .put("p_value", value)
        reason?.takeIf { it.isNotBlank() }?.let { args.put("p_reason", it) }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("p_note", it) }
        val text = IttcApi.rpc(context, "request_discount", args)
        val o = try {
            JSONObject(text)
        } catch (_: Throwable) {
            JSONObject()
        }
        return DiscountResult(o.str("status") ?: "pending", o.optDouble("amount", 0.0), o.optDouble("percent", 0.0))
    }

    /** Take back a waiting / approved discount (cancel_discount) */
    fun cancelDiscount(context: Context, discountId: String) {
        IttcApi.rpc(context, "cancel_discount", JSONObject().put("p_discount_id", discountId))
    }

    // ------------------------------------------------------------------ 🔎 search
    data class Found(
        val id: String,
        val name: String,
        val phone: String?,
        val stage: String?,
        val stageKind: String?,
        val course: String?,
        val owner: String?,
    )

    private const val FOUND_SELECT =
        "id,name,phone,stage:pipeline_stages(name,kind),assignee:assigned_to(full_name),lead_courses(course:courses(name))"

    /**
     * Every lead I may see (closed ones too) whose name, phone (any part, e.g. the last 4 digits),
     * other numbers, student ID, guardian, e-mail or area has the text — as the Tracker's search.
     */
    fun search(context: Context, text: String, limit: Int = 40): List<Found> {
        val clean = text.replace(Regex("[%,()*\"\\\\]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.count { it.isLetterOrDigit() } < 2) return emptyList()
        val digits = searchDigits(clean)
        val username = clean.trimStart('@').lowercase()
        val parts = arrayListOf(
            "name.ilike.*$clean*",
            "student_ref.ilike.*$clean*",
            "nu_admission_id.ilike.*$clean*",
            "guardian_name.ilike.*$clean*",
            "email.ilike.*$clean*",
            "area.ilike.*$clean*",
        )
        if (username.length >= 2) parts += "whatsapp_username.ilike.*$username*"
        if (digits.length >= 3) {
            parts += listOf(
                "phone.ilike.*$digits*",
                "alt_phone.ilike.*$digits*",
                "extra_phones_text.ilike.*$digits*",
                "guardian_phone.ilike.*$digits*",
                "whatsapp_number.ilike.*${clean.filter { it.isDigit() }}*",
            )
        }
        val or = enc("(" + parts.joinToString(",") + ")")
        val arr = IttcApi.select(
            context,
            "leads?select=$FOUND_SELECT&deleted_at=is.null&or=$or&order=last_contacted_at.desc.nullslast,created_at.desc&limit=$limit",
        )
        return arr.objects().mapNotNull { found(it) }
    }

    /** These leads, in this order (Recent / Starred) */
    fun byIds(context: Context, ids: List<String>): List<Found> {
        if (ids.isEmpty()) return emptyList()
        val arr = IttcApi.select(
            context,
            "leads?select=$FOUND_SELECT&deleted_at=is.null&id=in.(${ids.joinToString(",") { enc(it) }})",
        )
        val byId = arr.objects().mapNotNull { found(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** 🕘 The leads I worked on in the last 7 days, newest first (my_recent_leads) */
    fun recentIds(context: Context, limit: Int = 40): List<String> {
        val from = IttcDirect.iso(System.currentTimeMillis() - 7 * 24 * 3600_000L)
        val arr = JSONArray(
            IttcApi.rpc(context, "my_recent_leads", JSONObject().put("p_from", from).put("p_limit", limit)).ifBlank { "[]" },
        )
        return arr.objects().mapNotNull { it.str("lead_id") }
    }

    /** ⭐ My starred leads, newest star first (lead_stars) */
    fun starredIds(context: Context, limit: Int = 100): List<String> =
        IttcApi.select(context, "lead_stars?select=lead_id&order=created_at.desc&limit=$limit").objects().mapNotNull { it.str("lead_id") }

    private fun found(o: JSONObject): Found? {
        val courses = o.optJSONArray("lead_courses").objects().mapNotNull { it.optJSONObject("course")?.str("name") }
        return Found(
            id = o.str("id") ?: return null,
            name = o.str("name").orEmpty(),
            phone = o.str("phone"),
            stage = o.optJSONObject("stage")?.str("name"),
            stageKind = o.optJSONObject("stage")?.str("kind"),
            course = courses.firstOrNull(),
            owner = o.optJSONObject("assignee")?.str("full_name"),
        )
    }

    /** "+880 1711-23" → "0171123" (any part of a number works) — as the Tracker */
    private fun searchDigits(input: String): String {
        val d = input.filter { it.isDigit() }
        return if (d.startsWith("880") && d.length > 3) "0" + d.substring(3) else d
    }

    // ------------------------------------------------------------------ small things
    /** "৳1,25,000" (the Tracker's en-IN grouping) */
    fun money(n: Double): String {
        val v = Math.round(n)
        val neg = v < 0
        val s = Math.abs(v).toString()
        val grouped = if (s.length <= 3) {
            s
        } else {
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        return (if (neg) "-৳" else "৳") + grouped
    }

    /** Forget the kept settings (sign out, pull to refresh) */
    fun forget() {
        visitSettingCache = null
        feeSettingCache = null
        canUseCache = null
    }

    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out += it }
        return out
    }

    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }
}
