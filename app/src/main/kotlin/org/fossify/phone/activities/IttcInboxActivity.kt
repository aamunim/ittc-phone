package org.fossify.phone.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcCActivityInboxBinding
import org.fossify.phone.direct.IttcAdmitApi
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcNotify
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcTheme
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🔔 The Tracker's notifications (ITTC Phone 3.4, direct mode): newest first, unread in bold. A tap
 * marks it read (mark_notifications_read) and opens the lead when it is about one; "Mark all read".
 */
class IttcInboxActivity : SimpleActivity() {
    private val binding by viewBinding(IttcCActivityInboxBinding::inflate)
    private val adapter = Adapter()
    private var items: List<Item> = emptyList()
    @Volatile private var loading = 0
    private val notifyHold = IttcNotify.ScreenHold()
    /** When the list last came from the server (elapsedRealtime; 0: never): a quick return reads nothing again */
    private var loadedAt = 0L
    /** "3 Nov" (the main thread only) */
    private val dayFormat = SimpleDateFormat("d MMM", Locale.ENGLISH)

    private class Item(
        val id: String,
        val kind: String,
        val title: String,
        val body: String?,
        val data: JSONObject?,
        val createdAt: Long,
        var read: Boolean,
    ) {
        /** The lead it is about (lead_id, or the only one of lead_ids; only a real id) */
        val leadId: String?
            get() = (
                data?.optString("lead_id")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: data?.optJSONArray("lead_ids")?.takeIf { it.length() == 1 }?.optString(0)?.takeIf { it.isNotBlank() }
                )?.trim()?.takeIf { IttcAdmitApi.isUuid(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val p = IttcTheme.of(this)
        adapter.p = p
        binding.apply {
            ittcCInboxProgress.indeterminateTintList = IttcTheme.tint(p.primary)
            setupEdgeToEdge(padBottomSystem = listOf(ittcCInboxList))
            setupMaterialScrollListener(ittcCInboxList, ittcCInboxAppbar)
            ittcCInboxHolder.setBackgroundColor(p.bg)
            ittcCInboxSummary.setTextColor(p.muted)
            ittcCInboxEmpty.setTextColor(p.muted)
            ittcCInboxMarkAll.setTextColor(p.primary)
            ittcCInboxMarkAll.setOnClickListener { markAll() }
            ittcCInboxList.layoutManager = LinearLayoutManager(this@IttcInboxActivity)
            ittcCInboxList.adapter = adapter
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcCInboxAppbar, NavigationIcon.Arrow)
        IttcNotify.clearPosted(this)
        notifyHold.resume(this)
        // (back within 20 s, e.g. from a lead it opened: the list on screen is fresh enough)
        if (loadedAt > 0 && SystemClock.elapsedRealtime() - loadedAt < 20_000) return
        load()
    }

    override fun onPause() {
        notifyHold.pause()
        super.onPause()
    }

    // ------------------------------------------------------------------ the list
    private fun load() {
        if (!IttcDirect.isActive(this)) {
            items = emptyList()
            showList(getString(R.string.ittc_c_signed_out))
            return
        }
        if (items.isEmpty()) binding.ittcCInboxProgress.visibility = View.VISIBLE
        val mine = ++loading
        val app = applicationContext
        ensureBackgroundThread {
            var error: String? = null
            val list = try {
                val arr = IttcApi.select(app, "notifications?select=id,kind,title,body,data,created_at,read_at&order=created_at.desc&limit=100")
                parse(arr)
            } catch (t: Throwable) {
                error = IttcCUi.errorText(app, t)
                null
            }
            runOnUiThread {
                if (mine != loading || isFinishing || isDestroyed) return@runOnUiThread
                binding.ittcCInboxProgress.visibility = View.GONE
                if (list != null) {
                    items = list
                    loadedAt = SystemClock.elapsedRealtime()
                    // (the badge follows what the list says, up to 100)
                    ensureBackgroundThread { IttcNotify.setUnread(app, list.count { !it.read }) }
                }
                showList(error ?: if (items.isEmpty()) getString(R.string.ittc_c_inbox_empty) else null)
            }
        }
    }

    private fun parse(arr: JSONArray): List<Item> {
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Item(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: continue,
                kind = o.optString("kind"),
                title = o.optString("title"),
                body = if (o.isNull("body")) null else o.optString("body").takeIf { it.isNotBlank() },
                data = o.optJSONObject("data"),
                createdAt = IttcBridge.parseTimeOf(o.optString("created_at")) ?: 0L,
                read = !o.isNull("read_at"),
            )
        }
        return out
    }

    private fun showList(empty: String?) {
        adapter.notifyDataSetChanged()
        val unread = showSummary()
        binding.ittcCInboxEmpty.text = empty.orEmpty()
        binding.ittcCInboxEmpty.visibility = if (items.isEmpty() && !empty.isNullOrEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty() && empty != null) toast(empty)
    }

    /** The "2 unread" line and Mark all read; returns the unread count */
    private fun showSummary(): Int {
        val unread = items.count { !it.read }
        binding.ittcCInboxSummary.text = when {
            items.isEmpty() -> ""
            unread == 0 -> getString(R.string.ittc_c_inbox_all_read)
            else -> resources.getQuantityString(R.plurals.ittc_c_inbox_unread, unread, unread)
        }
        binding.ittcCInboxMarkAll.visibility = if (unread > 0) View.VISIBLE else View.GONE
        return unread
    }

    // ------------------------------------------------------------------ read
    private fun tapped(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!item.read) {
            item.read = true
            // (only this row and the summary line: not the whole list)
            adapter.notifyItemChanged(index)
            showSummary()
            val app = applicationContext
            ensureBackgroundThread {
                try {
                    IttcApi.rpc(app, "mark_notifications_read", JSONObject().put("p_ids", JSONArray().put(item.id)))
                    IttcNotify.setUnread(app, (IttcNotify.unreadCached(app) - 1).coerceAtLeast(0))
                } catch (_: Throwable) {
                    // (it stays unread on the server: the next load shows it so again)
                }
            }
        }
        val lead = item.leadId
        if (lead != null) {
            IttcLeadActivity.open(this, lead)
        } else if (item.data?.optString("route").orEmpty().isNotBlank()) {
            toast(R.string.ittc_c_inbox_in_tracker)
        }
    }

