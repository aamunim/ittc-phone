package org.fossify.phone.helpers

import android.app.Activity
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.direct.IttcDirect
import org.json.JSONObject

/**
 * 🧭 ITTC 3.9: one way to change a lead's stage, shared by the lead page and the Leads board —
 * the stage's rules (which ones close a lead / admit it), the outbox item and how it is queued
 * (offline-safe: kept on the phone and sent when there is internet, then the lists are read again).
 */
object IttcStageMove {
    /** "Admitted" (not "Admission cancelled"): done on the admit form, not by a bare stage change */
    fun isAdmitted(name: String?): Boolean {
        val s = name.orEmpty().lowercase()
        return s.contains("admitted") && !s.contains("cancel")
    }

    /** A stage that closes the lead for now ("Not now", "Lost", "Cancelled", "Dropped") — the lead page's steps leave these out */
    fun isClosed(name: String?): Boolean {
        val s = name.orEmpty().lowercase()
        return s.contains("not now") || s.contains("lost") || s.contains("cancel") || s.contains("drop")
    }

    /** Asked first (a sheet) rather than moved at once: it needs a note or a date, or it closes the lead */
    fun needsConfirm(st: IttcBridge.Stage): Boolean = st.noteRequired || st.askDate || isClosed(st.name)

    /** The outbox item for a stage change: {"kind":"stage","stage_id",["note"],["due_at"]} */
    fun item(stageId: String, note: String?, dueAt: Long?): JSONObject {
        val item = JSONObject().put("kind", "stage").put("stage_id", stageId)
        note?.let { item.put("note", it) }
        dueAt?.let { item.put("due_at", it) }
        return item
    }

    /**
     * Queue a change for [leadId] (any kind: the lead page queues its other changes this way too) —
     * after the one-time disclosure, written to the outbox off the main thread; [done] runs on the
     * main thread with whether it was kept. The lists are fetched again a little later.
     */
    fun queue(activity: Activity, leadId: String, item: JSONObject, done: (Boolean) -> Unit) {
        IttcDisclosure.showOnce(activity) {
            item.put("lead_id", leadId)
            val app = activity.applicationContext
            ensureBackgroundThread {
                val ok = try {
                    IttcDirect.enqueue(app, item)
                    true
                } catch (_: Throwable) {
                    false
                }
                activity.runOnUiThread { done(ok) }
            }
            IttcDirect.refreshAsync(app, force = true, delayMs = 3_000)
        }
    }
}
