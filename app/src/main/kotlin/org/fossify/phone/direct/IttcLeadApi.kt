package org.fossify.phone.direct

import android.content.Context
import org.fossify.phone.helpers.IttcBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🎓 The lead page, "Add a lead" and message templates in direct mode: the server's answers as
 * the signed-in person (its row security decides). Every call here waits for the network: only
 * off the main thread. Throws [IttcApi.ApiError] (status 0 = no internet).
 */
object IttcLeadApi {
    private const val CACHE_MS = 10 * 60_000L

    /** The Tracker's web app (a lead: /leads/<id>) */
    const val WEB = "https://lead.ittc.edu.bd"

    fun webUrl(leadId: String) = "$WEB/leads/$leadId"

    data class Course(val id: String, val name: String)

    data class Template(
        val id: String,
        val name: String,
        val body: String,
        /** "whatsapp", "sms" or "both" */
        val channels: String,
        /** null: every course */
        val courseIds: Set<String>?,
    ) {
        fun forChannel(channel: String) = channels == "both" || channels == channel

        /** As the Tracker: a template for some courses only shows for a lead of one of them */
        fun forCourse(courseId: String?) = courseIds.isNullOrEmpty() || (courseId != null && courseId in courseIds)
    }

    /** Someone with this number is a lead already */
    data class Duplicate(val leadId: String, val name: String, val stage: String?, val owner: String?, val canOpen: Boolean)

