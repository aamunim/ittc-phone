package org.fossify.phone.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.widget.Toast
import org.fossify.phone.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 🎓 ITTC 3.12: ITTC mode (work: leads, the Tracker) or Personal mode (a plain phone). Saved in the
 * "ittc" preferences; the listeners hear every change (of the mode, and of the admin's or the
 * person's mode settings) on the main thread.
 *
 * What Personal mode changes:
 *  - the main screen: no Leads tab, no lead search / bell, no lead pills or "Leads" chip in Call
 *    History, no "Lead" pills in Contacts, no lead matches on the dial pads, no leads-due badge
 *    ([IttcBridge.recentLead] / [IttcBridge.leadsFor] / [IttcBridge.lookup] answer "no lead");
 *  - a call: no lead card, no after-call screen, nothing queued for the server — except a lead's
 *    call as the admin (or the person, when allowed) chose: [leadCallInPersonal];
 *  - lead reminders and ITTC notifications wait ([IttcModeAuto] delivers them on the way back).
 *
 * The admin's controls (Settings → ITTC Phone in the Tracker, see [adminSettingsChanged]):
 * personal_mode_allowed, lock_office_hours, office_hours, lead_call_in_personal,
 * let_user_change_lead_call. Anything missing: the defaults (as before 3.12: Personal allowed).
 */
object IttcMode {
    private const val PREFS = "ittc"
    private const val KEY_PERSONAL = "ittc_mode_personal"
    private const val KEY_AUTO = "ittc_mode_auto"
    private const val KEY_HOURS = "ittc_mode_hours"
    private const val KEY_SIM_ON = "ittc_mode_sim_on"
    private const val KEY_SIM_ID = "ittc_mode_sim_id"
    private const val KEY_SIM_LABEL = "ittc_mode_sim_label"
    private const val KEY_LEAD_CALL = "ittc_mode_lead_call"
    private const val KEY_ADMIN = "ittc_mode_admin"

    /** What a lead's call does in Personal mode */
    const val LEAD_NOTHING = "nothing"
    const val LEAD_HINT = "hint"
    const val LEAD_LOG = "log"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var appContext: Context? = null

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val app = ctx.applicationContext ?: ctx
        if (appContext == null) appContext = app
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ============================================================================= the mode
    fun isPersonal(ctx: Context): Boolean =
        cached ?: try {
            prefs(ctx).getBoolean(KEY_PERSONAL, false)
        } catch (_: Throwable) {
            false
        }.also { cached = it }

    /** The mode without a context (false until something read it: ITTC, as before 3.12) */
    fun personalNow(): Boolean = cached ?: appContext?.let { isPersonal(it) } ?: false

    /**
     * Set the mode (no checks: see [request] for a person's switch). [reason]: "user", "auto"
     * (office hours), "admin" (the admin's settings), "tile", "signout".
     */
    fun setPersonal(ctx: Context, personal: Boolean, reason: String = "user") {
        if (isPersonal(ctx) == personal) return
        cached = personal
        prefs(ctx).edit().putBoolean(KEY_PERSONAL, personal).apply()
        val app = ctx.applicationContext ?: ctx
        // (what waited in Personal mode, the tile; never in the way of the switch itself)
        try {
            IttcModeAuto.modeChanged(app, personal, reason)
        } catch (_: Throwable) {
        }
        notifyListeners()
    }

    /** Can the mode be switched now (back to ITTC always; to Personal: see [blockReason]) */
    fun canSwitch(ctx: Context): Boolean = isPersonal(ctx) || blockReason(ctx) == null

    /** Personal mode may be chosen now */
    fun canBePersonal(ctx: Context): Boolean = blockReason(ctx) == null

    /** Why Personal mode cannot be chosen now (null: it can) */
    fun blockReason(ctx: Context, now: Long = System.currentTimeMillis()): String? {
        val a = admin(ctx)
        if (!a.personalAllowed) return ctx.getString(R.string.ittc_mode_blocked_off)
        if (a.lockOfficeHours && inOfficeHours(ctx, now)) {
            val end = nextBoundary(hours(ctx), now)?.at
            return if (end != null) ctx.getString(R.string.ittc_mode_blocked_hours_until, IttcModeAuto.timeText(ctx, end))
            else ctx.getString(R.string.ittc_mode_blocked_hours)
        }
        return null
    }

    /**
     * A person's switch (header button, side panel, tile): refused with a toast when the admin's
     * settings say no. Main thread. true: the mode is [personal] now.
     */
    fun request(ctx: Context, personal: Boolean, reason: String = "user"): Boolean {
        if (isPersonal(ctx) == personal) return true
        if (personal) {
            val why = blockReason(ctx)
            if (why != null) {
                try {
                    Toast.makeText(ctx.applicationContext, why, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                }
                return false
            }
        }
        setPersonal(ctx, personal, reason)
        return true
    }

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    internal fun notifyListeners() {
        val notify = Runnable { listeners.forEach { l -> try { l() } catch (_: Throwable) { } } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else main.post(notify)
    }

    // ============================================================================= a call
    /**
     * Is this call (on [handle]'s SIM) a personal one: the mode, unless "Office SIM is ITTC" is on —
     * then a call on the office SIM is ITTC and one on another SIM personal (the admin's settings
     * still win: no Personal mode, or ITTC locked in office hours). Any error: the mode.
     */
    fun personalFor(ctx: Context, handle: PhoneAccountHandle?): Boolean = personalForAccount(ctx, handle?.id)

    fun personalForAccount(ctx: Context, accountId: String?): Boolean {
        val mode = isPersonal(ctx)
        return try {
            val simId = officeSimId(ctx)
            if (!officeSimOn(ctx) || simId == null || accountId.isNullOrEmpty()) return mode
            if (accountId == simId) return false
            canBePersonal(ctx)
        } catch (_: Throwable) {
            mode
        }
    }

    /** What a lead's call does in Personal mode: [LEAD_NOTHING], [LEAD_HINT] or [LEAD_LOG] */
    fun leadCallInPersonal(ctx: Context): String {
        val a = admin(ctx)
        if (!a.letUserChangeLeadCall) return a.leadCall
        return cleanLeadCall(prefs(ctx).getString(KEY_LEAD_CALL, null)) ?: a.leadCall
    }

    fun canChooseLeadCall(ctx: Context): Boolean = admin(ctx).letUserChangeLeadCall

    fun setLeadCall(ctx: Context, value: String) {
        val v = cleanLeadCall(value) ?: return
        prefs(ctx).edit().putString(KEY_LEAD_CALL, v).apply()
    }

    private fun cleanLeadCall(v: String?): String? = v?.takeIf { it == LEAD_NOTHING || it == LEAD_HINT || it == LEAD_LOG }

    // ============================================================================= office SIM
    fun officeSimOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SIM_ON, false)

    /** The office SIM's phone account id (as Telecom and the call log name it) */
    fun officeSimId(ctx: Context): String? = prefs(ctx).getString(KEY_SIM_ID, null)?.takeIf { it.isNotEmpty() }

    fun officeSimLabel(ctx: Context): String? = prefs(ctx).getString(KEY_SIM_LABEL, null)?.takeIf { it.isNotEmpty() }

    fun setOfficeSim(ctx: Context, on: Boolean, id: String? = officeSimId(ctx), label: String? = officeSimLabel(ctx)) {
        prefs(ctx).edit().putBoolean(KEY_SIM_ON, on).putString(KEY_SIM_ID, id).putString(KEY_SIM_LABEL, label).apply()
        notifyListeners()
    }

    // ============================================================================= office hours
    /** Office hours: [days] as JavaScript's getDay (0 = Sunday … 6 = Saturday), [from] / [to] in minutes of the day */
    data class Hours(val days: Set<Int>, val from: Int, val to: Int) {
        val empty: Boolean get() = days.isEmpty() || from == to

        fun toJson(): JSONObject = JSONObject()
            .put("days", JSONArray().apply { days.sorted().forEach { put(it) } })
            .put("from", text(from))
            .put("to", text(to))

        companion object {
            val DEFAULT = Hours(setOf(6, 0, 1, 2, 3, 4), 9 * 60, 19 * 60)

            fun text(min: Int): String = "%02d:%02d".format(java.util.Locale.US, min / 60, min % 60)

            fun minutes(s: String?): Int? {
                val m = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").find(s?.trim() ?: return null) ?: return null
                return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            }

            fun parse(o: JSONObject?): Hours? {
                o ?: return null
                val arr = o.optJSONArray("days")
                val days = if (arr == null) DEFAULT.days else (0 until arr.length()).mapNotNull { i ->
                    arr.opt(i).let { v -> (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() }?.takeIf { it in 0..6 }
                }.toSet()
                return Hours(days, minutes(o.optString("from")) ?: DEFAULT.from, minutes(o.optString("to")) ?: DEFAULT.to)
            }
        }
    }

    /** The hours in use: the admin's when locked or when the person never set their own */
    fun hours(ctx: Context): Hours {
        val a = admin(ctx)
        if (a.lockOfficeHours) return a.hours
        val own = prefs(ctx).getString(KEY_HOURS, null) ?: return a.hours
        return try {
            Hours.parse(JSONObject(own)) ?: a.hours
        } catch (_: Throwable) {
            a.hours
        }
    }

    fun hoursLocked(ctx: Context): Boolean = admin(ctx).lockOfficeHours

    fun setHours(ctx: Context, h: Hours) {
        if (hoursLocked(ctx)) return
        prefs(ctx).edit().putString(KEY_HOURS, h.toJson().toString()).apply()
        notifyListeners()
    }

    fun autoSwitch(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoSwitch(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        notifyListeners()
    }

    fun inOfficeHours(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = inHours(hours(ctx), now)

    /** A switch of the office hours: at [at], the mode the hours want from then on ([personal]) */
    data class Boundary(val at: Long, val personal: Boolean)

    /** The office-hours spans around [now] (a week before to a week after; one over midnight when To ≤ From) */
    private fun spans(h: Hours, now: Long): List<Pair<Long, Long>> {
        if (h.empty) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        for (offset in -8..8) {
            val day = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, offset)
            }
            if ((day.get(Calendar.DAY_OF_WEEK) - 1) !in h.days) continue
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h.from / 60)
                set(Calendar.MINUTE, h.from % 60)
            }.timeInMillis
            val end = (day.clone() as Calendar).apply {
                if (h.to <= h.from) add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, h.to / 60)
                set(Calendar.MINUTE, h.to % 60)
            }.timeInMillis
            if (end > start) out += start to end
        }
        return out
    }

    fun inHours(h: Hours, now: Long): Boolean = spans(h, now).any { (s, e) -> now in s until e }

    /** The next time the office hours start or end after [now] (null: no office hours) */
    fun nextBoundary(h: Hours, now: Long = System.currentTimeMillis()): Boundary? {
        val all = spans(h, now)
        if (all.isEmpty()) return null
        var state = all.any { (s, e) -> now in s until e }
        all.flatMap { listOf(it.first, it.second) }.filter { it > now }.distinct().sorted().forEach { t ->
            val inside = all.any { (s, e) -> t in s until e }
            if (inside != state) return Boundary(t, personal = !inside)
            state = inside
        }
        return null
    }

    /** The last time the office hours started or ended, at or before [now] (null: none in the last week) */
    fun lastBoundary(h: Hours, now: Long = System.currentTimeMillis()): Boundary? {
        val all = spans(h, now)
        if (all.isEmpty()) return null
        all.flatMap { listOf(it.first, it.second) }.filter { it <= now }.distinct().sortedDescending().forEach { t ->
            val inside = all.any { (s, e) -> t in s until e }
            val before = all.any { (s, e) -> (t - 1) in s until e }
            if (inside != before) return Boundary(t, personal = !inside)
        }
        return null
    }

    // ============================================================================= the admin's settings
    data class Admin(
        val personalAllowed: Boolean = true,
        val lockOfficeHours: Boolean = false,
        val hours: Hours = Hours.DEFAULT,
        val leadCall: String = LEAD_HINT,
        val letUserChangeLeadCall: Boolean = false,
    ) {
        /** Anything the admin limits (the settings screen says so) */
        val limits: Boolean get() = !personalAllowed || lockOfficeHours
    }

    @Volatile
    private var adminCache: Pair<String, Admin>? = null

    fun admin(ctx: Context): Admin {
        val raw = try {
            prefs(ctx).getString(KEY_ADMIN, null)
        } catch (_: Throwable) {
            null
        } ?: return Admin()
        adminCache?.let { if (it.first == raw) return it.second }
        val parsed = try {
            parseAdmin(JSONObject(raw))
        } catch (_: Throwable) {
            Admin()
        }
        adminCache = raw to parsed
        return parsed
    }

    private fun parseAdmin(o: JSONObject): Admin = Admin(
        personalAllowed = o.opt("personal_mode_allowed") != false,
        lockOfficeHours = o.opt("lock_office_hours") == true,
        hours = Hours.parse(o.optJSONObject("office_hours")) ?: Hours.DEFAULT,
        leadCall = cleanLeadCall(o.optString("lead_call_in_personal")) ?: LEAD_HINT,
        letUserChangeLeadCall = o.opt("let_user_change_lead_call") == true,
    )

    /**
     * The admin's ITTC Phone settings came (the server's or the Tracker's phone_meta "settings";
     * any thread). Kept when they changed; then the mode follows them (no Personal mode → ITTC; ITTC
     * locked in office hours → ITTC now if it is office hours) and the switch times are set again.
     */
    fun adminSettingsChanged(ctx: Context, settings: JSONObject?) {
        settings ?: return
        try {
            val keep = JSONObject()
            listOf("personal_mode_allowed", "lock_office_hours", "office_hours", "lead_call_in_personal", "let_user_change_lead_call")
                .forEach { k -> if (settings.has(k) && !settings.isNull(k)) keep.put(k, settings.get(k)) }
            val text = keep.toString()
            val p = prefs(ctx)
            if (p.getString(KEY_ADMIN, null) == text) return
            p.edit().putString(KEY_ADMIN, text).apply()
            adminCache = null
            enforce(ctx)
            IttcModeAuto.reschedule(ctx.applicationContext ?: ctx)
            notifyListeners()
        } catch (_: Throwable) {
        }
    }

    /** The admin's limits on the mode now (any thread) */
    fun enforce(ctx: Context) {
        if (!isPersonal(ctx)) return
        val a = admin(ctx)
        if (!a.personalAllowed || (a.lockOfficeHours && inOfficeHours(ctx))) setPersonal(ctx, false, "admin")
    }

    /** Signed out of ITTC: back to ITTC mode, the admin's settings forgotten, no switch alarms */
    fun signedOut(ctx: Context) {
        try {
            prefs(ctx).edit().remove(KEY_ADMIN).apply()
            adminCache = null
            setPersonal(ctx, false, "signout")
            IttcModeAuto.cancel(ctx.applicationContext ?: ctx)
        } catch (_: Throwable) {
        }
    }
}
