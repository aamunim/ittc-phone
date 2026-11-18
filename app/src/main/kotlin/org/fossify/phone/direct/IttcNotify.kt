package org.fossify.phone.direct

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.fossify.phone.helpers.IttcBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 🔔 The Tracker's notifications in ITTC Phone (3.4, direct mode): the unread count (kept, for the
 * bell's badge), and — while the app is open — a live watch: the server's new rows in
 * "notifications" for me (Supabase Realtime, filtered user_id=eq.<me>; the table's row security
 * applies) become an Android notification (channel "ittc_inbox"; a tap opens the inbox).
 * The socket follows [IttcRealtime]'s ways: the token goes with the join, a heartbeat every 25 s,
 * a fresh token every 40 minutes, and opened again with growing waits when it fails.
 */
object IttcNotify {
    private const val PREFS = "ittc_notify"
    private const val CHANNEL = "ittc_inbox"
    private const val REOPEN_MS = 40 * 60_000L
    private const val MAX_COUNT = 100

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    /** Screens that want the live watch now (MainActivity, a lead, search, the inbox) */
    private var users = 0
    @Volatile private var appContext: Context? = null
    @Volatile private var openedAt = 0L
    private var failures = 0
    private var ref = 0
    /** (heartbeats and timed reopens only: never waits for the network) */
    private val timer = Executors.newSingleThreadScheduledExecutor()
    /** (network work: tokens, the count, opening the socket — off the timer and the socket's reader) */
    private val worker = Executors.newSingleThreadExecutor()
    private var beat: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Int) -> Unit>()
    private val posted = LinkedHashSet<String>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ the count
    /** The last known unread count (no network; 0 when not signed in) */
    fun unreadCached(context: Context): Int =
        if (!IttcDirect.isSignedIn(context)) 0 else prefs(context).getInt("unread_" + (IttcAuth.session(context)?.userId ?: ""), 0)

    /** Ask the server for the unread count (off the main thread); [done] runs on the main thread */
    fun refreshAsync(context: Context, done: ((Int) -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            val n = try {
                if (IttcDirect.isActive(app)) countNow(app) else unreadCached(app)
            } catch (_: Throwable) {
                unreadCached(app)
            }
            if (done != null) main.post { done(n) }
        }
    }

    /** (blocking) the unread count from the server, kept */
    internal fun countNow(app: Context): Int {
        val arr = IttcApi.select(app, "notifications?select=id&read_at=is.null&limit=$MAX_COUNT")
        setUnread(app, arr.length())
        return arr.length()
    }

    /** The count changed (the inbox marked some read, a new one came): kept and told */
    internal fun setUnread(context: Context, n: Int) {
        val uid = IttcAuth.session(context)?.userId ?: return
        val before = prefs(context).getInt("unread_$uid", -1)
        prefs(context).edit().putInt("unread_$uid", n.coerceIn(0, MAX_COUNT)).apply()
        if (before != n) main.post { listeners.forEach { l -> try { l(n) } catch (_: Throwable) {} } }
    }

    /** A screen showing the badge hears changes (on the main thread) */
    fun addListener(l: (Int) -> Unit) {
        listeners += l
    }

    fun removeListener(l: (Int) -> Unit) {
        listeners -= l
    }

    // ------------------------------------------------------------------ live
    /**
     * A screen of the app is open: watch for new notifications. Counted, as [IttcRealtime]'s users:
     * each start is matched by one [stop] (in onResume / onPause); watched while any screen wants it.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(this) {
            users++
            wanted = true
        }
        worker.execute { open(app) }
    }

    /** One screen less wants it (closed when none does); signed out: closed, whoever wanted it */
    fun stop() {
        val app = appContext ?: return
        // (on the worker, after any start before it: reading the session must not block the main thread)
        worker.execute {
            val signedIn = try {
                IttcAuth.isSignedIn(app)
            } catch (_: Throwable) {
                false
            }
            synchronized(this) {
                users = if (signedIn) (users - 1).coerceAtLeast(0) else 0
                if (users > 0) return@execute
                wanted = false
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
    }

    /**
     * For a screen other than MainActivity: [resume] in onResume (starts when signed in, read off
     * the main thread), [pause] in onPause — never more than one start held per screen.
     */
    class ScreenHold {
        private var resumed = false
        private var held = false

        fun resume(context: Context) {
            resumed = true
            val app = context.applicationContext
            IttcNotify.worker.execute {
                val ok = try {
                    IttcDirect.isSignedIn(app)
                } catch (_: Throwable) {
                    false
                }
                if (ok) IttcNotify.main.post {
                    if (resumed && !held) {
                        held = true
                        IttcNotify.start(app)
                    }
                }
            }
        }

        fun pause() {
            resumed = false
            if (held) {
                held = false
                IttcNotify.stop()
            }
        }
    }

    /** (off the main thread: getting a token may need the network) */
    private fun open(app: Context) {
        synchronized(this) { if (socket != null || !wanted) return }
        if (!IttcDirect.isActive(app)) return
        val access = IttcAuth.accessToken(app) ?: return
        val uid = IttcAuth.session(app)?.userId ?: return
        synchronized(this) {
            if (socket != null || !wanted) return
            openedAt = System.currentTimeMillis()
            socket = IttcApi.http.newWebSocket(Request.Builder().url(IttcApi.realtimeUrl()).build(), listener(app, uid, access))
        }
    }

    private fun listener(app: Context, uid: String, access: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@IttcNotify) { if (socket !== webSocket) return }
            val config = JSONObject().put(
                "postgres_changes",
                JSONArray().put(
                    JSONObject().put("event", "INSERT").put("schema", "public")
                        .put("table", "notifications").put("filter", "user_id=eq.$uid"),
                ),
            )
            webSocket.send(
                JSONObject().put("topic", "realtime:ittc-inbox:$uid").put("event", "phx_join")
                    .put("payload", JSONObject().put("config", config).put("access_token", access))
                    .put("ref", nextRef()).toString(),
            )
            synchronized(this@IttcNotify) {
                if (socket !== webSocket) return
                beat?.cancel(false)
                beat = timer.scheduleAtFixedRate({
                    if (System.currentTimeMillis() - openedAt > REOPEN_MS) {
                        reopen(app, webSocket, 1_000)
                    } else {
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", nextRef()).toString())
                    }
                }, 25, 25, TimeUnit.SECONDS)
            }
            // (what came while it was closed: the count only, no old pop-ups)
            worker.execute {
                try {
                    countNow(app)
                } catch (_: Throwable) {
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try {
                JSONObject(text)
            } catch (_: Throwable) {
                return
            }
            when (o.optString("event")) {
                "postgres_changes" -> {
                    val record = o.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record") ?: return
                    // (only mine: the filter and the row security say so already)
                    if (record.optString("user_id") != uid) return
                    arrived(app, record)
                }
                "phx_close", "phx_error" -> reopen(app, webSocket, 5_000)
                "phx_reply" -> when (o.optJSONObject("payload")?.optString("status")) {
                    "error" -> reopen(app, webSocket, 30_000, failed = true)
                    "ok" -> synchronized(this@IttcNotify) { failures = 0 }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reopen(app, webSocket, 15_000, failed = true)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reopen(app, webSocket, 5_000)
    }

    private fun reopen(app: Context, webSocket: WebSocket, afterMs: Long, failed: Boolean = false) {
        synchronized(this) {
            if (socket !== webSocket) return
            if (failed) failures++
            val wait = if (failures == 0) afterMs else minOf(afterMs shl minOf(failures - 1, 5), 10 * 60_000L)
            socket = null
            beat?.cancel(false)
            try {
                webSocket.close(1000, null)
            } catch (_: Throwable) {
            }
            if (wanted) {
                retry?.cancel(false)
                retry = timer.schedule({ worker.execute { open(app) } }, wait, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    private fun nextRef(): String = (++ref).toString()

    // ------------------------------------------------------------------ a new one
    private fun arrived(app: Context, r: JSONObject) {
        val id = r.optString("id")
        synchronized(posted) {
            if (id.isEmpty() || !posted.add(id)) return
            while (posted.size > 200) posted.remove(posted.first())
        }
        if (r.has("read_at") && !r.isNull("read_at")) return
        setUnread(app, (unreadCached(app) + 1).coerceAtMost(MAX_COUNT))
        // (made for quiet hours: it waits in the list, as the Tracker's push waits)
        val after = IttcBridge.parseTimeOf(r.optString("deliver_after").takeIf { !r.isNull("deliver_after") })
        if (after != null && after > System.currentTimeMillis() + 60_000) return
        post(app, id, r.optString("kind"), r.optString("title"), r.optString("body").takeIf { !r.isNull("body") })
    }

    private fun post(app: Context, id: String, kind: String, title: String, body: String?) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "ITTC notifications", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                app, id.hashCode(),
                Intent(app, org.fossify.phone.activities.IttcInboxActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(org.fossify.commons.R.drawable.ic_bell_vector)
                .setContentTitle(title.ifBlank { "ITTC" })
                .setContentText(body.orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(if (kind == "follow_up" || kind == "visit") NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
                .build()
            nm.notify("ittc_inbox", id.hashCode(), n)
        } catch (_: Throwable) {
            // (notifications not allowed: the inbox still has it)
        }
    }

    /** The inbox is open: its pop-ups are not needed any more */
    internal fun clearPosted(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.tag == "ittc_inbox" }.forEach { nm.cancel(it.tag, it.id) }
        } catch (_: Throwable) {
        }
    }
}