    // ------------------------------------------------------------------ one lead
    /** The lead with its calls and changes (phone_lead_detail); null: not found / not visible */
    fun detail(context: Context, leadId: String): JSONObject? {
        val text = IttcApi.rpc(context, "phone_lead_detail", JSONObject().put("p_lead_id", leadId)).trim()
        if (text.isEmpty() || text == "null") return null
        return try {
            JSONObject(text)
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ lists (kept 10 min)
    @Volatile private var coursesCache: Pair<Long, List<Course>>? = null
    @Volatile private var templatesCache: Pair<Long, List<Template>>? = null
    @Volatile private var instituteCache: Pair<Long, JSONObject>? = null

    fun courses(context: Context): List<Course> {
        coursesCache?.let { (at, list) -> if (System.currentTimeMillis() - at < CACHE_MS) return list }
        val arr = IttcApi.select(context, "courses?select=id,name&is_active=eq.true&order=sort_order")
        val out = ArrayList<Course>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("id") ?: continue
            out += Course(id, o.str("name") ?: continue)
        }
        coursesCache = System.currentTimeMillis() to out
        return out
    }

    fun templates(context: Context): List<Template> {
        templatesCache?.let { (at, list) -> if (System.currentTimeMillis() - at < CACHE_MS) return list }
        val arr = IttcApi.select(
            context,
            "message_templates?select=id,name,body,channels,course_ids&is_active=eq.true&order=sort_order.asc,name.asc",
        )
        val out = ArrayList<Template>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("id") ?: continue
            out += Template(
                id = id,
                name = o.str("name") ?: "",
                body = o.str("body") ?: continue,
                channels = o.str("channels") ?: "both",
                courseIds = o.optJSONArray("course_ids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() },
            )
        }
        templatesCache = System.currentTimeMillis() to out
        return out
    }

    /**
     * The institute's details for {institute}, {address}, {map_link}, {office_phone} (Settings →
     * Message templates); empty when this person may not read them
     */
    fun institute(context: Context): JSONObject {
        instituteCache?.let { (at, o) -> if (System.currentTimeMillis() - at < CACHE_MS) return o }
        val o = try {
            IttcApi.select(context, "app_settings?select=value&key=eq.message_templates")
                .optJSONObject(0)?.optJSONObject("value")?.optJSONObject("institute")
        } catch (e: IttcApi.ApiError) {
            if (e.status == 0) throw e
            null
        } ?: JSONObject()
        instituteCache = System.currentTimeMillis() to o
        return o
    }

    /** A new template / course may be wanted at once (pull to refresh, sign out) */
    fun forget() {
        coursesCache = null
        templatesCache = null
        instituteCache = null
    }

    // ------------------------------------------------------------------ add a lead
    /**
     * A new lead (create_lead, as the Tracker's form: only the columns sent, a course is needed;
     * the server picks the first stage and who gets it). Returns the new lead's id.
     */
    fun createLead(
        context: Context,
        name: String,
        phone: String,
        alt: String?,
        whatsapp: String?,
        courseId: String,
        note: String?,
    ): String {
        val row = JSONObject()
            .put("name", name.trim())
            .put("phone", IttcDirect.normalizeBd(phone) ?: phone.trim())
        alt?.trim()?.takeIf { it.isNotEmpty() }?.let { row.put("alt_phone", IttcDirect.normalizeBd(it) ?: it) }
        whatsapp?.trim()?.takeIf { it.isNotEmpty() }?.let { row.put("whatsapp_number", IttcDirect.normalizeBd(it) ?: it) }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { row.put("notes", it) }
        val text = IttcApi.rpc(
            context, "create_lead",
            JSONObject().put("p_row", row).put("p_course_ids", JSONArray().put(courseId)).put("p_tag_ids", JSONArray()),
        ).trim()
        return text.trim('"').takeIf { it.isNotEmpty() && it != "null" } ?: throw IttcApi.ApiError(500, "The lead was not saved.")
    }

    /**
     * Is this number a lead already? The kept list first (works without internet), then the
     * server's own check (every lead, also ones this person cannot open). null: no / unknown.
     */
    fun duplicateOf(context: Context, phone: String): Duplicate? {
        IttcDirect.lookupLead(context, phone)?.let { return Duplicate(it.leadId, it.name, it.stage, null, true) }
        val n = IttcDirect.normalizeBd(phone) ?: return null
        return try {
            val arr = JSONArray(IttcApi.rpc(context, "find_leads_by_phone", JSONObject().put("p_phone", n)))
            val o = arr.optJSONObject(0) ?: return null
            Duplicate(
                leadId = o.str("id") ?: return null,
                name = o.str("name") ?: "",
                stage = o.str("stage_name"),
                owner = o.str("assigned_to_name"),
                canOpen = o.optBoolean("can_open", false),
            )
        } catch (_: Throwable) {
            null // (the server says again when saving)
        }
    }

    // ------------------------------------------------------------------ templates: {name} …
    /** The Tracker's template words (others are kept as typed) */
    private val VARIABLES = setOf(
        "name", "first_name", "course", "batch", "batch_start", "fee", "full_fee", "discount", "final_fee", "paid",
        "due", "next_installment", "next_due_date", "visit_time", "next_call", "papers_missing", "student_id",
        "section", "my_name", "my_phone", "institute", "address", "map_link", "office_phone", "today",
    )
    private val WORD = Regex("\\{([a-z_]+)\\}")

    /**
     * {name}, {course} … filled as the Tracker does (the words this app does not know, e.g. fees,
     * become empty: the person can still edit the text before sending). lead: phone_lead_detail's
     * keys (or the kept lead's).
     */
    fun fill(body: String, lead: JSONObject, myName: String?, institute: JSONObject? = null): String {
        val name = lead.str("name").orEmpty()
        val ctx = mapOf(
            "name" to name,
            "first_name" to (name.trim().split(Regex("\\s+")).firstOrNull() ?: ""),
            "course" to lead.str("course").orEmpty(),
            "batch" to lead.str("batch").orEmpty(),
            "next_call" to (IttcBridge.parseTimeOf(lead.str("next_call"))?.let { dateText(it, true) } ?: ""),
            "my_name" to myName.orEmpty(),
            "institute" to institute?.str("name").orEmpty(),
            "address" to institute?.str("address").orEmpty(),
            "map_link" to institute?.str("map_link").orEmpty(),
            "office_phone" to institute?.str("phone").orEmpty(),
            "today" to dateText(System.currentTimeMillis(), false),
        )
        val text = WORD.replace(body) { m ->
            val key = m.groupValues[1]
            if (key !in ctx && key !in VARIABLES) m.value else ctx[key]?.trim().orEmpty()
        }
        return text.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** "18 Nov 2026" / "18 Nov 2026, 3:40 PM" (as the Tracker's en-GB dates) */
    private fun dateText(ms: Long, withTime: Boolean): String {
        val day = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(ms))
        return if (withTime) "$day, ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ms))}" else day
    }

    /** wa.me wants the number with its country code: a Bangladeshi "01…" is "8801…" */
    fun waNumber(number: String): String {
        IttcDirect.normalizeBd(number)?.let { return "88$it" }
        val d = number.filter { it.isDigit() }
        return if (d.startsWith("00")) d.substring(2) else d
    }

    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }
}
