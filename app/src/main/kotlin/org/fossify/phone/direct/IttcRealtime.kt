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
 * the lists come again a moment later (2 s for my own leads, 20 s for "any lead" of a super admin).
 */
internal object IttcRealtime {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var users = 0
    private var ref = 0
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var beat: ScheduledFuture<*>? = null

    /** A screen that shows leads wants live updates (call off the main thread) */
    @Synchronized
    fun start(context: Context) {
        users++
        open(context.applicationContext)
    }

    @Synchronized
    private fun open(app: Context) {
        if (socket != null || users == 0 || !IttcDirect.isActive(app)) return
        val access = IttcAuth.accessToken(app) ?: return
        val uid = IttcAuth.session(app)?.userId ?: return
        socket = IttcApi.http.newWebSocket(
            Request.Builder().url(IttcApi.realtimeUrl()).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
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
                            .put("ref", (++ref).toString()).toString(),
                    )
                    // (the server closes a quiet socket: a heartbeat every 25 s)
                    synchronized(this@IttcRealtime) {
                        beat?.cancel(false)
                        beat = timer.scheduleAtFixedRate({
                            webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", (++ref).toString()).toString())
                        }, 25, 25, TimeUnit.SECONDS)
                    }
                    // (anything changed while it was closed)
                    IttcDirect.refreshAsync(app, force = true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = try {
                        JSONObject(text)
                    } catch (_: Throwable) {
                        return
                    }
                    if (o.optString("event") != "postgres_changes") return
                    val target = o.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record")?.optString("target")
                    IttcDirect.refreshAsync(app, force = true, delayMs = if (target == "all") 20_000 else 1_500)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@IttcRealtime) {
                        if (socket === webSocket) {
                            socket = null
                            beat?.cancel(false)
                            // (still wanted: try again in a while, e.g. when the internet is back)
                            if (users > 0) timer.schedule({ synchronized(this@IttcRealtime) { open(app) } }, 15, TimeUnit.SECONDS)
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(this@IttcRealtime) { if (socket === webSocket) socket = null }
                }
            },
        )
    }

    /** One screen less needs it; closed when none does */
    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    @Synchronized
    fun stop() {
        beat?.cancel(false)
        socket?.close(1000, null)
        socket = null
        users = 0
    }
}
