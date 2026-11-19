package org.fossify.phone.helpers

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyEditText
import org.fossify.phone.R
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.json.JSONArray
import org.json.JSONObject

/**
 * 👥 ITTC: hand over — everyone (direct mode) may give any lead they can see to any active user
 * (the server's hand_over_leads checks again). Shared by the lead page (and the Leads sheet,
 * through it) and the after-call screen:
 *  - [people]: whom to give it to — the server's handover_people() (everyone may read it); an
 *    older server without it (404 / PGRST202) or no internet: the last list kept here, else the
 *    people my_phone_data sent (super admins only).
 *  - [pick]: the list, then a note for them (optional) — the after-call screen's dialogs.
 *  - [queueNow] / [queueAfterLead]: the outbox item ({"kind":"handover"}, as the lead page queues
 *    it); the outbox holds it back until this lead's changes queued before it (the call's result)
 *    have gone: once the lead is someone else's, they might no longer be allowed.
 */
object IttcHandover {
    private const val PREFS = "ittc_handover"
    private const val KEY_PEOPLE = "people"
    private const val FRESH_MS = 5 * 60_000L

    @Volatile
    private var cached: Pair<Long, List<IttcBridge.Person>>? = null

    /** Whom leads can be given to (by name). Call off the main thread. */
    fun people(context: Context): List<IttcBridge.Person> {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        cached?.let { (at, list) -> if (now - at < FRESH_MS && list.isNotEmpty()) return list }
        val fromServer: List<IttcBridge.Person>? = try {
            parse(JSONArray(IttcApi.rpc(app, "handover_people")))
        } catch (_: IttcApi.ApiError) {
            // (404 / PGRST202: a server without handover_people yet; else offline / refused)
            null
        } catch (_: Throwable) {
            null
        }
        if (!fromServer.isNullOrEmpty()) {
            cached = now to fromServer
            try {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PEOPLE, toJson(fromServer).toString()).apply()
            } catch (_: Throwable) {
            }
            return fromServer
        }
        cached?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        val kept = try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PEOPLE, null)?.let { parse(JSONArray(it)) }
        } catch (_: Throwable) {
            null
        }
        if (!kept.isNullOrEmpty()) return kept
        val meta = try {
            IttcDirect.meta(app) ?: IttcBridge.metaCache
        } catch (_: Throwable) {
            IttcBridge.metaCache
        }
        return meta?.people.orEmpty().sortedBy { it.name.lowercase() }
    }

    /** [people] off the main thread; [done] on it (not when the activity is gone) */
    fun peopleAsync(activity: Activity, done: (List<IttcBridge.Person>) -> Unit) {
        val app = activity.applicationContext
        ensureBackgroundThread {
            val list = try {
                people(app)
            } catch (_: Throwable) {
                emptyList()
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) done(list)
            }
        }
    }

    /** "Name  ·  team lead" */
    fun label(p: IttcBridge.Person): String = if (p.role.isBlank()) p.name else "${p.name}  ·  ${p.role.replace('_', ' ')}"

    /** The outbox item (the lead's id is added when queued); [toName] for the message if it is refused */
    fun item(to: String, note: String?, toName: String? = null): JSONObject {
        val item = JSONObject().put("kind", "handover").put("to", to)
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { item.put("note", it) }
        toName?.takeIf { it.isNotBlank() }?.let { item.put("to_name", it) }
        return item
    }

    /**
     * The person (the lead's own person [ownerId] left out), then an optional note for them;
     * [done] with the person and the note ("" for none).
     */
    fun pick(activity: Activity, ownerId: String?, done: (IttcBridge.Person, String) -> Unit) {
        peopleAsync(activity) { all ->
            val people = all.filter { it.id != ownerId }
            if (people.isEmpty()) {
                activity.toast(R.string.ittc_lp_no_people)
                return@peopleAsync
            }
            pickList(activity, activity.getString(R.string.ittc_lp_hand_over), people.map { label(it) }) { i ->
                val person = people[i]
                noteDialog(activity, activity.getString(R.string.ittc_lp_hand_over_to, person.name)) { note -> done(person, note) }
            }
        }
    }

    /**
     * Queue the hand over of [leadId] to [to] now (kept on the phone). The outbox sends it only
     * after this lead's changes queued before it (e.g. the call's result) have gone.
     * Returns whether it was kept. Call off the main thread.
     */
    fun queueNow(context: Context, leadId: String, to: String, toName: String?, note: String?): Boolean {
        val app = context.applicationContext
        return try {
            IttcDirect.enqueue(app, item(to, note, toName).put("lead_id", leadId))
            IttcDirect.refreshAsync(app, force = true, delayMs = 3_000)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** [queueNow] off the main thread; [queued] (any thread) with whether it was kept */
    fun queueAfterLead(context: Context, leadId: String, to: String, toName: String?, note: String?, queued: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        ensureBackgroundThread {
            val ok = queueNow(app, leadId, to, toName, note)
            queued?.invoke(ok)
        }
    }

    // ------------------------------------------------------------------ dialogs
    private fun dp(c: Context, v: Int) = (c.resources.displayMetrics.density * v).toInt()

    private fun ripple(c: Context): Int {
        val tv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun pickList(activity: Activity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val fg = activity.getProperTextColor()
        var dialog: AlertDialog? = null
        items.forEachIndexed { i, label ->
            holder.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(fg)
                setPadding(dp(activity, 24), dp(activity, 14), dp(activity, 24), dp(activity, 14))
                setBackgroundResource(ripple(activity))
                setOnClickListener {
                    dialog?.dismiss()
                    onPick(i)
                }
            })
        }
        activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(ScrollView(activity).apply { addView(holder) }, this, titleText = title) { dialog = it }
            }
    }

    private fun noteDialog(activity: Activity, title: String, done: (String) -> Unit) {
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }
        val input = MyEditText(activity).apply {
            setHint(R.string.ittc_lp_note_optional)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
        }
        holder.addView(input)
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ittc_ho_give, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(ScrollView(activity).apply { addView(holder) }, this, titleText = title) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val note = input.text?.toString()?.trim().orEmpty()
                        dialog.dismiss()
                        done(note)
                    }
                }
            }
    }

    // ------------------------------------------------------------------ the list's JSON
    private fun parse(arr: JSONArray): List<IttcBridge.Person> {
        val out = ArrayList<IttcBridge.Person>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val name = (o.optString("full_name").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("name").takeIf { it.isNotBlank() && it != "null" }).orEmpty()
            val role = (o.optString("role_key").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("role").takeIf { it.isNotBlank() && it != "null" }).orEmpty()
            out += IttcBridge.Person(id, name, role)
        }
        return out
    }

    private fun toJson(list: List<IttcBridge.Person>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("full_name", it.name).put("role_key", it.role)) }
        return arr
    }
}
