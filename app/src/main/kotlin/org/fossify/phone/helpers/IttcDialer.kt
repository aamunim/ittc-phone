package org.fossify.phone.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.fossify.phone.activities.DialerActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.startCallIntent
import org.json.JSONArray
import org.json.JSONObject

/**
 * ▶ The power dialer: "Call all" in the Leads tab queues the leads shown, calls the first, and the
 * after-call screen's "Save & call next" goes on with the rest. Kept in memory and in the prefs (the
 * app may be closed between two calls). A queue untouched for 20 minutes is forgotten.
 */
object IttcDialer {
    private const val PREFS = "ittc_dialer"
    private const val STALE_MS = 20 * 60 * 1000L

    data class Item(val leadId: String, val number: String, val name: String)

    private val queue = ArrayList<Item>()
    /** the lead being called now (null: not calling) */
    @Volatile private var current: Item? = null
    @Volatile private var lastAt = 0L
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Calling through a list right now? (call [load] once first after the app started) */
    val active: Boolean
        get() = current != null && System.currentTimeMillis() - lastAt < STALE_MS

    /** Leads still to call after the one now */
    val remaining: Int
        get() = synchronized(queue) { if (active) queue.size else 0 }

    val currentLead: Item? get() = if (active) current else null

    /** Read the queue kept in the prefs (once; cheap) */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val p = prefs(context)
            val at = p.getLong("at", 0)
            if (System.currentTimeMillis() - at >= STALE_MS) return
            val arr = JSONArray(p.getString("queue", "[]"))
            val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::item) }
            synchronized(queue) {
                queue.clear()
                queue.addAll(items)
            }
            current = p.getString("current", null)?.let { item(JSONObject(it)) }
            lastAt = at
        } catch (_: Throwable) {
        }
    }

    private fun item(o: JSONObject): Item? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val n = o.optString("number").takeIf { it.isNotEmpty() } ?: return null
        return Item(id, n, o.optString("name"))
    }

    private fun json(i: Item) = JSONObject().put("id", i.leadId).put("number", i.number).put("name", i.name)

    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            synchronized(queue) { queue.forEach { arr.put(json(it)) } }
            prefs(context).edit()
                .putString("queue", arr.toString())
                .putString("current", current?.let { json(it).toString() })
                .putLong("at", lastAt)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /** Queue these leads (those with a number) and call the first; false: nobody to call */
    fun start(context: Context, list: List<IttcBridge.PhoneLead>): Boolean {
        loaded = true
        val items = list.mapNotNull { l -> l.callNumber?.takeIf { it.isNotBlank() }?.let { Item(l.leadId, it, l.name) } }
            .distinctBy { it.leadId }
        synchronized(queue) {
            queue.clear()
            queue.addAll(items)
        }
        current = null
        return next(context)
    }

    /** Call the next lead in the queue; false (and stopped): the queue is done */
    fun next(context: Context): Boolean {
        load(context)
        val item = synchronized(queue) { if (queue.isEmpty()) null else queue.removeAt(0) }
        if (item == null) {
            stop(context)
            return false
        }
        current = item
        lastAt = System.currentTimeMillis()
        save(context)
        call(context, item.number)
        return true
    }

    fun stop(context: Context? = null) {
        synchronized(queue) { queue.clear() }
        current = null
        lastAt = 0
        loaded = true
        context?.let {
            try {
                prefs(it).edit().clear().apply()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * From a screen that stays (the Leads tab): the same call path as its 📞 button. Otherwise (the
     * after-call screen closes at once: pass the app context) ITTC Phone's own dial screen, which
     * picks the SIM, checks blocked numbers and shows the call screen by itself.
     */
    private fun call(context: Context, number: String) {
        try {
            if (context is SimpleActivity && !context.isFinishing) {
                context.startCallIntent(number)
                return
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .setClass(context, DialerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}
