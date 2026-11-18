package org.fossify.phone.direct

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.fossify.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 🌐 ITTC Phone ↔ the ITTC server (Supabase), when ITTC Phone is signed in by itself (3.2).
 * Sign-in (e-mail + password → tokens), token refresh, and the server's functions ("RPC") and
 * tables (REST) — always as the signed-in person, so the server's row security decides what can be
 * seen or changed, exactly as in the Tracker. Call these off the main thread.
 */
internal object IttcApi {
    val configured: Boolean get() = BuildConfig.ITTC_SERVER_URL.startsWith("https://") && BuildConfig.ITTC_SERVER_KEY.isNotEmpty()
    private val base get() = BuildConfig.ITTC_SERVER_URL
    private val key get() = BuildConfig.ITTC_SERVER_KEY
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** A server answer that is not OK; [status] 0 = no internet */
    class ApiError(val status: Int, message: String) : IOException(message)

    // ------------------------------------------------------------------ auth
    data class Tokens(val access: String, val refresh: String, val expiresAt: Long, val userId: String, val email: String?)

    private fun tokens(body: String): Tokens {
        val o = JSONObject(body)
        val user = o.optJSONObject("user")
        return Tokens(
            access = o.getString("access_token"),
            refresh = o.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000,
            userId = user?.optString("id").orEmpty(),
            email = user?.optString("email"),
        )
    }

    fun signIn(email: String, password: String): Tokens {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val r = send(
            Request.Builder().url("$base/auth/v1/token?grant_type=password")
                .header("apikey", key)
                .post(body.toRequestBody(JSON)).build(),
        )
        return tokens(r)
    }

    fun refresh(refreshToken: String): Tokens {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val r = send(
            Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token")
                .header("apikey", key)
                .post(body.toRequestBody(JSON)).build(),
        )
        return tokens(r)
    }

    fun signOutServer(access: String) {
        try {
            send(
                Request.Builder().url("$base/auth/v1/logout").header("apikey", key)
                    .header("Authorization", "Bearer $access").post("{}".toRequestBody(JSON)).build(),
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ data
    /** A server function: its JSON answer as text ("null" for none) */
    fun rpc(context: Context, name: String, args: JSONObject = JSONObject()): String =
        authed(context) { access ->
            Request.Builder().url("$base/rest/v1/rpc/$name")
                .header("apikey", key)
                .header("Authorization", "Bearer $access")
                .post(args.toString().toRequestBody(JSON)).build()
        }

    /** Rows of a table (PostgREST query, e.g. "message_templates?select=id,name&is_active=eq.true") */
    fun select(context: Context, query: String): JSONArray =
        JSONArray(
            authed(context) { access ->
                Request.Builder().url("$base/rest/v1/$query")
                    .header("apikey", key)
                    .header("Authorization", "Bearer $access")
                    .get().build()
            },
        )

    /** Change one row ("leads?id=eq.<id>"); the server's row security decides */
    fun patch(context: Context, query: String, values: JSONObject) {
        authed(context) { access ->
            Request.Builder().url("$base/rest/v1/$query")
                .header("apikey", key)
                .header("Authorization", "Bearer $access")
                .header("Prefer", "return=minimal")
                .patch(values.toString().toRequestBody(JSON)).build()
        }
    }

    fun realtimeUrl(): String = base.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

    /** With a fresh token; a 401 refreshes once and tries again */
    private fun authed(context: Context, build: (String) -> Request): String {
        val access = IttcAuth.accessToken(context) ?: throw ApiError(401, "Not signed in")
        return try {
            send(build(access))
        } catch (e: ApiError) {
            if (e.status != 401) throw e
            val again = IttcAuth.accessToken(context, forceRefresh = true) ?: throw e
            send(build(again))
        }
    }

    private fun send(request: Request): String {
        val r: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError(0, "No internet")
        }
        r.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiError(it.code, message(text) ?: "Server error ${it.code}")
            return text
        }
    }

    /** The readable part of a server error ({"message": …} or {"error_description": …}) */
    private fun message(body: String): String? =
        try {
            val o = JSONObject(body)
            listOf("message", "error_description", "msg", "error").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
}
