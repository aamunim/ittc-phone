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
        val stored = prefs(context).getString(KEY_SESSION, null) ?: return null
        val json = IttcSecure.decrypt(stored)
        if (json == null) {
            // (the key is gone — restored to another phone, cleared lock screen: sign in again; never
            // retried on every check)
            prefs(context).edit().remove(KEY_SESSION).apply()
            return null
        }
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
        // (in memory first: a Keystore hiccup must not lose a token the server already rotated)
        cached = t
        val json = JSONObject()
            .put("access", t.access).put("refresh", t.refresh).put("expires_at", t.expiresAt)
            .put("user_id", t.userId).put("email", t.email ?: "")
            .toString()
        try {
            prefs(context).edit().putString(KEY_SESSION, IttcSecure.encrypt(json)).commit()
        } catch (_: Throwable) {
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            cached = null
            prefs(context).edit().remove(KEY_SESSION).commit()
        }
    }

    /**
     * A token good for at least a minute (refreshed if needed; null: signed out, or the server
     * refused the refresh — then the person signs in again). Signing out happens after the lock is
     * let go (it stops live updates, which may be waiting for a token).
     */
    fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        var ended = false
        val token = synchronized(lock) {
            val s = session(context) ?: return null
            if (!forceRefresh && s.expiresAt - System.currentTimeMillis() > 60_000) return s.access
            try {
                val fresh = IttcApi.refresh(s.refresh)
                // (signed out / someone else signed in while it was asked: never brought back)
                if (session(context)?.refresh != s.refresh) return null
                save(context, fresh.copy(userId = fresh.userId.ifEmpty { s.userId }, email = fresh.email ?: s.email))
                fresh.access
            } catch (e: IttcApi.ApiError) {
                // (only a refused refresh token ends the sign-in; busy / rate-limited / no internet:
                // keep the old one, the next request tries again)
                if (e.status == 400 || e.status == 401 || e.status == 403) {
                    ended = true
                    null
                } else {
                    s.access
                }
            } catch (_: Throwable) {
                s.access
            }
        }
        if (ended) IttcDirect.signedOutByServer(context, "Your sign-in ended. Please sign in again.")
        return token
    }
}
