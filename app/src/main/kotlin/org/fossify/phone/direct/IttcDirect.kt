package org.fossify.phone.direct

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.SubscriptionManager
import org.fossify.phone.BuildConfig
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcToday
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 🔑 ITTC Phone signed in by itself (3.2): the same work as with the Tracker, straight with the
 * ITTC server.
 *  - When: signed in here, allowed (the admin can stop a person), and either the person chose
 *    "Run without the Tracker" or the Tracker is not there / not signed in. Never both at once:
 *    the Tracker is told (provider "direct_mode") and then sends no calls itself.
 *  - The lists (leads, stages, people, settings, who is a lead) are kept in private files, so the
 *    app opens at once and works without internet; changes go through a queue ("outbox") that is
 *    sent when there is internet, each item once.
 *  - Only calls with numbers that are leads are sent; personal calls never leave the phone.
 */
object IttcDirect {
    private const val PREFS = "ittc_direct"
    private const val LEADS = "direct_leads.json"
    private const val META = "direct_meta.json"
    private const val DIR = "direct_directory.json"
    private const val OUTBOX = "direct_outbox.json"
    private const val JOB_ID = 73021

    private val io = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val configured: Boolean get() = IttcApi.configured

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(context: Context, name: String) = File(context.applicationContext.filesDir, name)

    // ================================================================== who / which way
    fun isSignedIn(context: Context): Boolean = configured && IttcAuth.isSignedIn(context)

    fun email(context: Context): String? = IttcAuth.session(context)?.email

    /** The person chose to run without the Tracker even when it is installed */
    fun preferDirect(context: Context): Boolean = prefs(context).getBoolean("prefer_direct", false)

