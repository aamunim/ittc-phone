package org.fossify.phone.direct

import android.content.Context
import org.json.JSONObject

/**
 * 🔑 ITTC Phone's own sign-in (3.2): the tokens, kept encrypted (IttcSecure), refreshed before they
 * run out. Separate from the Tracker's sign-in (Supabase rotates refresh tokens: two apps cannot
 * share one).
 */
internal object IttcAuth {
    private const val PREFS = "ittc_direct"
    private const val KEY_SESSION = "session"

    @Volatile private var cached: IttcApi.Tokens? = null
    private val lock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun session(context: Context): IttcApi.Tokens? {
        cached?.let { return it }
        val json = IttcSecure.decrypt(prefs(context).getString(KEY_SESSION, null)) ?: return null
        return try {
            val o = JSONObject(json)
            IttcApi.Tokens(
                access = o.getString("access"),
                refresh = o.getString("refresh"),
                expiresAt = o.getLong("expires_at"),
                userId = o.getString("user_id"),
                email = o.optString("email").takeIf { it.isNotEmpty() },
            ).also { cached = it }
        } catch (_: Throwable) {
            null
        }
    }

    fun isSignedIn(context: Context): Boolean = session(context) != null

    fun save(context: Context, t: IttcApi.Tokens) {
        val json = JSONObject()
            .put("access", t.access).put("refresh", t.refresh).put("expires_at", t.expiresAt)
            .put("user_id", t.userId).put("email", t.email ?: "")
            .toString()
        prefs(context).edit().putString(KEY_SESSION, IttcSecure.encrypt(json)).apply()
        cached = t
    }

    fun clear(context: Context) {
        cached = null
        prefs(context).edit().remove(KEY_SESSION).apply()
    }

    /**
     * A token good for at least a minute (refreshed if needed; null: signed out, or the server
     * refused the refresh — then the person signs in again)
     */
    fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        synchronized(lock) {
            val s = session(context) ?: return null
            if (!forceRefresh && s.expiresAt - System.currentTimeMillis() > 60_000) return s.access
            return try {
                val fresh = IttcApi.refresh(s.refresh)
                save(context, fresh.copy(userId = fresh.userId.ifEmpty { s.userId }, email = fresh.email ?: s.email))
                fresh.access
            } catch (e: IttcApi.ApiError) {
                if (e.status in 400..499) {
                    // (the refresh token is no longer valid: signed out elsewhere / password changed)
                    IttcDirect.signedOutByServer(context, "Your sign-in ended. Please sign in again.")
                    null
                } else {
                    // (no internet: keep the old one; the next request tries again)
                    s.access
                }
            }
        }
    }
}
