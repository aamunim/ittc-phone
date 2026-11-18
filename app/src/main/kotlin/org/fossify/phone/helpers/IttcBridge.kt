package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.Call
import android.telecom.DisconnectCause
import org.fossify.phone.activities.IttcAfterCallActivity
import org.fossify.phone.activities.IttcLeadActivity
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.extensions.isOutgoing
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 🎓 ITTC Phone ↔ ITTC Admission Tracker (package com.abamunim.leads).
 *
 * The Tracker is a separate, private app. The two only talk through its provider:
 *  - "is this number a lead?"  query content://com.abamunim.leads.leadlookup/number/<number>
 *  - "a call ended"            call(…, "call_ended", <the call log's _ID for the call>, …)
 *  - "which of these are leads?" (Recents) query …/numbers with the numbers as selectionArgs
 * Each side checks the other's signing certificate against a fixed list (the Tracker comes from
 * Google Play, re-signed with Google's key). The full contract is in docs/ITTC.md.
 * Nothing here ever leaves the phone.
 */
object IttcBridge {
    private const val TRACKER = "com.abamunim.leads"
    private const val AUTHORITY = "com.abamunim.leads.leadlookup"
    private const val PROVIDER = "content://$AUTHORITY"
    private const val LOOKUP = "$PROVIDER/number/"
    private const val NUMBERS = "$PROVIDER/numbers"
    private const val SCHEME = "leadmanagement"

    /** SHA-256 of the Tracker's signing certificates: Google Play's app signing key, and the upload key (APK builds) */
    private val TRACKER_CERTS = setOf(
        "d07ea3e9d65a0df3027e771b1b14e79833fc1c87cadae7810202e395e28e4e54",
        "2955a704b8bab01f1fb8ed467a07ce3906cc46f783c33ba098b30e74f4710d83",
    )

    // (the call screen's lookups never wait behind a call-log search)
    private val io = Executors.newSingleThreadExecutor()
    private val ended = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Lead(
        val leadId: String,
        val name: String,
        val rating: String?,
        val stage: String?,
        val course: String?,
        val lastNote: String?,
        /** the Tracker's Call button started this call just now (the Tracker shows its own popup after it) */
        val appCall: Boolean = false,
    ) {
        /** "🎓 Lead · Course · Stage" and the last note under it */
        fun summary(badge: String): String {
            val top = listOfNotNull(badge, course, stage, rating?.let { "★ $it" }).joinToString(" · ")
            return if (lastNote.isNullOrBlank()) top else "$top\n“${lastNote.take(120)}”"
        }
    }

    /**
     * The real Tracker is installed and owns the provider (its certificate is on the list:
     * nothing is ever sent to another app)
     */
    @Suppress("DEPRECATION")
    private fun trackerReady(context: Context): Boolean =
        try {
            val pm = context.packageManager
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val s = pm.getPackageInfo(TRACKER, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (s == null) emptyArray() else if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
            } else {
                pm.getPackageInfo(TRACKER, PackageManager.GET_SIGNATURES).signatures
            } ?: emptyArray()
            pm.resolveContentProvider(AUTHORITY, 0)?.packageName == TRACKER &&
                certs.any { sha256(it.toByteArray()) in TRACKER_CERTS }
        } catch (_: Throwable) {
            false
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Is the Tracker installed (the real one)? */
    fun trackerInstalled(context: Context): Boolean = trackerReady(context.applicationContext)

    @Volatile private var signedInAt = 0L
    @Volatile private var signedInKnown = false
    @Volatile private var signedInAnswer = false

    /**
     * The Tracker is installed and someone is signed in to it (it answers "who is signed in").
     * Kept for a minute; on the main thread only the kept answer is given (and a fresh one asked).
     */
    fun trackerSignedIn(context: Context): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!signedInKnown) {
            // (the last answer, kept: right after a restart the main thread must not guess "no")
            signedInAnswer = app.getSharedPreferences("ittc", Context.MODE_PRIVATE).getBoolean("tracker_signed_in", false)
            signedInKnown = true
        }
        if (now - signedInAt < 60_000) return signedInAnswer
        if (Looper.myLooper() == Looper.getMainLooper()) {
            signedInAt = now - 50_000 // (ask once; the answer is in within ~10 s)
            io.execute { askSignedIn(app) }
            return signedInAnswer
        }
        return askSignedIn(app)
    }

    private fun askSignedIn(app: Context): Boolean {
        val answer = try {
            trackerReady(app) && withTracker(app) { client ->
                client.query(Uri.parse("$PROVIDER/phone_meta"), null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use false
                    val i = c.getColumnIndex("json")
                    !(i < 0 || c.isNull(i)) && parseMeta(c.getString(i))?.meId != null
                }
            } == true
        } catch (_: Throwable) {
            // (the Tracker busy / restarting: the last answer stands)
            signedInAnswer
        }
        if (answer != signedInAnswer) {
            app.getSharedPreferences("ittc", Context.MODE_PRIVATE).edit().putBoolean("tracker_signed_in", answer).apply()
        }
        signedInAnswer = answer
        signedInKnown = true
        signedInAt = System.currentTimeMillis()
        return answer
    }

    /**
     * Tell the Tracker (2.11.0+) whether ITTC Phone sends lead calls itself now: it then sends none
     * of its own (no call saved twice). Off the main thread; false: no Tracker / an older one.
     */
    fun setTrackerDirect(context: Context, on: Boolean): Boolean {
        val app = context.applicationContext
        if (!trackerReady(app)) return false
        return try {
            val answer = withTracker(app) { it.call("direct_mode", if (on) "1" else "0", null) }
            // (Tracker 2.11.0+: its phone id; ITTC Phone sends calls with it, so the server keeps one copy)
            answer?.getString("device_id")?.takeIf { it.isNotBlank() }?.let { IttcDirect.setTrackerDeviceId(app, it) }
            answer?.getString("result") == "ok"
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The colour of a lead's stage line: Admitted blue, Not Now / cancelled grey, a working
     * stage the app's colour
     */
    fun stageColor(stage: String?, working: Int, closed: Int): Int = when {
        stage == null -> working
        stage.contains("Admitted", true) && !stage.contains("cancel", true) -> 0xFF1E88E5.toInt()
        stage.contains("Not Now", true) || stage.contains("cancel", true) -> closed
        else -> working
    }

    private fun clean(number: String?): String? =
        number?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 6 }

    /** The lead with this number (off the main thread; the answer on the main thread, null if none) */
    fun lookup(context: Context, number: String?, done: (Lead?) -> Unit) {
        val app = context.applicationContext
        val n = clean(number)
        if (n == null) {
            done(null)
            return
        }
        io.execute {
            val lead = try {
                if (IttcDirect.isActive(app)) IttcDirect.lookupLead(app, n)
                else if (!trackerReady(app)) null else query(app, n)
            } catch (_: Throwable) {
                null
            }
            main.post { done(lead) }
        }
    }

    /**
     * The Tracker's provider as an "unstable" client: if the Tracker dies mid-request we get an
     * exception (caught) instead of Android killing the phone app with it.
     */
    private fun <T> withTracker(context: Context, block: (android.content.ContentProviderClient) -> T?): T? {
        val client = context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY) ?: return null
        return try {
            block(client)
        } finally {
            client.close()
        }
    }

    /** startedAt: when the call began (asked after it, for "was it started from the Tracker") */
    private fun query(context: Context, number: String, startedAt: Long? = null): Lead? = withTracker(context) { client ->
        val uri = Uri.parse(LOOKUP + Uri.encode(number) + (startedAt?.let { "?started=$it" } ?: ""))
        // (ask the Tracker to give up after 3 s, so a slow one holds up the next calls less)
        val cancel = CancellationSignal()
        val timeout = Runnable { cancel.cancel() }
        main.postDelayed(timeout, 3_000)
        try {
            readLead(client.query(uri, null, null, null, null, cancel))
        } finally {
            main.removeCallbacks(timeout)
        }
    }

    private fun readLead(cursor: android.database.Cursor?): Lead? {
        cursor?.use { c ->
            if (!c.moveToFirst()) return null
            return readRow(c)
        }
        return null
    }

    /** The lead in the cursor's current row (null: no lead id) */
    private fun readRow(c: android.database.Cursor): Lead? {
        fun col(name: String): String? {
            val i = c.getColumnIndex(name)
            return if (i < 0 || c.isNull(i)) null else c.getString(i)?.takeIf { it.isNotBlank() && it != "null" }
        }
        val id = col("lead_id") ?: return null
        return Lead(
            leadId = id,
            name = col("name") ?: "",
            rating = col("rating"),
            stage = col("stage"),
            course = col("course"),
            lastNote = col("last_note"),
            appCall = col("app_call") == "1",
        )
    }

    // -------------------------------------------------------------------
    // 🕘 Recents: which numbers are leads (asked in one go, kept a little while)
    // -------------------------------------------------------------------
    private const val RECENTS_TTL_MS = 2 * 60 * 1000L
    private class Known(val lead: Lead?, val at: Long)
    private val known = ConcurrentHashMap<String, Known>()
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val recents = Executors.newSingleThreadExecutor()

    /**
     * The lead for a Recents row, if already known (null: not a lead, or not asked yet). Numbers not
     * known yet are asked together a moment later; [onNew] runs on the main thread when they are in.
     */
    fun recentLead(context: Context, number: String?, onNew: () -> Unit): Lead? {
        val n = clean(number) ?: return null
        val k = known[n]
        if (k != null && System.currentTimeMillis() - k.at < RECENTS_TTL_MS) return k.lead
        if (asking.add(n)) {
            val app = context.applicationContext
            // (one batch for the whole screen: rows bound in the same moment join it)
            main.postDelayed({ askRecents(app, onNew) }, 150)
        }
        return k?.lead
    }

    private fun askRecents(app: Context, onNew: () -> Unit) {
        val batch = asking.toList().take(300)
        if (batch.isEmpty()) return
        asking.removeAll(batch.toSet())
        recents.execute {
            val found = HashMap<String, Lead>()
            try {
                if (IttcDirect.isActive(app)) {
                    batch.forEach { n -> IttcDirect.lookupLead(app, n)?.let { found[n] = it } }
                } else if (trackerReady(app)) {
                    withTracker(app) { client ->
                        client.query(Uri.parse(NUMBERS), null, null, batch.toTypedArray(), null)?.use { c ->
                            while (c.moveToNext()) {
                                val i = c.getColumnIndex("number")
                                val n = if (i < 0) null else c.getString(i)
                                val lead = readRow(c) ?: continue
                                if (n != null) found[n] = lead
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // (no Tracker / an older one: the rows just stay plain)
            }
            val now = System.currentTimeMillis()
            batch.forEach { known[it] = Known(found[it], now) }
            main.post { onNew() }
        }
    }

    /**
     * Which of these numbers are leads (call off the main thread: it may ask the Tracker).
     * Keys are the numbers as given. Fills the Recents cache too.
     */
    fun leadsFor(context: Context, numbers: Collection<String>): Map<String, Lead> {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val byClean = numbers.mapNotNull { n -> clean(n)?.let { it to n } }
        val missing = byClean.map { it.first }.distinct()
            .filter { known[it]?.let { k -> now - k.at >= RECENTS_TTL_MS } ?: true }
        if (missing.isNotEmpty() && IttcDirect.isActive(app)) {
            missing.forEach { known[it] = Known(IttcDirect.lookupLead(app, it), now) }
        } else if (missing.isNotEmpty() && trackerReady(app)) {
            missing.chunked(300).forEach { batch ->
                val found = HashMap<String, Lead>()
                try {
                    withTracker(app) { client ->
                        client.query(Uri.parse(NUMBERS), null, null, batch.toTypedArray(), null)?.use { c ->
                            while (c.moveToNext()) {
                                val i = c.getColumnIndex("number")
                                val n = if (i < 0) null else c.getString(i)
                                val lead = readRow(c) ?: continue
                                if (n != null) found[n] = lead
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                batch.forEach { known[it] = Known(found[it], now) }
            }
        }
        val out = HashMap<String, Lead>()
        byClean.forEach { (c, original) -> known[c]?.lead?.let { out[original] = it } }
        return out
    }

    // -------------------------------------------------------------------
    // 🎓 "Leads" tab: the signed-in user's own open leads (Tracker 2.10.5+)
    // -------------------------------------------------------------------
    data class PhoneLead(
        val leadId: String,
        val name: String,
        val phone: String?,
        val altPhone: String?,
        val whatsapp: String?,
        val stage: String?,
        val course: String?,
        val batch: String?,
        val rating: String?,
        val nextCall: Long?,
        val lastContacted: Long?,
        val attempts: Int,
        val lastNote: String?,
        // (Tracker 2.10.6+; null with an older one)
        val stageId: String? = null,
        val courseId: String? = null,
        val assignedTo: String? = null,
    ) {
        val callNumber: String? get() = phone ?: altPhone ?: whatsapp
    }

    /** The last list read (for the dial pad's name search and an instant tab) */
    @Volatile
    var myLeadsCache: List<PhoneLead> = emptyList()
        private set

    /** null: no Tracker / an older one (the tab then says to update it); call off the main thread */
    fun myLeads(context: Context): List<PhoneLead>? {
        val app = context.applicationContext
        if (IttcDirect.isActive(app)) return IttcDirect.leads(app).also { myLeadsCache = it; IttcTabBadges.leadsChanged() }
        if (!trackerReady(app)) return null
        return try {
            withTracker(app) { client ->
                client.query(Uri.parse("$PROVIDER/my_leads"), null, null, null, null)?.use { c ->
                    fun col(name: String): String? {
                        val i = c.getColumnIndex(name)
                        return if (i < 0 || c.isNull(i)) null else c.getString(i)?.takeIf { it.isNotBlank() && it != "null" }
                    }
                    val out = ArrayList<PhoneLead>(c.count)
                    while (c.moveToNext()) {
                        val id = col("lead_id") ?: continue
                        out += PhoneLead(
                            leadId = id,
                            name = col("name") ?: "",
                            phone = col("phone"),
                            altPhone = col("alt_phone"),
                            whatsapp = col("whatsapp_number"),
                            stage = col("stage"),
                            course = col("course"),
                            batch = col("batch"),
                            rating = col("rating"),
                            nextCall = parseTime(col("next_call")),
                            lastContacted = parseTime(col("last_contacted")),
                            attempts = col("call_attempts")?.toIntOrNull() ?: 0,
                            lastNote = col("last_note"),
                            stageId = col("stage_id"),
                            courseId = col("course_id"),
                            assignedTo = col("assigned_to"),
                        )
                    }
                    out
                }
            }
        } catch (_: Throwable) {
            null
        }?.also { myLeadsCache = it; IttcTabBadges.leadsChanged() }
    }

    fun parseTimeOf(s: String?): Long? = parseTime(s)

    private fun parseTime(s: String?): Long? =
        try {
            s?.let { java.time.OffsetDateTime.parse(it.replace(' ', 'T')).toInstant().toEpochMilli() }
        } catch (_: Throwable) {
            null
        }

    /**
     * Ask the Tracker for a fresh list (it fetches one when it is running).
     * done(true): asked; false: the Tracker is not running; null: no Tracker (on the main thread)
     */
    fun askRefresh(context: Context, done: ((Boolean?) -> Unit)? = null) {
        val app = context.applicationContext
        recents.execute {
            val answer = try {
                if (IttcDirect.isActive(app)) {
                    IttcDirect.refresh(app, force = true)
                    "asked"
                } else if (trackerReady(app)) withTracker(app) { it.call("refresh_leads", null, null) }?.getString("result") else null
            } catch (_: Throwable) {
                null
            }
            done?.let { d -> main.post { d(answer?.let { it == "asked" }) } }
        }
    }

    // -------------------------------------------------------------------
    // 🧭 Who is signed in, the open pipeline stages and the people (Tracker 2.10.6+)
    // -------------------------------------------------------------------
    data class Stage(
        val id: String,
        val name: String,
        val color: Int?,
        val noteRequired: Boolean,
        val askDate: Boolean,
        val dateLabel: String?,
        val followUpDays: Int?,
        /** null: every course */
        val courseIds: Set<String>?,
    ) {
        fun forCourse(courseId: String?): Boolean = courseIds == null || (courseId != null && courseId in courseIds)
    }

    data class Person(val id: String, val name: String, val role: String)

    data class PhoneMeta(
        val meId: String?,
        val isSuper: Boolean,
        val stages: List<Stage>,
        val people: List<Person>,
        /** when the Tracker fetched the list (server time) */
        val at: Long?,
        /** the admin's ITTC Phone settings (Tracker 2.11.0+ / direct): note_presets, outcome_next, daily_target, min_version */
        val settings: org.json.JSONObject? = null,
    ) {
        /** The admin's quick notes for the after-call screen */
        val notePresets: List<String>
            get() = settings?.optJSONArray("note_presets")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            }.orEmpty()

        /** The next call a result suggests: hours from now, or "tomorrow10"; null: none */
        fun nextFor(outcome: String?): Any? {
            val rules = settings?.optJSONObject("outcome_next") ?: return null
            if (outcome == null || !rules.has(outcome) || rules.isNull(outcome)) return null
            return when (val v = rules.opt(outcome)) {
                is Number -> v.toDouble().takeIf { it > 0 }
                is String -> v.takeIf { it.isNotBlank() }
                else -> null
            }
        }

        val dailyTarget: Int get() = settings?.optInt("daily_target", 0) ?: 0
    }

    @Volatile
    var metaCache: PhoneMeta? = null
        private set

    /** null: an older Tracker (no stages) or none yet; call off the main thread */
    fun phoneMeta(context: Context): PhoneMeta? {
        val app = context.applicationContext
        if (IttcDirect.isActive(app)) return IttcDirect.meta(app)?.also { metaCache = it; IttcTabBadges.leadsChanged() }
        if (!trackerReady(app)) return null
        return try {
            withTracker(app) { client ->
                client.query(Uri.parse("$PROVIDER/phone_meta"), null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val i = c.getColumnIndex("json")
                    if (i < 0 || c.isNull(i)) null else parseMeta(c.getString(i))
                }
            }
        } catch (_: Throwable) {
            null
        }?.also { metaCache = it; IttcTabBadges.leadsChanged() }
    }

    fun parseMeta(json: String): PhoneMeta? {
        val o = try {
            org.json.JSONObject(json)
        } catch (_: Throwable) {
            return null
        }
        fun org.json.JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
        val me = o.optJSONObject("me")
        val stages = ArrayList<Stage>()
        o.optJSONArray("stages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.str("id") ?: continue
                stages += Stage(
                    id = id,
                    name = s.str("name") ?: continue,
                    color = s.str("color")?.let {
                        try {
                            android.graphics.Color.parseColor(it)
                        } catch (_: Throwable) {
                            null
                        }
                    },
                    noteRequired = s.optBoolean("note_required"),
                    askDate = s.optBoolean("ask_date"),
                    dateLabel = s.str("date_label"),
                    followUpDays = if (s.isNull("follow_up_days")) null else s.optInt("follow_up_days"),
                    courseIds = s.optJSONArray("course_ids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() },
                )
            }
        }
        val people = ArrayList<Person>()
        o.optJSONArray("people")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                people += Person(p.str("id") ?: continue, p.str("name") ?: "", p.str("role") ?: "")
            }
        }
        return PhoneMeta(
            meId = me?.str("id"),
            isSuper = me?.optBoolean("super") == true,
            stages = stages,
            people = people,
            at = parseTime(o.str("at")),
            settings = o.optJSONObject("settings"),
        )
    }

    /**
     * ⚡ Called (on the main thread) when the Tracker saves a new list; null when there is no
     * Tracker. Unregister with [unwatchLeads].
     */
    fun watchLeads(context: Context, callback: () -> Unit): android.database.ContentObserver? {
        val app = context.applicationContext
        val observer = object : android.database.ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                callback()
            }
        }
        // (direct mode: ITTC Phone's own list changed, or the mode switched)
        IttcDirect.addObserver(observer)
        if (trackerReady(app)) {
            try {
                app.contentResolver.registerContentObserver(Uri.parse("$PROVIDER/my_leads"), false, observer)
            } catch (_: Throwable) {
            }
        }
        return observer
    }

    fun unwatchLeads(context: Context, observer: android.database.ContentObserver?) {
        observer ?: return
        IttcDirect.removeObserver(observer)
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) {
        }
    }

    /** Opens the Tracker (its own launcher screen) */
    fun openTracker(context: Context): Boolean =
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TRACKER) ?: return false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * 📝 The after-call result (Tracker 2.10.5+): queued in the Tracker, which sends it.
     * false: an older Tracker or none (call off the main thread).
     */
    fun saveOutcome(
        context: Context,
        leadId: String,
        number: String?,
        outcome: String?,
        note: String?,
        nextAt: Long?,
        startedAt: Long? = null,
        stage: Stage? = null,
    ): Boolean {
        val app = context.applicationContext
        // (nothing is sent before the person has seen the disclosure)
        if (!IttcDisclosure.agreed(app)) return false
        if (IttcDirect.isActive(app)) {
            val item = org.json.JSONObject()
                .put("kind", "outcome")
                .put("lead_id", leadId)
                .put("number", number ?: org.json.JSONObject.NULL)
                .put("outcome", outcome ?: org.json.JSONObject.NULL)
                .put("note", note?.trim().orEmpty())
            nextAt?.let { item.put("next_at", it) }
            startedAt?.let { item.put("started_at", it) }
            rowIdFor(number)?.let { item.put("device_call_id", it) }
            stage?.let {
                item.put("stage_id", it.id)
                if (it.noteRequired) item.put("stage_note", true)
                if (it.askDate) item.put("stage_due", true)
            }
            IttcDirect.enqueue(app, item)
            IttcDirect.kick(app)
            return true
        }
        if (!trackerReady(app)) return false
        val extras = Bundle().apply {
            putString("lead_id", leadId)
            number?.let { putString("number", it) }
            outcome?.let { putString("outcome", it) }
            note?.takeIf { it.isNotBlank() }?.let { putString("note", it) }
            nextAt?.let { putString("next_at", it.toString()) }
            // (the call log row not found yet: the Tracker finds it itself by number and time)
            startedAt?.let { putString("started_at", it.toString()) }
            // (a new pipeline stage, Tracker 2.10.6+: it sends the note / the date with it when the
            // stage wants them)
            stage?.let {
                putString("stage_id", it.id)
                if (it.noteRequired) putString("stage_note", "1")
                if (it.askDate) putString("stage_due", "1")
            }
        }
        return try {
            val result = withTracker(app) { it.call("save_outcome", rowIdFor(number), extras) }
            result?.getString("result") == "queued"
        } catch (_: Throwable) {
            false
        }
    }

    /** Signed out of ITTC Phone: nothing of the last person's lists stays */
    fun clearCaches() {
        myLeadsCache = emptyList()
        metaCache = null
        known.clear()
        IttcTabBadges.leadsChanged()
    }

    /** Forget what Recents knew (a call just ended: its lead may have changed) */
    fun forgetRecents() = known.clear()

    // -------------------------------------------------------------------
    // 📲 Open the lead in the Tracker (and its "add a note" popup for a call)
    // -------------------------------------------------------------------
    fun openLead(context: Context, leadId: String, callLogId: String? = null): Boolean {
        if (IttcDirect.isActive(context)) {
            IttcLeadActivity.open(context, leadId)
            return true
        }
        if (!trackerReady(context)) return false
        val query = callLogId?.let { "?callNote=${Uri.encode(it)}" }.orEmpty()
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME://leads/${Uri.encode(leadId)}$query"))
                    .setPackage(TRACKER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    // -------------------------------------------------------------------
    // A call ended: find its row in the call log (the system writes it a
    // moment later), then tell the Tracker which row it is. A lead call not
    // started from the Tracker gets ITTC Phone's "after the call" screen.
    // -------------------------------------------------------------------
    /** Is one of ITTC Phone's screens open right now (then a screen may be shown after the call) */
    @Volatile
    var screensOpen = 0

    /** The call log _ID found for the last lead call (for "Add a note" on the after-call screen) */
    private val rowIds = ConcurrentHashMap<String, String>()

    fun rowIdFor(number: String?): String? = clean(number)?.let { rowIds[it] }

    /** The number the after-call screen really opened for (Android may refuse to open it) */
    @Volatile
    private var shownFor: String? = null

    fun afterCallShown(number: String?) {
        shownFor = clean(number)
    }

    fun onCallEnded(context: Context, call: Call, lastCall: Boolean) {
        // (never in the way of the phone: anything going wrong here is dropped)
        try {
            handOver(context, call, lastCall)
        } catch (_: Throwable) {
        }
    }

    private fun handOver(context: Context, call: Call, lastCall: Boolean) {
        val app = context.applicationContext
        // (made the phone app in Android's settings and never opened: no hand-over before the
        // disclosure; the Tracker's own call-log sync still has the call)
        if (!IttcDisclosure.agreed(app)) return
        val details = try {
            call.details
        } catch (_: Throwable) {
            null
        } ?: return
        val number = clean(details.handle?.schemeSpecificPart) ?: return
        val startedAt = details.creationTimeMillis.takeIf { it > 0 } ?: (System.currentTimeMillis() - 60_000)
        val talkedSec = details.connectTimeMillis.takeIf { it > 0 }
            ?.let { ((System.currentTimeMillis() - it) / 1000).toInt().coerceAtLeast(0) } ?: 0
        val outgoing = try {
            call.isOutgoing()
        } catch (_: Throwable) {
            false
        }
        val reason = endReason(details.disconnectCause)
        // (an after-call screen only: when no other call is left, while our call screen is up (it
        // cannot open from the background), and not on a locked phone (it would stay unseen))
        val locked = try {
            app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        } catch (_: Throwable) {
            true
        }
        val canShow = lastCall && screensOpen > 0 && !locked
        rowIds.remove(number)
        shownFor = null
        forgetRecents()
        ended.execute {
            try {
                if (IttcDirect.isActive(app)) {
                    directEnded(app, number, startedAt, outgoing, talkedSec, reason, canShow)
                    return@execute
                }
                if (!trackerReady(app)) return@execute
                // only lead calls: a personal call is never handed over
                val lead = query(app, number, startedAt) ?: return@execute
                // (a call the Tracker's Call button placed: the Tracker shows its own popup)
                val show = canShow && !(outgoing && lead.appCall)
                if (show) {
                    main.post {
                        IttcAfterCallActivity.open(app, lead, number, outgoing, talkedSec, reason, startedAt)
                    }
                }
                val id = findRow(app, number, startedAt) ?: return@execute
                rowIds[number] = id
                // (give the after-call screen a moment to open, up to ~2 s)
                if (show) {
                    var waited = 0
                    while (shownFor != number && waited < 2_000) {
                        Thread.sleep(200)
                        waited += 200
                    }
                }
                val extras = Bundle().apply {
                    putString("number", number)
                    if (reason != null) putString("end_reason", reason)
                    // (the after-call screen has "Add a note": the Tracker sends no notification too.
                    // Only if it really opened by now; else the Tracker's notification is the way in)
                    if (show && shownFor == number) putString("shows_note", "1")
                }
                withTracker(app) { it.call("call_ended", id, extras) }
            } catch (_: Throwable) {
                // never break the phone because of the Tracker; its own sync is the backup
            }
        }
    }

    /** Direct mode: the after-call screen for a lead, and the call itself queued for the server */
    private fun directEnded(app: Context, number: String, startedAt: Long, outgoing: Boolean, talkedSec: Int, reason: String?, canShow: Boolean) {
        // only lead calls: a personal call is never sent
        val lead = IttcDirect.lookupLead(app, number)
        if (lead == null) {
            // (an unknown number: the after-call screen offers "Add as a lead")
            if (canShow && IttcDirect.unknownAfterCall(app)) {
                main.post { IttcAfterCallActivity.openUnknown(app, number, outgoing, talkedSec, reason, startedAt) }
            }
            return
        }
        if (canShow) {
            main.post { IttcAfterCallActivity.open(app, lead, number, outgoing, talkedSec, reason, startedAt) }
        }
        val id = findRow(app, number, startedAt)
        if (id == null) {
            // (the call log not written yet: found later by number and time)
            IttcDirect.queueCallAt(app, number, startedAt, refreshFirst = false)
            return
        }
        rowIds[number] = id
        IttcDirect.queueCall(app, id)
        IttcDirect.kick(app)
    }

    /** The call log _ID of this call: the row for the number nearest to when it began (waits up to ~10 s) */
    @SuppressLint("MissingPermission")
    private fun findRow(context: Context, number: String, startedAt: Long): String? {
        val wanted = number.filter { it.isDigit() }.takeLast(10)
        repeat(8) { attempt ->
            if (attempt > 0) Thread.sleep(1_500)
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                    arrayOf((startedAt - 10_000).toString(), (startedAt + 10_000).toString()),
                    null,
                )?.use { c ->
                    // (a redial of the same number moments later is another row: take the nearest)
                    var best: String? = null
                    var bestGap = Long.MAX_VALUE
                    while (c.moveToNext()) {
                        val n = c.getString(1)?.filter { it.isDigit() }?.takeLast(10) ?: continue
                        if (n != wanted) continue
                        val gap = kotlin.math.abs(c.getLong(2) - startedAt)
                        if (gap < bestGap) {
                            bestGap = gap
                            best = c.getLong(0).toString()
                        }
                    }
                    if (best != null) return best
                }
            } catch (_: SecurityException) {
                return null // no call log access: the Tracker's own sync will find it
            } catch (_: Throwable) {
                // try again
            }
        }
        return null
    }

    private fun endReason(cause: DisconnectCause?): String? = when (cause?.code) {
        null -> null
        DisconnectCause.LOCAL -> "hung_up"
        DisconnectCause.REMOTE -> "they_hung_up"
        DisconnectCause.BUSY -> "busy"
        DisconnectCause.MISSED -> "missed"
        DisconnectCause.REJECTED -> "declined"
        DisconnectCause.CANCELED -> "cancelled"
        DisconnectCause.ERROR -> "error"
        DisconnectCause.RESTRICTED -> "restricted"
        DisconnectCause.ANSWERED_ELSEWHERE -> "answered_elsewhere"
        DisconnectCause.CALL_PULLED -> "pulled"
        else -> null
    }
}