    fun setPreferDirect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prefer_direct", on).apply()
        modeAt = 0
        lastLeadsText = null
        syncTrackerFlag(context)
        changed(context)
    }

    @Volatile private var modeAt = 0L
    @Volatile private var modeActive = false

    /**
     * Working by itself right now? (cached for 30 s: it asks the Tracker whether it is signed in)
     */
    fun isActive(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - modeAt < 30_000) return modeActive
        val active = isSignedIn(context) && prefs(context).getBoolean("allowed", true) &&
            (preferDirect(context) || !IttcBridge.trackerSignedIn(context)) &&
            // (a Tracker older than 2.11 on the phone sends every call with its own id: working by
            // itself then would save each lead call twice)
            (prefs(context).getString("tracker_device_id", null) != null || !IttcBridge.trackerSignedIn(context) ||
                !IttcBridge.trackerInstalled(context))
        modeActive = active
        modeAt = now
        return active
    }

    /** After a call with a number that is no lead: offer "Add as a lead" (on by default) */
    fun unknownAfterCall(context: Context): Boolean = prefs(context).getBoolean("unknown_after_call", true)

    fun setUnknownAfterCall(context: Context, on: Boolean) = prefs(context).edit().putBoolean("unknown_after_call", on).apply()

    /** What the person sees: their name and role from the last check */
    fun me(context: Context): JSONObject? = prefs(context).getString("me", null)?.let {
        try {
            JSONObject(it)
        } catch (_: Throwable) {
            null
        }
    }

    /** The admin's settings (note presets, next call by result, daily target, lowest version) */
    fun settings(context: Context): JSONObject = prefs(context).getString("settings", null)?.let {
        try {
            JSONObject(it)
        } catch (_: Throwable) {
            null
        }
    } ?: JSONObject()

    /** Why the last sign-out happened (shown once on the account screen) */
    fun lastMessage(context: Context): String? = prefs(context).getString("message", null)

    fun clearMessage(context: Context) = prefs(context).edit().remove("message").apply()

    /** This install's id (the server's "device"); kept across sign-ins */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString("device_id", null)?.let { return it }
        val id = "ittc-" + UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }

    /**
     * The id calls are sent with: the Tracker's own (when it is on this phone), so a call sent by
     * both apps is kept once by the server; else this install's
     */
    fun callDeviceId(context: Context): String = prefs(context).getString("tracker_device_id", null) ?: deviceId(context)

    fun setTrackerDeviceId(context: Context, id: String) {
        if (prefs(context).getString("tracker_device_id", null) != id) {
            prefs(context).edit().putString("tracker_device_id", id).apply()
            modeAt = 0
        }
    }

    /** Too old for the admin's lowest version: it must be updated from Google Play */
    fun updateNeeded(context: Context): Boolean {
        val min = settings(context).optString("min_version").takeIf { it.isNotBlank() && it != "null" } ?: return false
        return compareVersions(BuildConfig.VERSION_NAME, min) < 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    // ================================================================== sign in / out
    /** Sign in (off the main thread). Throws with a readable message. */
    fun signIn(context: Context, email: String, password: String) {
        val app = context.applicationContext
        val t = try {
            IttcApi.signIn(email, password)
        } catch (e: IttcApi.ApiError) {
            throw IllegalStateException(
                when {
                    e.status == 0 -> "No internet. Please try again."
                    e.status == 400 -> "Wrong e-mail or password."
                    else -> e.message ?: "Could not sign in."
                },
            )
        }
        // (changes kept from an earlier sign-in go only with the same person; checked before the new
        // token is kept, so nothing of another person's is ever sent with it)
        synchronized(outboxLock) {
            val owner = prefs(app).getString("outbox_user", null)
            if (owner != null && owner != t.userId) file(app, OUTBOX).delete()
            prefs(app).edit().putString("outbox_user", t.userId).commit()
        }
        generation++
        listOf(LEADS, META, DIR).forEach { file(app, it).delete() }
        leadsCache = null
        metaCache = null
        dirCache = null
        IttcAuth.save(app, t)
        val ok = checkSession(app, fresh = true)
        if (!ok) {
            IttcAuth.clear(app)
            val msg = lastMessage(app) ?: "Could not check your sign-in. Please try again."
            clearMessage(app)
            throw IllegalStateException(msg)
        }
        prefs(app).edit().putBoolean("allowed", true).apply()
        modeAt = 0
        // (now, not later: a Tracker 2.11+ gives its phone id with the answer, which direct mode needs)
        IttcBridge.setTrackerDirect(app, preferDirect(app) || !IttcBridge.trackerSignedIn(app))
        modeAt = 0
        refresh(app, force = true, directory = true)
        kick(app)
    }

    /** Signed out here (the server is told when there is internet) */
    fun signOut(context: Context) {
        val app = context.applicationContext
        val access = IttcAuth.session(app)?.access
        val device = deviceId(app)
        sender.execute {
            try {
                if (access != null) {
                    IttcApi.rpcWith(access, "ittc_phone_forget", JSONObject().put("p_device_id", device))
                    IttcApi.signOutServer(access)
                }
            } catch (_: Throwable) {
            }
        }
        clearLocal(app, null)
    }

    /**
     * The server ended it (signed out by an admin, not allowed, password changed). Changes not sent
     * yet stay on the phone: they go if the same person signs in again (another person: deleted).
     */
    fun signedOutByServer(context: Context, message: String) {
        clearLocal(context.applicationContext, message, keepOutbox = true)
    }

    private fun clearLocal(app: Context, message: String?, keepOutbox: Boolean = false) {
        IttcAuth.clear(app)
        generation++
        listOf(LEADS, META, DIR).forEach { file(app, it).delete() }
        if (!keepOutbox) synchronized(outboxLock) { file(app, OUTBOX).delete() }
        leadsCache = null
        ownCache = null
        metaCache = null
        dirCache = null
        lastLeadsText = null
        IttcBridge.clearCaches()
        IttcLeadApi.forget()
        IttcAdmitApi.forget()
        IttcVisitFeeApi.forget()
        IttcToday.clear(app)
        org.fossify.phone.helpers.IttcNoteDraft.clear(app)
        // (receipt pictures with students' names and amounts)
        try {
            File(app.cacheDir, "ittc_receipts").deleteRecursively()
        } catch (_: Throwable) {
        }
        org.fossify.phone.helpers.IttcDialer.stop(app)
        prefs(app).edit().remove("me").remove("settings").remove("last_refresh").remove("last_check")
            .remove("last_directory").remove("refresh_error").remove("tracker_device_id").apply()
        if (message != null) prefs(app).edit().putString("message", message).apply()
        IttcRealtime.stop()
        IttcNotify.stop()
        IttcReminders.cancelAll(app, notifications = true)
        modeAt = 0
        modeActive = false
        syncTrackerFlag(app)
        changed(app)
    }

    /**
     * Ask the server: still allowed? signed out by an admin? the settings, who I am (also every
     * few minutes). false: not allowed (and signed out here).
     */
    fun checkSession(context: Context, fresh: Boolean = false): Boolean {
        val app = context.applicationContext
        val info = JSONObject()
            .put("device_label", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("app_version", BuildConfig.VERSION_NAME)
        val answer = try {
            JSONObject(IttcApi.rpc(app, "ittc_phone_session", JSONObject().put("p_device_id", deviceId(app)).put("p_info", info).put("p_fresh", fresh)))
        } catch (e: IttcApi.ApiError) {
            // (a fresh sign-in: say why it could not be checked)
            if (fresh) {
                prefs(app).edit().putString(
                    "message",
                    when {
                        e.status == 404 -> "The ITTC server is not updated for ITTC Phone yet. Ask your admin to run the database update (App update → Database only)."
                        e.status == 0 -> "No internet. Please try again."
                        else -> "Could not check your sign-in (${e.status}: ${e.message}). Please try again."
                    },
                ).apply()
                return false
            }
            // (no internet / the server busy: keep working with what is kept)
            return e.status == 0 || e.status >= 500
        } catch (_: Throwable) {
            return true
        }
        if (!answer.optBoolean("allowed", false)) {
            val msg = when (answer.optString("reason")) {
                "not_allowed" -> "Your admin turned off signing in to ITTC Phone for you."
                "signed_out" -> "Your admin signed this phone out."
                else -> "Your account is not active."
            }
            prefs(app).edit().putBoolean("allowed", false).apply()
            // (changes not sent yet stay: they go if this person may sign in again)
            clearLocal(app, msg, keepOutbox = true)
            return false
        }
        prefs(app).edit()
            .putBoolean("allowed", true)
            .putString("me", answer.optJSONObject("me")?.toString())
            .putString("settings", answer.optJSONObject("settings")?.toString())
            .putLong("last_check", System.currentTimeMillis())
            .apply()
        return true
    }

    /** Tell the Tracker (if installed) whether ITTC Phone sends its own lead calls now */
    fun syncTrackerFlag(context: Context) {
        val app = context.applicationContext
        val on = isSignedIn(app) && prefs(app).getBoolean("allowed", true) &&
            (preferDirect(app) || !IttcBridge.trackerSignedIn(app))
        io.execute { IttcBridge.setTrackerDirect(app, on) }
    }

    // ================================================================== the lists
    @Volatile private var leadsCache: List<IttcBridge.PhoneLead>? = null
    @Volatile private var metaCache: IttcBridge.PhoneMeta? = null
    @Volatile private var dirCache: Map<String, IttcBridge.Lead>? = null

    fun leads(context: Context): List<IttcBridge.PhoneLead> {
        leadsCache?.let { return it }
        val arr = readArray(file(context, LEADS))
        val out = ArrayList<IttcBridge.PhoneLead>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> phoneLead(o)?.let(out::add) }
        leadsCache = out
        return out
    }

    fun meta(context: Context): IttcBridge.PhoneMeta? {
        metaCache?.let { return it }
        val f = file(context, META)
        if (!f.exists()) return null
        return try {
            IttcBridge.parseMeta(f.readText())?.also { metaCache = it }
        } catch (_: Throwable) {
            null
        }
    }

    /** The lead with this number (from the kept list: works without internet) */
    fun lookupLead(context: Context, number: String?): IttcBridge.Lead? {
        val n = normalizeBd(number) ?: return null
        return leadFor(context, sha256(n))
    }

    @Volatile private var ownCache: Map<String, IttcBridge.Lead>? = null

    /**
     * Who is a lead: my own leads first (from the list that comes every few seconds — a lead
     * assigned a moment ago is known on the call screen before the whole list comes again), then
     * the "who is a lead" list (every lead I can see, fetched every 15 minutes)
     */
    private fun leadFor(context: Context, hash: String): IttcBridge.Lead? =
        ownLeads(context)[hash] ?: directory(context)[hash]

    private fun ownLeads(context: Context): Map<String, IttcBridge.Lead> {
        ownCache?.let { return it }
        val map = HashMap<String, IttcBridge.Lead>()
        leads(context).forEach { l ->
            val lead = IttcBridge.Lead(l.leadId, l.name, l.rating, l.stage, l.course, l.lastNote)
            listOf(l.phone, l.altPhone, l.whatsapp).forEach { n -> normalizeBd(n)?.let { map[sha256(it)] = lead } }
        }
        ownCache = map
        return map
    }

    private fun directory(context: Context): Map<String, IttcBridge.Lead> {
        dirCache?.let { return it }
        val f = file(context, DIR)
        val map = HashMap<String, IttcBridge.Lead>()
        try {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val hash = o.optString("hash").takeIf { it.isNotEmpty() } ?: continue
                    map[hash] = IttcBridge.Lead(
                        leadId = o.optString("lead_id"),
                        name = o.optString("name"),
                        rating = o.str("rating"),
                        stage = o.str("stage"),
                        course = o.str("course"),
                        lastNote = o.str("last_note"),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        dirCache = map
        return map
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    private fun phoneLead(o: JSONObject): IttcBridge.PhoneLead? {
        val id = o.str("lead_id") ?: return null
        return IttcBridge.PhoneLead(
            leadId = id,
            name = o.str("name") ?: "",
            phone = o.str("phone"),
            altPhone = o.str("alt_phone"),
            whatsapp = o.str("whatsapp_number"),
            stage = o.str("stage"),
            course = o.str("course"),
            batch = o.str("batch"),
            rating = o.str("rating"),
            nextCall = IttcBridge.parseTimeOf(o.str("next_call")),
            lastContacted = IttcBridge.parseTimeOf(o.str("last_contacted")),
            attempts = o.optInt("call_attempts"),
            lastNote = o.str("last_note"),
            stageId = o.str("stage_id"),
            courseId = o.str("course_id"),
            assignedTo = o.str("assigned_to"),
        )
    }

    private val refreshLock = Any()
    private var refreshing = false
    private var again = false
    private var againForce = false
    private var againDirectory = false
    @Volatile private var lastDone = 0L

    /** Changes with every sign-in / sign-out: a list fetched for the last person is never kept */
    @Volatile private var generation = 0

    /**
     * Fetch the lists again (off the main thread). At most every 20 s unless forced (forced: not
     * again within 3 s); the "who is a lead" list every 15 min, or with [directory]. Also checks the
     * session every 5 min. Asked while one runs: it runs once more after it.
     */
    fun refresh(context: Context, force: Boolean = false, directory: Boolean = false) {
        val app = context.applicationContext
        if (!isSignedIn(app)) return
        synchronized(refreshLock) {
            if (refreshing) {
                again = true
                againForce = againForce || force
                againDirectory = againDirectory || directory
                return
            }
            refreshing = true
        }
        var f = force
        var d = directory
        while (true) {
            var more = false
            try {
                if (!(f && !d && System.currentTimeMillis() - lastDone < 3_000)) refreshOnce(app, f, d)
            } finally {
                // (one decision under one lock: either this runner takes the next ask, or it is done)
                synchronized(refreshLock) {
                    if (again) {
                        again = false
                        f = againForce
                        d = againDirectory
                        againForce = false
                        againDirectory = false
                        more = true
                    } else {
                        refreshing = false
                    }
                }
            }
            if (!more) return
        }
    }

    private fun refreshOnce(app: Context, force: Boolean, directory: Boolean) {
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (!isSignedIn(app)) return
        if (!force && now - p.getLong("last_refresh", 0) < 20_000) return
        val gen = generation
        try {
            if (now - p.getLong("last_check", 0) > 5 * 60_000 && !checkSession(app)) {
                if (isSignedIn(app)) p.edit().putString("refresh_error", "Could not check your sign-in").apply()
                return
            }
            val text = IttcApi.rpc(app, "my_phone_data").trim()
            // (no answer: the account is not active any more — the session check says so next)
            if (text == "null" || text.isEmpty()) {
                p.edit().putLong("last_check", 0).putString("refresh_error", "Your account is not active").apply()
                return
            }
            val data = JSONObject(text)
            val leads = data.optJSONArray("leads") ?: JSONArray()
            data.remove("leads")
            val dir = if (directory || now - p.getLong("last_directory", 0) > 15 * 60_000 || !file(app, DIR).exists()) {
                fetchDirectory(app)
            } else {
                null
            }
            // (signed out / someone else signed in meanwhile: this list is not theirs)
            if (gen != generation || !isSignedIn(app)) return
            val leadsText = leads.toString()
            val changedLeads = leadsText != lastLeadsText
            lastLeadsText = leadsText
            // (the same list again: nothing rewritten or read again)
            if (changedLeads) {
                writeAtomic(file(app, LEADS), leadsText)
                leadsCache = null
                ownCache = null
            }
            writeAtomic(file(app, META), data.toString())
            data.optJSONObject("settings")?.let { p.edit().putString("settings", it.toString()).apply() }
            metaCache = null
            if (dir != null) {
                writeAtomic(file(app, DIR), dir.toString())
                p.edit().putLong("last_directory", now).apply()
                dirCache = null
            }
            lastDone = System.currentTimeMillis()
            p.edit().putLong("last_refresh", now).remove("refresh_error").apply()
            // (the Tracker forgets "ITTC Phone works by itself" after 2 hours unless told again)
            if (now - p.getLong("last_flag", 0) > 10 * 60_000) {
                p.edit().putLong("last_flag", now).apply()
                syncTrackerFlag(app)
            }
            // (Recents asks again and the alarms are set again only when something changed)
            if (changedLeads || dir != null) IttcBridge.forgetRecents()
            // (also every hour: a follow-up more than a day away gets its alarm when it comes near)
            if ((changedLeads || now - lastScheduled > 3600_000L) && isActive(app)) {
                lastScheduled = now
                IttcReminders.schedule(app, leads(app))
            }
        } catch (e: Throwable) {
            p.edit().putString("refresh_error", e.message ?: "Could not update").apply()
        }
        changed(app)
    }

    /** Only leads (alumni / bank numbers cannot take a result); an older database: the whole list */
    private fun fetchDirectory(app: Context): JSONArray =
        try {
            JSONArray(IttcApi.rpc(app, "phone_lead_directory"))
        } catch (e: IttcApi.ApiError) {
            if (e.status != 404) throw e
            JSONArray(IttcApi.rpc(app, "my_lead_directory"))
        }

    @Volatile private var lastLeadsText: String? = null
    @Volatile private var lastScheduled = 0L

    private var pendingRefresh: Runnable? = null
    private var pendingDue = 0L

    /**
     * Refresh soon. With a delay, several asks close together make one (the live pings);
     * [directory] false: the "who is a lead" list only when it is due.
     */
    fun refreshAsync(context: Context, force: Boolean = false, delayMs: Long = 0, directory: Boolean = false) {
        val app = context.applicationContext
        if (delayMs <= 0) {
            io.execute { refresh(app, force, directory) }
            return
        }
        main.post {
            val due = System.currentTimeMillis() + delayMs
            // (one already waiting that comes as soon: it does for this ask too — a stream of pings
            // never pushes it back)
            if (pendingRefresh != null && pendingDue <= due) return@post
            pendingRefresh?.let { main.removeCallbacks(it) }
            val r = Runnable {
                pendingRefresh = null
                io.execute { refresh(app, force, directory) }
            }
            pendingRefresh = r
            pendingDue = due
            main.postDelayed(r, delayMs)
        }
    }

    fun lastRefresh(context: Context): Long = prefs(context).getLong("last_refresh", 0)
    fun refreshError(context: Context): String? = prefs(context).getString("refresh_error", null)

    // ================================================================== watching for changes
    private val observers = java.util.Collections.synchronizedSet(HashSet<ContentObserver>())

    fun addObserver(o: ContentObserver) {
        observers.add(o)
    }

    fun removeObserver(o: ContentObserver?) {
        if (o != null) observers.remove(o)
    }

    private fun changed(context: Context) {
        val list = synchronized(observers) { observers.toList() }
        main.post { list.forEach { it.dispatchChange(false, null) } }
    }

    // ================================================================== calls
    /**
     * A lead call ended (direct mode): the call's row from the call log goes in the queue (only for
     * leads; called off the main thread)
     */
    fun queueCall(context: Context, callLogId: String) {
        val app = context.applicationContext
        val row = callRow(app, callLogId) ?: return
        if (lookupLead(app, row.number) == null) return
        enqueue(app, JSONObject().put("kind", "call").put("call", row.toJson()))
    }

    /**
     * A lead call by its number and start time (its call-log row may not be written yet): queued,
     * found when sent. [refreshFirst]: the number just became a lead (Add lead) — the list first.
     */
    fun queueCallAt(context: Context, number: String, startedAt: Long, refreshFirst: Boolean = true) {
        val app = context.applicationContext
        io.execute {
            if (refreshFirst) refresh(app, force = true, directory = true)
            enqueue(app, JSONObject().put("kind", "call_at").put("number", number).put("started_at", startedAt))
        }
    }

    internal data class Row(val id: String, val number: String, val direction: String, val durationSec: Long, val dateMs: Long, val sim: String?) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("hash", sha256(number))
            .put("direction", direction)
            .put("duration_sec", durationSec)
            .put("called_at", iso(dateMs))
            .put("sim", sim ?: JSONObject.NULL)
    }

    @SuppressLint("MissingPermission")
    internal fun callRow(context: Context, callLogId: String): Row? = rows(context, "${CallLog.Calls._ID} = ?", arrayOf(callLogId)).firstOrNull()

    /** The call with this number nearest to when it began (±15 s) */
    internal fun callRowAt(context: Context, number: String, startedAt: Long): Row? {
        val n = normalizeBd(number) ?: return null
        return rows(
            context,
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            arrayOf((startedAt - 15_000).toString(), (startedAt + 15_000).toString()),
        ).filter { it.number == n }.minByOrNull { kotlin.math.abs(it.dateMs - startedAt) }
    }

    @SuppressLint("MissingPermission")
    private fun rows(context: Context, where: String, args: Array<String>): List<Row> {
        val out = ArrayList<Row>()
        val sims = simLabels(context)
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.PHONE_ACCOUNT_ID),
                where, args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = normalizeBd(c.getString(1)) ?: continue
                    val dir = when (c.getInt(2)) {
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "incoming"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> continue
                    }
                    out += Row(c.getLong(0).toString(), number, dir, c.getLong(3), c.getLong(4), c.getString(5)?.let { sims[it] })
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun simLabels(context: Context): Map<String, String> =
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val list = sm?.activeSubscriptionInfoList.orEmpty()
            if (list.size < 2) emptyMap()
            else list.flatMap { s ->
                val label = s.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${s.simSlotIndex + 1}"
                listOfNotNull(s.subscriptionId.toString() to label, s.iccId?.takeIf { it.isNotBlank() }?.let { it to label })
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }

    // ================================================================== the queue (outbox)
    private val outboxLock = Any()

    /** Add one change; it is sent at once when there is internet, else later (kept on the phone) */
    fun enqueue(context: Context, item: JSONObject) {
        val app = context.applicationContext
        synchronized(outboxLock) {
            val arr = readArray(file(app, OUTBOX))
            item.put("qid", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
            // (the phone id fixed now: a call and its result always go with the same one)
            if (!item.has("device")) {
                val rowId = item.optString("device_call_id")
                var device: String? = null
                if (rowId.isNotEmpty()) {
                    for (k in 0 until arr.length()) {
                        val q = arr.optJSONObject(k) ?: continue
                        if (q.optString("kind") == "call" && q.optJSONObject("call")?.optString("id") == rowId) device = q.optString("device")
                    }
                }
                item.put("device", device?.takeIf { it.isNotBlank() } ?: callDeviceId(app))
            }
            arr.put(item)
            // (never grows without end: the oldest go first)
            val keep = JSONArray()
            for (i in maxOf(0, arr.length() - 1000) until arr.length()) keep.put(arr.get(i))
            writeAtomic(file(app, OUTBOX), keep.toString())
        }
        kick(app)
    }

    fun pendingCount(context: Context): Int = synchronized(outboxLock) { readArray(file(context, OUTBOX)).length() }

    /** Changes to this lead still waiting (calls waiting for their call-log row not counted) */
    fun pendingFor(context: Context, leadId: String): Int = synchronized(outboxLock) {
        val arr = readArray(file(context, OUTBOX))
        (0 until arr.length()).count { i ->
            val o = arr.optJSONObject(i)
            o != null && o.optString("lead_id") == leadId && o.optString("kind") !in setOf("call", "call_at")
        }
    }

    /** Send what is waiting (off the main thread); if something is left, Android runs it again with internet */
    fun kick(context: Context) {
        val app = context.applicationContext
        sender.execute {
            val left = try {
                sendAll(app)
            } catch (_: Throwable) {
                1
            }
            scheduleRetry(app, left > 0)
        }
    }

    private fun scheduleRetry(app: Context, needed: Boolean) {
        try {
            val js = app.getSystemService(JobScheduler::class.java) ?: return
            if (!needed) {
                js.cancel(JOB_ID)
                return
            }
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(app, IttcOutboxJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setMinimumLatency(30_000)
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build(),
            )
        } catch (_: Throwable) {
        }
    }

    /** The number of items still waiting */
    private val sendLock = Any()

    /** The number of items still waiting (one sender at a time: nothing goes twice) */
    internal fun sendAll(app: Context): Int = synchronized(sendLock) { sendAllNow(app) }

    private fun sendAllNow(app: Context): Int {
        if (!isSignedIn(app)) return 0
        val items = synchronized(outboxLock) { readArray(file(app, OUTBOX)) }
        val now = System.currentTimeMillis()
        val gen = generation
        for (i in 0 until items.length()) {
            // (signed out / someone else signed in meanwhile: the rest never goes with their token)
            if (gen != generation || !isSignedIn(app)) return pendingCount(app)
            val item = items.optJSONObject(i) ?: continue
            val qid = item.optString("qid")
            val result = try {
                send(app, item)
            } catch (e: IttcApi.ApiError) {
                when {
                    e.status == 0 -> return pendingCount(app) // no internet: all wait
                    // refused: it would never go (busy / rate-limited / timed out / not found while the
                    // server reloads: later)
                    // (an account made inactive: kept — the session check signs out and keeps them)
                    e.message?.contains("not active", true) == true -> Keep
                    e.status in 400..499 && e.status !in setOf(401, 404, 408, 425, 429) -> Done
                    else -> Keep
                }
            } catch (_: Throwable) {
                Keep
            }
            when {
                result == Done || now - item.optLong("at") > 7 * 24 * 3600_000L -> remove(app, qid)
                result is Update -> update(app, result.item)
            }
        }
        changed(app)
        return pendingCount(app)
    }

    private sealed class Result
    private object Done : Result()
    private object Keep : Result()
    private class Update(val item: JSONObject) : Result()

    private fun rpc(app: Context, name: String, args: JSONObject) = IttcApi.rpc(app, name, args)

    private fun send(app: Context, item: JSONObject): Result = when (item.optString("kind")) {
        "call" -> {
            val calls = JSONArray().put(item.getJSONObject("call"))
            rpc(app, "sync_phone_calls", JSONObject().put("p_device_id", deviceOf(app, item)).put("p_calls", calls))
            Done
        }
        // (a lead call whose call-log row was not written yet when it ended: found by number and time)
        "call_at" -> {
            val row = callRowAt(app, item.getString("number"), item.getLong("started_at"))
            when {
                // (only calls with leads ever leave the phone)
                lookupLead(app, item.getString("number")) == null ->
                    if (System.currentTimeMillis() - item.optLong("at") < 30 * 60_000) Keep else Done
                row != null -> {
                    rpc(app, "sync_phone_calls", JSONObject().put("p_device_id", deviceOf(app, item)).put("p_calls", JSONArray().put(row.toJson())))
                    Done
                }
                System.currentTimeMillis() - item.optLong("at") < 30 * 60_000 -> Keep
                else -> Done
            }
        }
        "outcome" -> sendOutcome(app, item)
        "note" -> {
            rpc(app, "add_lead_note", JSONObject().put("p_lead_ids", JSONArray().put(item.getString("lead_id"))).put("p_note", item.getString("note")))
            Done
        }
        "stage" -> {
            moveStage(app, item.getString("lead_id"), item.getString("stage_id"), item.optString("note").takeIf { it.isNotBlank() }, item.optLong("due_at").takeIf { it > 0 })
            Done
        }
        "next" -> {
            rpc(app, "schedule_follow_up", JSONObject().put("p_lead_id", item.getString("lead_id")).put("p_due_at", iso(item.getLong("due_at"))))
            Done
        }
        "edit" -> {
            IttcApi.patch(app, "leads?id=eq.${item.getString("lead_id")}", item.getJSONObject("values"))
            Done
        }
        "template" -> {
            rpc(
                app, "log_template_sent",
                JSONObject().put("p_lead_id", item.getString("lead_id")).put("p_template_id", item.getString("template_id"))
                    .put("p_channel", item.getString("channel")).put("p_edited", item.optBoolean("edited")),
            )
            Done
        }
        "handover" -> {
            rpc(
                app, "hand_over_leads",
                JSONObject().put("p_lead_ids", JSONArray().put(item.getString("lead_id"))).put("p_to", item.getString("to"))
                    .apply { item.optString("note").takeIf { it.isNotBlank() }?.let { put("p_note", it) } },
            )
            Done
        }
        else -> Done
    }

    private fun deviceOf(app: Context, item: JSONObject): String =
        item.optString("device").takeIf { it.isNotBlank() } ?: callDeviceId(app)

    private val OUTCOME_LABELS = mapOf(
        "connected" to "Connected", "no_answer" to "No answer", "busy" to "Busy",
        "switched_off" to "Switched off", "wrong_number" to "Wrong number", "call_back" to "Call back later",
    )

    /**
     * The after-call result, as the Tracker sends it: on the call itself (its call-log row), else a
     * note; then the stage; then the next call. Each part once (a part done is marked).
     */
    private fun sendOutcome(app: Context, o: JSONObject): Result {
        val leadId = o.getString("lead_id")
        val note = o.optString("note").trim().takeIf { it.isNotEmpty() }
        val outcome = o.optString("outcome").takeIf { it.isNotEmpty() }
        if (!o.optBoolean("call_done") && (outcome != null || note != null)) {
            var rowId = o.optString("device_call_id").takeIf { it.isNotEmpty() }
            var row: Row? = rowId?.let { callRow(app, it) }
            if (row == null && o.has("number") && o.has("started_at")) {
                row = callRowAt(app, o.getString("number"), o.getLong("started_at"))
                rowId = row?.id
            }
            if (outcome != null && row != null && rowId != null) {
                // (the call itself first: the result lands on it, never a second call; call sync
                // may be off for this person — then the result alone makes the call)
                try {
                    rpc(app, "sync_phone_calls", JSONObject().put("p_device_id", deviceOf(app, o)).put("p_calls", JSONArray().put(row.toJson())))
                } catch (e: IttcApi.ApiError) {
                    if (e.status == 0 || e.status >= 500) throw e
                }
                rpc(
                    app, "save_phone_call",
                    JSONObject().put("p_lead_id", leadId).put("p_outcome", outcome).put("p_notes", note ?: "")
                        .put("p_duration_sec", row.durationSec).put("p_called_at", iso(row.dateMs))
                        .put("p_direction", if (row.direction == "outgoing") "outgoing" else "incoming")
                        .put("p_device_id", deviceOf(app, o)).put("p_device_call_id", rowId)
                        .apply { row.sim?.let { put("p_sim_label", it) } },
                )
            } else if (outcome != null && o.has("started_at") && System.currentTimeMillis() - o.optLong("at") < 15 * 60_000) {
                return Keep // (the call's row not in the call log yet: next time)
            } else {
                val text = listOfNotNull(outcome?.let { "Result: ${OUTCOME_LABELS[it] ?: it}" }, note).joinToString(" — ")
                rpc(app, "add_lead_note", JSONObject().put("p_lead_ids", JSONArray().put(leadId)).put("p_note", text))
            }
            o.put("call_done", true)
            update(app, o)
        }
        val stage = o.optString("stage_id").takeIf { it.isNotEmpty() }
        if (stage != null && !o.optBoolean("stage_done")) {
            val due = if (o.optBoolean("stage_due")) o.optLong("next_at").takeIf { it > 0 } else null
            moveStage(app, leadId, stage, if (o.optBoolean("stage_note")) note else null, due)
            o.put("stage_done", true)
            update(app, o)
        }
        val next = o.optLong("next_at").takeIf { it > 0 }
        if (next != null && !o.optBoolean("stage_due")) {
            rpc(
                app, "schedule_follow_up",
                JSONObject().put("p_lead_id", leadId).put("p_due_at", iso(next)).apply { note?.let { put("p_note", it) } },
            )
        }
        refreshAsync(app, force = true, delayMs = 1_500)
        return Done
    }

    /** Move the stage; a rule that says no (lead closed meanwhile…) becomes a note, not an endless retry */
    private fun moveStage(app: Context, leadId: String, stageId: String, note: String?, dueAt: Long?) {
        try {
            rpc(
                app, "move_lead_stage",
                JSONObject().put("p_lead_id", leadId).put("p_stage_id", stageId)
                    .apply {
                        note?.let { put("p_note", it) }
                        dueAt?.let { put("p_due_at", iso(it)) }
                    },
            )
        } catch (e: IttcApi.ApiError) {
            if (e.status != 400 || e.message?.contains("not active", true) == true) throw e
            rpc(app, "add_lead_note", JSONObject().put("p_lead_ids", JSONArray().put(leadId)).put("p_note", "Stage not changed from ITTC Phone: ${e.message}"))
            dueAt?.let {
                try {
                    rpc(app, "schedule_follow_up", JSONObject().put("p_lead_id", leadId).put("p_due_at", iso(it)))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun remove(app: Context, qid: String) = synchronized(outboxLock) {
        val arr = readArray(file(app, OUTBOX))
        val keep = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("qid") != qid) keep.put(it) }
        writeAtomic(file(app, OUTBOX), keep.toString())
    }

    private fun update(app: Context, item: JSONObject) = synchronized(outboxLock) {
        val arr = readArray(file(app, OUTBOX))
        val out = JSONArray()
        val qid = item.optString("qid")
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.put(if (it.optString("qid") == qid) item else it) }
        writeAtomic(file(app, OUTBOX), out.toString())
    }

    // ================================================================== small helpers
    private val bdMobile = Regex("^01[3-9]\\d{8}$")

    /** "+880 1711-234567" → "01711234567" (null: not a Bangladeshi mobile number), as the Tracker */
    fun normalizeBd(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var d = raw.filter { it.isDigit() }
        if (d.length == 13 && d.startsWith("880")) d = "0" + d.substring(3)
        else if (d.length == 10 && d.startsWith("1")) d = "0$d"
        return if (bdMobile.matches(d)) d else null
    }

    private val HEX = "0123456789abcdef".toCharArray()

    internal fun sha256(text: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val out = CharArray(d.size * 2)
        d.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    internal fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun readArray(f: File): JSONArray =
        try {
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (_: Throwable) {
            JSONArray()
        }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}
