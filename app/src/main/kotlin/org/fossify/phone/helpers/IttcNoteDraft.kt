package org.fossify.phone.helpers

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 📝 ITTC 3.5: a note written on the call screen ("Write a note now"), kept for the number until
 * the after-call screen takes it (and fills its note with it). In memory and in the prefs (the app
 * may be restarted between the two screens); a draft older than 30 minutes is forgotten.
 * The prefs are written off the main thread (the call screen never waits on the disk).
 */
object IttcNoteDraft {
    private const val PREFS = "ittc_note_draft"
    private const val KEY = "drafts"
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    private class Draft(val text: String, val at: Long)

    private val drafts = HashMap<String, Draft>()
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor()

    /** "+880 1711-000000" / "01711000000" → the same key (the last 10 digits) */
    private fun keyOf(number: String?): String? =
        number?.filter { it.isDigit() }?.takeIf { it.length >= 6 }?.takeLast(10)

    /** Keep [text] as the draft note for [number] (blank: the draft goes) */
    fun set(context: Context, number: String?, text: String?) {
        val key = keyOf(number) ?: return
        val app = context.applicationContext
        val clean = text?.trim().orEmpty()
        synchronized(drafts) {
            // (an empty draft stays in memory as "none", so an older one in the prefs never comes back)
            drafts[key] = Draft(clean, System.currentTimeMillis())
        }
        io.execute {
            ensureLoaded(app)
            save(app)
        }
    }

    /** The draft note for [number] (null: none / too old); taking it removes it */
    fun take(context: Context, number: String?): String? {
        val key = keyOf(number) ?: return null
        val app = context.applicationContext
        ensureLoaded(app)
        val d = synchronized(drafts) { drafts.put(key, Draft("", System.currentTimeMillis())) } ?: return null
        io.execute { save(app) }
        return d.text.takeIf { it.isNotEmpty() && System.currentTimeMillis() - d.at < MAX_AGE_MS }
    }

    /** The draft without taking it (null: none / too old) */
    fun peek(context: Context, number: String?): String? {
        val key = keyOf(number) ?: return null
        ensureLoaded(context.applicationContext)
        val d = synchronized(drafts) { drafts[key] } ?: return null
        return d.text.takeIf { it.isNotEmpty() && System.currentTimeMillis() - d.at < MAX_AGE_MS }
    }

    private fun ensureLoaded(app: Context) {
        if (loaded) return
        try {
            val json = JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
            val now = System.currentTimeMillis()
            synchronized(drafts) {
                json.keys().forEach { k ->
                    val o = json.optJSONObject(k) ?: return@forEach
                    val at = o.optLong("at", 0)
                    val text = o.optString("text")
                    // (a draft set in memory before the prefs were read is newer: it wins)
                    if (text.isNotBlank() && now - at < MAX_AGE_MS && !drafts.containsKey(k)) drafts[k] = Draft(text, at)
                }
            }
        } catch (_: Throwable) {
        }
        loaded = true
    }

    private fun save(app: Context) {
        try {
            val now = System.currentTimeMillis()
            val out = JSONObject()
            synchronized(drafts) {
                drafts.entries.removeAll { now - it.value.at >= MAX_AGE_MS }
                drafts.forEach { (k, d) -> if (d.text.isNotEmpty()) out.put(k, JSONObject().put("text", d.text).put("at", d.at)) }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, out.toString()).apply()
        } catch (_: Throwable) {
        }
    }
}
