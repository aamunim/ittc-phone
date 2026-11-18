package org.fossify.phone.direct

import android.content.Context
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ⚡ Live updates while ITTC Phone is open and signed in by itself: the server's "your leads
 * changed" pings (Supabase Realtime, table phone_lead_pings — the same the Tracker listens to) make
 * the list come again a moment later (1.5 s for my own leads, 20 s for "any lead" of a super admin;
 * several pings close together make one request).
 * The sign-in token goes with the join, so the socket is opened again with a fresh one every 40
 * minutes (the server drops a channel whose token ran out), and whenever the server closes it.
 */
internal object IttcRealtime {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var users = 0
    @Volatile private var openedAt = 0L
    private var failures = 0
    private var ref = 0
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var beat: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private const val REOPEN_MS = 40 * 60_000L

    /** A screen that shows leads wants live updates (call off the main thread) */
    fun start(context: Context) {
        synchronized(this) { users++ }
        open(context.applicationContext)
    }

    /** (the token is fetched before the lock: getting one may sign out, which calls [stop]) */
    private fun open(app: Context) {
        synchronized(this) { if (socket != null || users == 0) return }
        if (!IttcDirect.isActive(app)) return
        val access = IttcAuth.accessToken(app) ?: return
        val uid = IttcAuth.session(app)?.userId ?: return
        synchronized(this) {
            if (socket != null || users == 0) return
            openedAt = System.currentTimeMillis()
            socket = IttcApi.http.newWebSocket(Request.Builder().url(IttcApi.realtimeUrl()).build(), listener(app, uid, access))
        }
    }

    private fun listener(app: Context, uid: String, access: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // (stopped meanwhile: this socket is not wanted)
            synchronized(this@IttcRealtime) { if (socket !== webSocket) return }
            val config = JSONObject().put(
                "postgres_changes",
                JSONArray().put(
                    JSONObject().put("event", "INSERT").put("schema", "public")
                        .put("table", "phone_lead_pings").put("filter", "target=in.($uid,all)"),
                ),
            )
            webSocket.send(
                JSONObject().put("topic", "realtime:ittc-phone:$uid").put("event", "phx_join")
                    .put("payload", JSONObject().put("config", config).put("access_token", access))
                    .put("ref", nextRef()).toString(),
            )
            synchronized(this@IttcRealtime) {
                if (socket !== webSocket) return
                beat?.cancel(false)
                // (the server closes a quiet socket: a heartbeat every 25 s; a fresh token now and then)
                beat = timer.scheduleAtFixedRate({
                    if (System.currentTimeMillis() - openedAt > REOPEN_MS) {
                        reopen(app, webSocket, 1_000)
                    } else {
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", nextRef()).toString())
                    }
                }, 25, 25, TimeUnit.SECONDS)
            }
            // (anything changed while it was closed; the list's own 20 s limit applies)
            IttcDirect.refreshAsync(app, directory = false)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try {
                JSONObject(text)
            } catch (_: Throwable) {
                return
            }
            when (o.optString("event")) {
                "postgres_changes" -> {
                    val target = o.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record")?.optString("target")
                    IttcDirect.refreshAsync(app, force = true, delayMs = if (target == "all") 20_000 else 1_500, directory = false)
                }
                // (the channel ended — token ran out, server restart: join again with a fresh token)
                "phx_close", "phx_error" -> reopen(app, webSocket, 5_000)
                "phx_reply" -> when (o.optJSONObject("payload")?.optString("status")) {
                    "error" -> reopen(app, webSocket, 30_000, failed = true)
                    // (joined: the waits start small again)
                    "ok" -> synchronized(this@IttcRealtime) { failures = 0 }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reopen(app, webSocket, 15_000, failed = true)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reopen(app, webSocket, 5_000)
    }

    /** Close this socket (if it is still the one) and, while still wanted, open a new one later */
    private fun reopen(app: Context, webSocket: WebSocket, afterMs: Long, failed: Boolean = false) {
        synchronized(this) {
            if (socket !== webSocket) return
            // (failing again and again: wait longer each time, up to 10 minutes)
            if (failed) failures++
            val wait = if (failures == 0) afterMs else minOf(afterMs shl minOf(failures - 1, 5), 10 * 60_000L)
            socket = null
            beat?.cancel(false)
            try {
                webSocket.close(1000, null)
            } catch (_: Throwable) {
            }
            if (users > 0) {
                retry?.cancel(false)
                retry = timer.schedule({ open(app) }, wait, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    private fun nextRef(): String = (++ref).toString()

    /** One screen less needs it; closed when none does */
    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) closeNow()
    }

    /** Signed out: closed, whoever wanted it */
    @Synchronized
    fun stop() {
        users = 0
        closeNow()
    }

    @Synchronized
    private fun closeNow() {
        beat?.cancel(false)
        retry?.cancel(false)
        val s = socket
        socket = null
        try {
            s?.close(1000, null)
        } catch (_: Throwable) {
        }
    }
}
