package org.fossify.phone.helpers

import android.content.Context
import org.fossify.phone.models.RecentCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 🎓 ITTC: the Recents list as last shown, kept in the app's private storage, so the next
 * opening shows it at once while the call log is read again behind it (that read waits for
 * every contact to load: seconds on a phone with many contacts).
 */
object IttcRecentsCache {
    private const val FILE = "ittc_recents_cache.json"
    private const val KEEP = 150

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun save(context: Context, calls: List<RecentCall>) {
        try {
            val arr = JSONArray()
            calls.take(KEEP).forEach { arr.put(toJson(it)) }
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun load(context: Context): List<RecentCall> =
        try {
            val f = file(context)
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            }
        } catch (_: Throwable) {
            emptyList()
        }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun toJson(c: RecentCall): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("phoneNumber", c.phoneNumber)
        put("name", c.name)
        put("photoUri", c.photoUri)
        put("startTS", c.startTS)
        put("duration", c.duration)
        put("type", c.type)
        put("simID", c.simID)
        put("simColor", c.simColor)
        put("specificNumber", c.specificNumber)
        put("specificType", c.specificType)
        put("isUnknownNumber", c.isUnknownNumber)
        c.groupedCalls?.let { g -> put("grouped", JSONArray().apply { g.forEach { put(toJson(it)) } }) }
    }

    private fun fromJson(o: JSONObject): RecentCall? =
        try {
            RecentCall(
                id = o.getInt("id"),
                phoneNumber = o.optString("phoneNumber"),
                name = o.optString("name"),
                photoUri = o.optString("photoUri"),
                startTS = o.getLong("startTS"),
                duration = o.optInt("duration"),
                type = o.optInt("type"),
                simID = o.optInt("simID", -1),
                simColor = o.optInt("simColor"),
                specificNumber = o.optString("specificNumber"),
                specificType = o.optString("specificType"),
                isUnknownNumber = o.optBoolean("isUnknownNumber"),
                groupedCalls = o.optJSONArray("grouped")?.let { g ->
                    (0 until g.length()).mapNotNull { fromJson(g.getJSONObject(it)) }.toMutableList()
                },
            )
        } catch (_: Throwable) {
            null
        }
}