    private fun markAll() {
        if (items.none { !it.read }) return
        binding.ittcCInboxMarkAll.isEnabled = false
        val app = applicationContext
        ensureBackgroundThread {
            val error = try {
                IttcApi.rpc(app, "mark_notifications_read", JSONObject())
                IttcNotify.setUnread(app, 0)
                null
            } catch (t: Throwable) {
                IttcCUi.errorText(app, t)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.ittcCInboxMarkAll.isEnabled = true
                if (error != null) return@runOnUiThread toast(error)
                items.forEach { it.read = true }
                IttcNotify.clearPosted(this)
                showList(null)
            }
        }
    }

    // ------------------------------------------------------------------ rows
    private class Holder(
        val root: LinearLayout, val icon: TextView, val title: TextView, val body: TextView, val time: TextView, val dot: View,
        /** The row's two looks, made once per row (a ripple inside the card's corners when pressed) */
        val readBg: Drawable, val unreadBg: Drawable,
    ) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        /** The palette (set in onCreate, before the list is shown) */
        lateinit var p: IttcTheme.Palette

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val p = this.p
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(IttcCUi.dp(c, 14), IttcCUi.dp(c, 12), IttcCUi.dp(c, 14), IttcCUi.dp(c, 12))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = IttcCUi.dp(c, 4)
                    bottomMargin = IttcCUi.dp(c, 4)
                }
            }
            val size = IttcCUi.dp(c, 40)
            val icon = IttcCUi.text(c, "", 18f, p.text).apply {
                gravity = Gravity.CENTER
                background = IttcTheme.rounded(c, p.surfaceAlt, 999f)
            }
            root.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = IttcCUi.dp(c, 12) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val top = IttcCUi.row(c)
            val title = IttcCUi.text(c, "", 15f, p.text).apply { maxLines = 2 }
            top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val time = IttcCUi.text(c, "", 12f, p.muted)
            top.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = IttcCUi.dp(c, 8) })
            val dot = View(c).apply { background = IttcTheme.rounded(c, p.primary, 999f) }
            top.addView(dot, LinearLayout.LayoutParams(IttcCUi.dp(c, 8), IttcCUi.dp(c, 8)).apply { marginStart = IttcCUi.dp(c, 6) })
            col.addView(top)
            val body = IttcCUi.text(c, "", 13f, p.text2).apply { maxLines = 4 }
            col.addView(body, IttcCUi.topMargin(c, 2))
            root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val holder = Holder(
                root, icon, title, body, time, dot,
                readBg = IttcTheme.pressable(c, IttcTheme.rounded(c, p.surface, 16f), p.text),
                unreadBg = IttcTheme.pressable(c, IttcTheme.rounded(c, p.primarySoft, 16f), p.primary),
            )
            root.setOnClickListener {
                val at = holder.adapterPosition
                if (at != RecyclerView.NO_POSITION) tapped(at)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.text = iconOf(item.kind)
            holder.title.text = item.title
            holder.title.setTypeface(null, if (item.read) Typeface.NORMAL else Typeface.BOLD)
            holder.title.setTextColor(if (item.read) p.text2 else p.text)
            holder.body.text = item.body.orEmpty()
            holder.body.visibility = if (item.body.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.time.text = ago(item.createdAt)
            holder.dot.visibility = if (item.read) View.GONE else View.VISIBLE
            // (unread: a soft blue card; read: the plain surface)
            holder.root.background = if (item.read) holder.readBg else holder.unreadBg
        }
    }

    /** The Tracker's icon for each kind (Settings → Notifications) */
    private fun iconOf(kind: String): String = when (kind) {
        "assigned" -> "👥"
        "follow_up" -> "⏰"
        "visit" -> "🏫"
        "auto_assigned" -> "🆕"
        "speed_to_lead" -> "⚡"
        "outcome" -> "🎓"
        "lead_merged" -> "🔗"
        "signup" -> "🙋"
        "sync_nudge" -> "📞"
        "missed_call" -> "📵"
        "discount" -> "💰"
        "payment" -> "📅"
        "feedback" -> "💬"
        "scorecard" -> "🏁"
        "leaderboard" -> "🏆"
        "alumni" -> "🎓"
        "supervisor" -> "🧑‍💼"
        "docready" -> "📄"
        else -> "🔔"
    }

    /** "now", "5m", "2h", "Yesterday", "3 Nov" (as the Tracker) */
    private fun ago(at: Long): String {
        if (at <= 0) return ""
        val min = (System.currentTimeMillis() - at) / 60_000
        return when {
            min < 1 -> getString(R.string.ittc_c_now)
            min < 60 -> getString(R.string.ittc_c_min_ago, min.toInt())
            min < 24 * 60 && android.text.format.DateUtils.isToday(at) -> getString(R.string.ittc_c_hours_ago, (min / 60).toInt())
            android.text.format.DateUtils.isToday(at + 24 * 3600_000L) -> getString(R.string.ittc_c_yesterday)
            else -> dayFormat.format(Date(at))
        }
    }

    companion object {
        fun open(context: Context) {
            try {
                context.startActivity(
                    Intent(context, IttcInboxActivity::class.java).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            } catch (_: Throwable) {
            }
        }
    }
}
