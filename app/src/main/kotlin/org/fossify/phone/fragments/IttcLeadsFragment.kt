package org.fossify.phone.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.R
import org.fossify.phone.activities.IttcAddLeadActivity
import org.fossify.phone.activities.IttcLeadActivity
import org.fossify.phone.activities.IttcTeamActivity
import org.fossify.phone.databinding.IttcFragmentLeadsBinding
import org.fossify.phone.databinding.IttcItemLeadBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcRealtime
import org.fossify.phone.direct.IttcReminders
import org.fossify.phone.extensions.startCallIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCrash
import org.fossify.phone.helpers.IttcDialer
import org.fossify.phone.helpers.IttcDisclosure
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcToday
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 🎓 ITTC: the "Leads" tab — open leads from the Admission Tracker: the signed-in user's own
 * (≤ 500); a super admin sees everyone's (≤ 2000) and picks whose in one chip: All / My /
 * Team Leads / Callers (all of them, or one person).
 * Chips: due today / overdue / never called / hot / all, and a row of pipeline stages.
 * Live: the Tracker announces each new list (a ContentObserver) and it shows at once.
 * 📞 calls at once; a tap on the row offers WhatsApp, SMS, the other number and the lead in the
 * Tracker. The toolbar search filters by name or number.
 * 3.2: a "Today" card (+ the day's target, and the team for team leads / super admins), "＋ Add
 * lead" and live updates when signed in to ITTC directly, and "▶ Call all" (the power dialer).
 * 3.5: the 3.3 look — a blue "Today" card with the target ring, pill chips, lead cards (avatar,
 * stage pill, course · next call, the last note); swipe right calls, swipe left sets the next call.
 */
class IttcLeadsFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<IttcLeadsFragment.LeadsInnerBinding>(context, attributeSet) {

    class LeadsInnerBinding(val binding: IttcFragmentLeadsBinding) : InnerBinding {
        override val fragmentList: MyRecyclerView? = null
        override val recentsList: MyRecyclerView? = null
    }

    private enum class Filter { TODAY, OVERDUE, NEW, HOT, ALL }

    /** Whose leads (a super admin's choice; everyone else: their own) */
    private enum class Scope { MY, ALL, TEAM_LEADS, CALLERS }

    private lateinit var binding: IttcFragmentLeadsBinding
    private var all: List<IttcBridge.PhoneLead> = emptyList()
    private var filter = Filter.TODAY
    private var query = ""
    private var loadedOnce = false
    private var lastAsk = 0L
    private var textColor = 0
    private var primaryColor = 0
    private val adapter = LeadsAdapter()
    private var meta: IttcBridge.PhoneMeta? = null
    private var scope = Scope.MY
    /** one person in Team Leads / Callers; null = all of them */
    private var person: String? = null
    private var stageId: String? = null
    private var trackerClosed = false
    private var asking = false
    private var observer: android.database.ContentObserver? = null
    private val prefs by lazy { context.getSharedPreferences("ittc", Context.MODE_PRIVATE) }

    /** signed in to ITTC by itself and working that way (found off the main thread) */
    private var direct = false
    /** the rows on screen now (for "Call all") */
    private var shownLeads: List<IttcBridge.PhoneLead> = emptyList()
    /** direct mode: phone_today(); Tracker mode: the call log's [calls, connected, seconds] */
    private var today: JSONObject? = null
    private var phoneToday: IntArray? = null
    private var todayOffline = false
    private var todayAsking = false
    /** live updates held by this tab (touched on [liveExec] only) */
    @Volatile private var liveHeld = false
    /** 🎨 the 3.3 palette (follows the app's light / dark theme) */
    private var palette: IttcTheme.Palette? = null
    private var heroColor = 0
    private var actionsPalette: IttcTheme.Palette? = null

    private fun pal(): IttcTheme.Palette = palette ?: IttcTheme.of(context).also { palette = it }

    companion object {
        /** start / release in order, never two at once (the refcount stays right) */
        private val liveExec = Executors.newSingleThreadExecutor()
        @Volatile private var remindersAt = 0L
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = IttcFragmentLeadsBinding.bind(this)
        innerBinding = LeadsInnerBinding(binding)
    }

    override fun setupFragment() {
        binding.ittcLeadsList.adapter = adapter
        scope = prefs.getString("leads_scope", null)?.let { v -> Scope.values().firstOrNull { it.name == v } } ?: Scope.MY
        person = prefs.getString("leads_person", null)
        stageId = prefs.getString("leads_stage", null)
        binding.ittcLeadsStatus.setOnClickListener {
            if (trackerClosed && IttcBridge.openTracker(context)) return@setOnClickListener
            ask(force = true)
        }
        binding.ittcLeadsAdd.setOnClickListener { IttcAddLeadActivity.open(context, null, null) }
        binding.ittcLeadsCallAll.setOnClickListener { callAll() }
        binding.ittcLeadsStop.setOnClickListener {
            IttcDialer.stop(context)
            context.toast(R.string.ittc_l2_calling_stopped)
            render()
        }
        binding.ittcLeadsTeam.setOnClickListener { IttcTeamActivity.open(context) }
        setupSwipes()
        // (the last list at once, then the Tracker's)
        all = IttcBridge.myLeadsCache
        meta = IttcBridge.metaCache
        today = IttcToday.peek()
        render()
    }

    // ⚡ a new list from the Tracker shows at once while this tab is on screen
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observer == null) observer = IttcBridge.watchLeads(context) { reload() }
        syncLive()
    }

    override fun onDetachedFromWindow() {
        IttcBridge.unwatchLeads(context, observer)
        observer = null
        removeCallbacks(reloadNow)
        // (still counted as attached during this call: say it)
        syncLive(attached = false)
        super.onDetachedFromWindow()
    }

    // (the app went to the background / came back: live updates only while it is on screen)
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLive(visible = visibility == VISIBLE)
        if (visibility == VISIBLE && isAttachedToWindow && ::binding.isInitialized) {
            // (back from a call: the dialer's buttons and today's numbers)
            render()
            loadToday(force = false)
        }
    }

    /**
     * ⚡ Direct mode: live updates (IttcRealtime, counted) while this tab is on screen, and a fresh
     * list when it shows. Held at most once per tab; let go when it goes away.
     */
    private fun syncLive(attached: Boolean = isAttachedToWindow, visible: Boolean = windowVisibility == VISIBLE) {
        val app = context?.applicationContext ?: return
        val wanted = attached && visible
        liveExec.execute {
            try {
                val want = wanted && IttcDirect.isActive(app)
                if (want && !liveHeld) {
                    liveHeld = true
                    IttcRealtime.start(app)
                    IttcDirect.refreshAsync(app)
                } else if (!want && liveHeld) {
                    liveHeld = false
                    IttcRealtime.release()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private val reloadNow = Runnable { load() }

    /** (a burst of changes: read once) */
    private fun reload() {
        removeCallbacks(reloadNow)
        postDelayed(reloadNow, 300)
    }

    private fun saveChoice() {
        prefs.edit().putString("leads_scope", scope.name).putString("leads_person", person).putString("leads_stage", stageId).apply()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        this.primaryColor = properPrimaryColor
        // (the app's theme may have changed: the palette again)
        val p = IttcTheme.of(context)
        palette = p
        heroColor = 0
        actionsPalette = null
        swipePainter = null
        setBackgroundColor(p.bg)
        binding.ittcLeadsPlaceholder.setTextColor(p.text2)
        binding.ittcLeadsHint.setTextColor(p.muted)
        render()
    }

    fun refreshItems() {
        // (ask the Tracker for a fresh list now and then; it answers when it runs)
        ask(force = false)
        load()
        loadToday(force = false)
        syncLive()
    }

    /** Ask the Tracker to fetch a fresh list (at most every 20 s unless tapped) */
    private fun ask(force: Boolean) {
        val ctx = context ?: return
        if (asking || (!force && System.currentTimeMillis() - lastAsk < 20 * 1000)) return
        lastAsk = System.currentTimeMillis()
        asking = true
        renderStatus()
        IttcBridge.askRefresh(ctx) { answer ->
            trackerClosed = answer == false
            // (the new list arrives through the observer; give up waiting after a while)
            postDelayed({
                asking = false
                renderStatus()
            }, if (answer == true) 4000 else 0)
            renderStatus()
        }
    }

    private fun load() {
        val ctx = context ?: return
        ensureBackgroundThread {
            val leads = try {
                IttcBridge.myLeads(ctx)
            } catch (e: Throwable) {
                IttcCrash.record(ctx, e, "Leads tab: reading the list")
                null
            }
            val m = try {
                IttcBridge.phoneMeta(ctx)
            } catch (e: Throwable) {
                IttcCrash.record(ctx, e, "Leads tab: reading the stages")
                null
            }
            val isDirect = try {
                IttcDirect.isActive(ctx)
            } catch (_: Throwable) {
                false
            }
            // ⏰ Tracker mode: follow-up reminders from this list too (direct mode sets them itself) —
            // only when the person turned them on here (the Tracker has its own reminders: off by default)
            val now = System.currentTimeMillis()
            if (!isDirect && leads != null && now - remindersAt > 10 * 60_000) {
                try {
                    remindersAt = now
                    if (IttcReminders.enabled(ctx) && IttcReminders.chosen(ctx)) {
                        IttcReminders.schedule(ctx, leads)
                    } else {
                        // (alarms an older version set on its own go)
                        IttcReminders.cancelAll(ctx)
                    }
                } catch (e: Throwable) {
                    IttcCrash.record(ctx, e, "Leads tab: reminders")
                }
            }
            post {
                val modeChanged = direct != isDirect
                direct = isDirect
                // (the Tracker installed / signed in after this tab opened: watch it now)
                if (observer == null && isAttachedToWindow) observer = IttcBridge.watchLeads(context) { reload() }
                loadedOnce = true
                all = leads ?: emptyList()
                meta = m
                noTracker = leads == null
                // (the list is here: the Tracker answered)
                if (leads != null && m?.at != null && m.at > lastAsk - 60_000) {
                    asking = false
                    trackerClosed = false
                }
                render()
                if (modeChanged) {
                    syncLive()
                    loadToday(force = true)
                }
            }
        }
    }

    // ------------------------------------------------------------- 📊 today
    private var todayAt = 0L

    /** Today's numbers, off the main thread (the server's at most once a minute; see IttcToday) */
    private fun loadToday(force: Boolean) {
        val ctx = context ?: return
        if (todayAsking || (!force && System.currentTimeMillis() - todayAt < 30_000)) return
        todayAsking = true
        todayAt = System.currentTimeMillis()
        ensureBackgroundThread {
            var t: JSONObject? = null
            var p: IntArray? = null
            var off = false
            try {
                if (IttcDirect.isActive(ctx)) {
                    t = IttcToday.direct(ctx)
                    off = IttcToday.offline
                } else {
                    p = IttcToday.fromCallLog(ctx)
                }
            } catch (e: Throwable) {
                IttcCrash.record(ctx, e, "Leads tab: today")
            }
            post {
                todayAsking = false
                today = t
                phoneToday = p
                todayOffline = off
                renderToday()
            }
        }
    }

    private fun renderToday() {
        if (!::binding.isInitialized) return
        try {
            renderTodayNow()
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab: today card")
            binding.ittcLeadsToday.beVisibleIf(false)
        }
    }

    private fun renderTodayNow() {
        val b = binding
        val t = today
        val ph = phoneToday
        val calls: Int
        val connected: Int
        val talkSec: Int
        var extra: String? = null
        var target = meta?.dailyTarget ?: 0
        var team = false
        if (t != null) {
            val me = t.optJSONObject("me") ?: JSONObject()
            calls = me.optInt("calls")
            connected = me.optInt("connected")
            talkSec = me.optInt("talk_sec")
            extra = context.getString(R.string.ittc_a_today_extra, me.optInt("stage_changes"), me.optInt("new_leads"))
            t.optInt("target", 0).takeIf { it > 0 }?.let { target = it }
            team = t.optJSONArray("team") != null
        } else if (ph != null) {
            calls = ph[0]
            connected = ph[1]
            talkSec = ph[2]
        } else {
            b.ittcLeadsToday.beVisibleIf(false)
            return
        }
        if (target > 0 && calls >= target) {
            extra = listOfNotNull(context.getString(R.string.ittc_a_target_done), extra).joinToString(" · ")
        }

        // 🎨 light: the blue card with white text; dark: a deep blue card with the light text
        val p = pal()
        val heroBg = if (p.dark) p.primarySoft else p.primary
        val strong = if (p.dark) p.text else p.onPrimary
        val soft = if (p.dark) p.muted else p.onPrimaryMuted
        if (heroColor != heroBg) {
            heroColor = heroBg
            b.ittcLeadsToday.background = IttcTheme.rounded(context, heroBg, 20f)
            b.ittcLeadsRing.setColors(if (p.dark) p.outline else p.onPrimary.adjustAlpha(0.22f), p.ring, strong, soft)
            listOf(b.ittcLeadsTodayTitle, b.ittcLeadsStatCalls, b.ittcLeadsStatConnected, b.ittcLeadsStatTalk).forEach { it.setTextColor(strong) }
            listOf(b.ittcLeadsStatCallsLabel, b.ittcLeadsStatConnectedLabel, b.ittcLeadsStatTalkLabel, b.ittcLeadsTodayStats).forEach { it.setTextColor(soft) }
            b.ittcLeadsTeam.background = IttcTheme.rounded(context, if (p.dark) p.surfaceAlt else p.onPrimary.adjustAlpha(0.16f), 999f)
            b.ittcLeadsTeam.setTextColor(strong)
        }

        b.ittcLeadsRing.set(
            calls,
            target,
            calls.toString(),
            if (target > 0) context.getString(R.string.ittc_a_of_target, target) else context.getString(R.string.ittc_a_calls),
        )
        b.ittcLeadsRing.contentDescription = if (target > 0) {
            context.getString(R.string.ittc_a_ring_target, calls, target)
        } else {
            context.getString(R.string.ittc_a_ring_calls, calls)
        }
        b.ittcLeadsTodayTitle.text = context.getString(R.string.ittc_a_today) +
            if (t != null && todayOffline) " · " + context.getString(R.string.ittc_a_offline) else ""
        b.ittcLeadsStatCalls.text = calls.toString()
        b.ittcLeadsStatConnected.text = connected.toString()
        b.ittcLeadsStatTalk.text = IttcToday.duration(talkSec)
        b.ittcLeadsTodayStats.text = extra
        b.ittcLeadsTodayStats.beVisibleIf(!extra.isNullOrEmpty())
        b.ittcLeadsTeam.beVisibleIf(team)
        b.ittcLeadsToday.beVisibleIf(true)
    }

    // ------------------------------------------------------------- ▶ power dialer
    private fun callable(): List<IttcBridge.PhoneLead> = shownLeads.filter { !it.callNumber.isNullOrBlank() }

    private fun callAll() {
        val act = activity ?: return
        if (IttcDialer.active) {
            // (calling through a list already: on with the next one)
            IttcDialer.next(act)
            render()
            return
        }
        val list = callable()
        if (list.isEmpty()) {
            context.toast(R.string.ittc_l2_nobody)
            return
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.ittc_l2_call_all_title)
            .setMessage(context.getString(R.string.ittc_l2_call_all_text, list.size))
            .setPositiveButton(R.string.ittc_l2_call_all_go) { _, _ ->
                IttcDialer.start(act, list)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderActions() {
        val b = binding
        IttcDialer.load(context)
        val dialing = IttcDialer.active
        val n = callable().size
        b.ittcLeadsAdd.beVisibleIf(direct)
        b.ittcLeadsCallAll.text = if (dialing) {
            context.getString(R.string.ittc_a_call_next, IttcDialer.remaining)
        } else {
            context.getString(R.string.ittc_a_call_all, n)
        }
        b.ittcLeadsCallAll.beVisibleIf(dialing && IttcDialer.remaining > 0 || !dialing && n > 0)
        b.ittcLeadsStop.beVisibleIf(dialing)
        // 🎨 teal "Call all", outlined "+ Add lead" and "Stop" (made again only when the theme changes)
        val p = pal()
        if (actionsPalette !== p) {
            actionsPalette = p
            b.ittcLeadsCallAll.background = IttcTheme.rounded(context, p.call, 999f)
            b.ittcLeadsCallAll.setTextColor(p.onPrimary)
            b.ittcLeadsCallAll.setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getColoredDrawableWithColor(R.drawable.ic_play_vector, p.onPrimary), null, null, null,
            )
            b.ittcLeadsAdd.background = IttcTheme.rounded(context, p.surface, 999f, p.primary)
            b.ittcLeadsAdd.setTextColor(p.primary)
            b.ittcLeadsStop.background = IttcTheme.rounded(context, p.surface, 999f, p.danger)
            b.ittcLeadsStop.setTextColor(p.danger)
        }
        b.ittcLeadsActions.beVisibleIf(b.ittcLeadsAdd.visibility == VISIBLE || b.ittcLeadsCallAll.visibility == VISIBLE || dialing)
    }

    private var noTracker = false

    override fun onSearchClosed() {
        query = ""
        render()
    }

    override fun onSearchQueryChanged(text: String) {
        query = text.trim()
        render()
    }

    // ------------------------------------------------------------- filter
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun matches(f: Filter, l: IttcBridge.PhoneLead, today: Long): Boolean {
        val tomorrow = today + DateUtils.DAY_IN_MILLIS
        return when (f) {
            Filter.TODAY -> l.nextCall != null && l.nextCall in today until tomorrow
            Filter.OVERDUE -> l.nextCall != null && l.nextCall < today
            Filter.NEW -> l.attempts == 0 && l.lastContacted == null
            Filter.HOT -> l.rating.equals("hot", true)
            Filter.ALL -> true
        }
    }

    // ------------------------------------------------------------- scope
    private val isSuper get() = meta?.isSuper == true

    private fun inScope(l: IttcBridge.PhoneLead): Boolean {
        if (!isSuper) return true // (the Tracker only hands over the user's own leads)
        val m = meta ?: return true
        return when (scope) {
            Scope.ALL -> true
            Scope.MY -> l.assignedTo == m.meId
            Scope.TEAM_LEADS, Scope.CALLERS -> {
                val p = person
                if (p != null) {
                    l.assignedTo == p
                } else {
                    val role = if (scope == Scope.TEAM_LEADS) "team_lead" else "caller"
                    l.assignedTo != null && m.people.any { it.id == l.assignedTo && it.role == role }
                }
            }
        }
    }

    private fun scopeLabel(): String {
        val m = meta
        val name = person?.let { id -> m?.people?.firstOrNull { it.id == id }?.name }
        return when (scope) {
            Scope.MY -> context.getString(R.string.ittc_scope_my)
            Scope.ALL -> context.getString(R.string.ittc_scope_all)
            Scope.TEAM_LEADS -> name?.let { "🧑‍💼 $it" } ?: context.getString(R.string.ittc_scope_team_leads)
            Scope.CALLERS -> name?.let { "📞 $it" } ?: context.getString(R.string.ittc_scope_callers)
        } + " ▾"
    }

    /** One chip, one choice: All / My / Team Leads ▸ / Callers ▸ */
    private fun pickScope(anchor: android.view.View) {
        val m = meta ?: return
        val menu = android.widget.PopupMenu(context, anchor)
        val actions = HashMap<Int, () -> Unit>()
        var id = 1
        fun add(target: android.view.Menu, title: String, action: () -> Unit) {
            val itemId = id++
            target.add(0, itemId, itemId, title)
            actions[itemId] = action
        }
        add(menu.menu, context.getString(R.string.ittc_scope_all)) { setScope(Scope.ALL, null) }
        add(menu.menu, context.getString(R.string.ittc_scope_my)) { setScope(Scope.MY, null) }
        listOf(
            Triple(Scope.TEAM_LEADS, "team_lead", R.string.ittc_scope_team_leads to R.string.ittc_scope_all_team_leads),
            Triple(Scope.CALLERS, "caller", R.string.ittc_scope_callers to R.string.ittc_scope_all_callers),
        ).forEach { (sc, role, labels) ->
            val sub = menu.menu.addSubMenu(0, id++, id, context.getString(labels.first))
            add(sub, context.getString(labels.second)) { setScope(sc, null) }
            m.people.filter { it.role == role }.forEach { p -> add(sub, p.name) { setScope(sc, p.id) } }
        }
        menu.setOnMenuItemClickListener { item ->
            actions[item.itemId]?.invoke()
            actions.containsKey(item.itemId)
        }
        menu.show()
    }

    private fun setScope(sc: Scope, p: String?) {
        scope = sc
        person = p
        saveChoice()
        render()
    }

    /** Draw the tab; an error is kept (to report) and shown instead of stopping the app */
    private fun render() {
        if (!::binding.isInitialized) return
        try {
            renderNow()
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab")
            binding.ittcLeadsPlaceholder.text = "⚠️ The Leads tab could not be shown (${e.javaClass.simpleName}: ${e.message})"
            binding.ittcLeadsPlaceholder.beVisibleIf(true)
            binding.ittcLeadsList.beVisibleIf(false)
        }
    }

    private fun renderNow() {
        val today = startOfToday()
        val scoped = all.filter { inScope(it) }
        // (a stage that is gone: all stages)
        val stages = meta?.stages.orEmpty()
        if (meta != null && stageId != null && stages.none { it.id == stageId }) stageId = null
        val staged = scoped.filter { stageId == null || it.stageId == stageId }
        renderChips(today, staged)
        renderStages(scoped.filter { query.isNotEmpty() || matches(filter, it, today) }, stages)
        renderStatus()
        val q = query.lowercase(Locale.getDefault())
        val digits = query.filter { it.isDigit() }
        val shown = staged.filter { l ->
            (query.isNotEmpty() || matches(filter, l, today)) &&
                (q.isEmpty() || l.name.lowercase(Locale.getDefault()).contains(q) ||
                    (digits.length >= 3 && listOfNotNull(l.phone, l.altPhone, l.whatsapp).any { it.filter(Char::isDigit).contains(digits) }))
        }.sortedWith(compareBy<IttcBridge.PhoneLead> { it.nextCall == null }.thenBy { it.nextCall ?: Long.MAX_VALUE })
        adapter.submit(shown)
        shownLeads = shown
        renderActions()
        renderToday()
        val placeholder = when {
            !loadedOnce && all.isEmpty() -> context.getString(R.string.ittc_leads_loading)
            noTracker -> context.getString(
                when {
                    !org.fossify.phone.direct.IttcDirect.configured -> R.string.ittc_leads_no_tracker
                    org.fossify.phone.direct.IttcDirect.isSignedIn(context) -> R.string.ittc_l2_signed_in_not_direct
                    else -> R.string.ittc_l2_sign_in_here
                },
            )
            shown.isEmpty() -> context.getString(if (direct) R.string.ittc_l2_leads_none_direct else R.string.ittc_leads_none)
            else -> null
        }
        binding.ittcLeadsPlaceholder.text = placeholder
        binding.ittcLeadsPlaceholder.beVisibleIf(placeholder != null)
        // (no leads from anywhere yet: a tap opens the ITTC sign-in)
        if (noTracker && org.fossify.phone.direct.IttcDirect.configured) {
            binding.ittcLeadsPlaceholder.setOnClickListener {
                context.startActivity(android.content.Intent(context, org.fossify.phone.activities.IttcAccountActivity::class.java))
            }
        } else {
            binding.ittcLeadsPlaceholder.setOnClickListener(null)
            binding.ittcLeadsPlaceholder.isClickable = false
        }
        binding.ittcLeadsList.beVisibleIf(placeholder == null)
        binding.ittcLeadsHint.beVisibleIf(placeholder == null && !swipeLearned())
    }

    /** The chip looks: a dark pill when chosen, an outlined one otherwise; "Overdue" red */
    private enum class ChipKind { NORMAL, DANGER, SCOPE }

    private fun chipView(label: String, on: Boolean, kind: ChipKind = ChipKind.NORMAL, color: Int? = null, onClick: (android.view.View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = if (on || kind != ChipKind.NORMAL) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            minHeight = IttcTheme.dp(context, 36f)
            val pad = IttcTheme.dp(context, 14f)
            setPadding(pad, 0, pad, 0)
            val p = pal()
            when {
                on && color != null -> IttcTheme.pill(this, color, color.getContrastColor())
                on && kind == ChipKind.DANGER -> IttcTheme.pill(this, p.danger, p.surface)
                on -> IttcTheme.pill(this, p.text, p.surface)
                kind == ChipKind.DANGER -> IttcTheme.pill(this, p.dangerSoft, p.danger, p.danger.adjustAlpha(0.3f))
                kind == ChipKind.SCOPE -> IttcTheme.pill(this, p.primarySoft, p.primary)
                else -> IttcTheme.pill(this, p.surface, p.text, p.outline)
            }
            setOnClickListener { onClick(it) }
        }

    private fun addChip(row: ViewGroup, chip: TextView) {
        row.addView(
            chip,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = IttcTheme.dp(context, 8f)
            },
        )
    }

    private fun renderChips(today: Long, list: List<IttcBridge.PhoneLead>) {
        val row = binding.ittcLeadsChips
        row.removeAllViews()
        // (a super admin: whose leads, one choice in one chip)
        if (isSuper) addChip(row, chipView(scopeLabel(), false, ChipKind.SCOPE) { pickScope(it) })
        listOf(
            Filter.TODAY to R.string.ittc_chip_today,
            Filter.OVERDUE to R.string.ittc_chip_overdue,
            Filter.NEW to R.string.ittc_chip_new,
            Filter.HOT to R.string.ittc_chip_hot,
            Filter.ALL to R.string.ittc_chip_all,
        ).forEach { (f, label) ->
            val n = list.count { matches(f, it, today) }
            // (overdue is red while there are any)
            val kind = if (f == Filter.OVERDUE && n > 0) ChipKind.DANGER else ChipKind.NORMAL
            addChip(
                row,
                chipView(context.getString(label, n), f == filter, kind) {
                    filter = f
                    render()
                },
            )
        }
    }

    /** 🧭 All stages · New 42 · Interested 18 · … (the counts follow the chips above) */
    private fun renderStages(list: List<IttcBridge.PhoneLead>, stages: List<IttcBridge.Stage>) {
        val row = binding.ittcLeadsStages
        row.removeAllViews()
        binding.ittcLeadsStagesScroll.beVisibleIf(stages.isNotEmpty())
        if (stages.isEmpty()) return
        addChip(
            row,
            chipView(context.getString(R.string.ittc_stage_all, list.size), stageId == null) {
                stageId = null
                saveChoice()
                render()
            },
        )
        val counts = list.groupingBy { it.stageId }.eachCount()
        stages.forEach { st ->
            val n = counts[st.id] ?: 0
            // (a stage with nobody in it stays out of the way, unless it is the chosen one)
            if (n == 0 && st.id != stageId) return@forEach
            addChip(
                row,
                chipView("${st.name} $n", st.id == stageId, color = st.color) {
                    stageId = if (stageId == st.id) null else st.id
                    saveChoice()
                    render()
                },
            )
        }
    }

    /** "⟳ Updated 2 min ago" · "⟳ Updating…" · "the Tracker is closed" */
    private fun renderStatus() {
        if (!::binding.isInitialized) return
        val at = meta?.at
        val text = when {
            noTracker -> null
            trackerClosed -> context.getString(R.string.ittc_tracker_closed)
            asking -> context.getString(R.string.ittc_updating)
            at != null -> context.getString(
                R.string.ittc_updated,
                DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> null
        }
        binding.ittcLeadsStatus.text = text
        binding.ittcLeadsStatus.setTextColor(if (trackerClosed) pal().danger else pal().muted)
        binding.ittcLeadsStatus.beVisibleIf(text != null)
    }

    // ------------------------------------------------------------- rows
    /** "in 20 min" / "2 h" / "3 d" (how far a time is from now) */
    private fun span(ms: Long): String {
        val min = (Math.abs(ms) / 60_000L).coerceAtLeast(1)
        return when {
            min < 60 -> context.getString(R.string.ittc_a_min, min)
            min < 24 * 60 -> context.getString(R.string.ittc_a_hours, min / 60)
            else -> context.getString(R.string.ittc_a_days, min / (24 * 60))
        }
    }

    /** "Next call 10:30 AM · in 20 min" / "Overdue 2 h" (red) / "Last call 3 days ago" / "Never called" */
    private fun whenText(l: IttcBridge.PhoneLead, now: Long, today: Long): String {
        val next = l.nextCall ?: return l.lastContacted?.let {
            context.getString(R.string.ittc_a_last_call, DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS))
        } ?: context.getString(R.string.ittc_a_never_called)
        return when {
            next < now -> context.getString(R.string.ittc_a_overdue, span(now - next))
            next < today + DateUtils.DAY_IN_MILLIS -> context.getString(
                R.string.ittc_a_next_today,
                DateUtils.formatDateTime(context, next, DateUtils.FORMAT_SHOW_TIME),
                span(next - now),
            )
            else -> context.getString(
                R.string.ittc_a_next_at,
                DateUtils.formatDateTime(context, next, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME),
            )
        }
    }

    private fun ownerName(l: IttcBridge.PhoneLead): String {
        val m = meta
        return when {
            l.assignedTo == null -> "👤 —"
            l.assignedTo == m?.meId -> "👤 Me"
            else -> "👤 " + (m?.people?.firstOrNull { it.id == l.assignedTo }?.name?.substringBefore(' ') ?: "Other")
        }
    }

    private fun call(number: String?) {
        if (number.isNullOrBlank()) return
        activity?.startCallIntent(number)
    }

    /** IttcTheme.initials without a regex per row (lists bind many rows) */
    private fun initialsOf(name: String?): String {
        val words = name.orEmpty().split(' ', '\t', '\n', '\u00A0').filter { w -> w.firstOrNull()?.isLetter() == true }
        return when {
            words.isEmpty() -> "#"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /** A swipe's call: asks first when "confirm before calling" is on (as Recents) */
    private fun callConfirmed(l: IttcBridge.PhoneLead) {
        val number = l.callNumber?.takeIf { it.isNotBlank() } ?: return
        activity?.startCallWithConfirmationCheck(number, l.name.ifEmpty { number })
    }

    private fun showOptions(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        l.callNumber?.let { n -> items += "${context.getString(R.string.ittc_lead_call)}  $n" to { call(n) } }
        l.altPhone?.takeIf { it != l.callNumber }?.let { n -> items += "${context.getString(R.string.ittc_lead_call_alt)}  $n" to { call(n) } }
        (l.whatsapp ?: l.phone)?.let { n ->
            items += context.getString(R.string.ittc_whatsapp) to {
                val d = n.filter(Char::isDigit).let { if (it.startsWith("0") && it.length == 11) "88$it" else it }
                tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d")))
            }
        }
        l.callNumber?.let { n -> items += context.getString(R.string.ittc_sms) to { tryStart(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n"))) } }
        items += context.getString(if (direct) R.string.ittc_l2_open_lead else R.string.ittc_lead_open) to { IttcBridge.openLead(act, l.leadId) }
        AlertDialog.Builder(act)
            .setTitle(l.name.ifEmpty { l.callNumber ?: "" })
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun tryStart(intent: Intent) {
        try {
            activity?.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- 👉 swipes
    /** Swipe right: call; swipe left: the next call (direct) / the lead (Tracker) */
    private fun setupSwipes() {
        val list = binding.ittcLeadsList
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                @Suppress("DEPRECATION")
                val l = adapter.at(vh.adapterPosition) ?: return 0
                return if (l.callNumber.isNullOrBlank()) ItemTouchHelper.LEFT else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            }

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f

            // (once a row is really being swiped, the tabs' pager must not take the move; until then it may)
            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) list.parent?.requestDisallowInterceptTouchEvent(true)
            }

            // (a quick flick is not enough: no call by accident)
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 4

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                @Suppress("DEPRECATION")
                val pos = vh.adapterPosition
                val l = adapter.at(pos)
                // (the card comes back: the swipe only starts the action)
                if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
                if (l == null) return
                learnSwipe()
                if (direction == ItemTouchHelper.RIGHT) callConfirmed(l) else nextCallFor(l)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    try {
                        painter().draw(c, vh.itemView, dX)
                    } catch (_: Throwable) {
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }).attachToRecyclerView(list)
    }

    private fun swipeLearned() = prefs.getBoolean("leads_swipe_learned", false)

    private fun learnSwipe() {
        if (swipeLearned()) return
        prefs.edit().putBoolean("leads_swipe_learned", true).apply()
        binding.ittcLeadsHint.beVisibleIf(false)
    }

    private var swipePainter: SwipePainter? = null

    private fun painter(): SwipePainter = swipePainter ?: SwipePainter(pal()).also { swipePainter = it }

    /** What shows under a card while it is swiped (paints made once per theme) */
    private inner class SwipePainter(p: IttcTheme.Palette) {
        private val callPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.call }
        private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.onPrimary
            textSize = IttcTheme.dp(context, 13f).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        private val callIcon: Drawable = resources.getColoredDrawableWithColor(R.drawable.ic_phone_vector, p.onPrimary)
        private val nextIcon: Drawable = resources.getColoredDrawableWithColor(R.drawable.ic_clock_vector, p.onPrimary)
        private val callLabel = context.getString(R.string.ittc_a_swipe_call)
        private val nextLabel = context.getString(R.string.ittc_a_swipe_next)
        private val rect = RectF()
        private val radius = IttcTheme.dp(context, 16f).toFloat()
        private val icon = IttcTheme.dp(context, 18f)
        private val gap = IttcTheme.dp(context, 20f)

        fun draw(c: Canvas, item: android.view.View, dX: Float) {
            // (the card's place: the row minus its padding)
            rect.set(
                (item.left + item.paddingLeft).toFloat(), (item.top + item.paddingTop).toFloat(),
                (item.right - item.paddingRight).toFloat(), (item.bottom - item.paddingBottom).toFloat(),
            )
            val right = dX > 0
            c.drawRoundRect(rect, radius, radius, if (right) callPaint else nextPaint)
            val d = if (right) callIcon else nextIcon
            val label = if (right) callLabel else nextLabel
            val cy = rect.centerY()
            val textW = textPaint.measureText(label)
            val iconLeft = if (right) (rect.left + gap).toInt() else (rect.right - gap - textW - icon - gap / 3).toInt()
            d.setBounds(iconLeft, (cy - icon / 2).toInt(), iconLeft + icon, (cy + icon / 2).toInt())
            d.draw(c)
            c.drawText(label, iconLeft + icon + gap / 3f, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }

    // ------------------------------------------------------------- ⏰ next call (swipe left)
    private fun nextCallFor(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        // (Tracker mode: the lead in the Tracker, as a tap does)
        if (!direct) {
            if (!IttcBridge.openLead(act, l.leadId)) showOptions(l)
            return
        }
        val now = Calendar.getInstance()
        fun at(daysAhead: Int, hour: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val choices = ArrayList<Pair<String, (() -> Unit)>>()
        choices += context.getString(R.string.ittc_a_in_1h) to { saveNext(l, System.currentTimeMillis() + 3600_000L) }
        if (now.get(Calendar.HOUR_OF_DAY) < 17) choices += context.getString(R.string.ittc_a_this_evening) to { saveNext(l, at(0, 18)) }
        choices += context.getString(R.string.ittc_a_tomorrow) to { saveNext(l, at(1, 10)) }
        choices += context.getString(R.string.ittc_a_in_2_days) to { saveNext(l, at(2, 10)) }
        choices += context.getString(R.string.ittc_a_pick) to { pickDateTime(l) }
        AlertDialog.Builder(act)
            .setTitle(context.getString(R.string.ittc_a_next_title, l.name.ifEmpty { l.callNumber.orEmpty() }))
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDateTime(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        val c = Calendar.getInstance().apply { l.nextCall?.takeIf { it > System.currentTimeMillis() }?.let { timeInMillis = it } }
        DatePickerDialog(act, { _, y, mo, d ->
            TimePickerDialog(act, { _, h, mi ->
                val at = Calendar.getInstance().apply {
                    set(y, mo, d, h, mi, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > System.currentTimeMillis()) saveNext(l, at) else context.toast(R.string.ittc_a_in_past)
            }, 10, 0, android.text.format.DateFormat.is24HourFormat(act)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    /** {"kind":"next"} into the outbox (sent at once with internet); the row moves at once */
    private fun saveNext(l: IttcBridge.PhoneLead, at: Long) {
        val act = activity ?: return
        IttcDisclosure.showOnce(act) {
            val app = act.applicationContext
            ensureBackgroundThread {
                val ok = try {
                    IttcDirect.enqueue(app, JSONObject().put("kind", "next").put("lead_id", l.leadId).put("due_at", at))
                    true
                } catch (e: Throwable) {
                    IttcCrash.record(app, e, "Leads tab: next call")
                    false
                }
                post {
                    if (!ok) {
                        context.toast(R.string.ittc_a_next_failed)
                        return@post
                    }
                    // (shown at once; the server's list confirms it in a moment)
                    all = all.map { if (it.leadId == l.leadId) it.copy(nextCall = at) else it }
                    render()
                    context.toast(
                        context.getString(
                            R.string.ittc_a_next_set,
                            DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME),
                        ),
                    )
                }
            }
        }
    }

    private inner class LeadsAdapter : RecyclerView.Adapter<LeadsAdapter.Holder>() {
        private var items: List<IttcBridge.PhoneLead> = emptyList()

        fun submit(list: List<IttcBridge.PhoneLead>) {
            items = list
            notifyDataSetChanged()
        }

        fun at(position: Int): IttcBridge.PhoneLead? = items.getOrNull(position)

        /** (each card's shapes made once; only their colours change on bind) */
        inner class Holder(val b: IttcItemLeadBinding) : RecyclerView.ViewHolder(b.root) {
            val card = GradientDrawable().apply { cornerRadius = IttcTheme.dp(context, 16f).toFloat() }
            val avatar = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val pill = GradientDrawable().apply { cornerRadius = IttcTheme.dp(context, 999f).toFloat() }
            val callBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

            init {
                b.ittcLeadHolder.background = card
                b.ittcLeadAvatar.background = avatar
                b.ittcLeadStage.background = pill
                b.ittcLeadCall.background = callBg
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(IttcItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val l = items[position]
            val p = pal()
            val now = System.currentTimeMillis()
            val today = startOfToday()
            holder.card.setColor(p.surface)
            holder.b.apply {
                val title = l.name.ifEmpty { l.callNumber.orEmpty() }
                val (avBg, avFg) = IttcTheme.avatarColors(context, title)
                holder.avatar.setColor(avBg)
                ittcLeadAvatar.setTextColor(avFg)
                ittcLeadAvatar.text = initialsOf(l.name)

                ittcLeadName.text = if (l.rating.equals("hot", true)) "$title 🔥" else title
                ittcLeadName.setTextColor(p.text)

                val stage = l.stage?.takeIf { it.isNotBlank() }
                ittcLeadStage.beVisibleIf(stage != null)
                if (stage != null) {
                    val (sBg, sFg) = IttcTheme.stageColors(context, stage)
                    holder.pill.setColor(sBg)
                    ittcLeadStage.setTextColor(sFg)
                    ittcLeadStage.text = stage
                }

                val owner = if (isSuper && scope != Scope.MY && person == null) ownerName(l) else null
                val overdue = l.nextCall != null && l.nextCall < now
                ittcLeadLine.text = listOfNotNull(owner, l.course, l.batch, whenText(l, now, today)).joinToString(" · ")
                ittcLeadLine.setTextColor(if (overdue) p.danger else p.muted)
                ittcLeadLine.setTypeface(null, if (overdue) Typeface.BOLD else Typeface.NORMAL)

                ittcLeadNote.text = l.lastNote?.let { "“$it”" }
                ittcLeadNote.setTextColor(p.text2)
                ittcLeadNote.beVisibleIf(!l.lastNote.isNullOrBlank())

                holder.callBg.setColor(p.callSoft)
                ittcLeadCall.setColorFilter(p.call)
                ittcLeadCall.setOnClickListener { call(l.callNumber) }
                ittcLeadCall.beVisibleIf(l.callNumber != null)
                // (direct mode: the lead's own screen; long press: the quick choices)
                ittcLeadHolder.setOnClickListener {
                    val act = activity
                    if (direct && act != null) IttcLeadActivity.open(act, l.leadId) else showOptions(l)
                }
                ittcLeadHolder.setOnLongClickListener {
                    showOptions(l)
                    true
                }
            }
        }
    }
}
