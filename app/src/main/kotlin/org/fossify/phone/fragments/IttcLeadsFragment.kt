package org.fossify.phone.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.R
import org.fossify.phone.activities.IttcAddLeadActivity
import org.fossify.phone.activities.IttcAdmitActivity
import org.fossify.phone.activities.IttcCUi
import org.fossify.phone.activities.IttcLeadActivity
import org.fossify.phone.activities.IttcLeadSheet
import org.fossify.phone.activities.IttcStageMoveSheet
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
import org.fossify.phone.helpers.IttcRingView
import org.fossify.phone.helpers.IttcStageMove
import org.fossify.phone.helpers.IttcTabBadges
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcToday
import org.fossify.phone.helpers.IttcUi
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 🎓 ITTC: the "Leads" tab — open leads from the Admission Tracker: the signed-in user's own
 * (≤ 500); a super admin sees everyone's (≤ 2000) and picks whose in one chip: All / My /
 * Team Leads / Callers (all of them, or one person).
 * Filters: due today / overdue / never called / hot / all, and a pipeline stage.
 * Live: the Tracker announces each new list (a ContentObserver) and it shows at once.
 * 📞 calls at once; a tap on the row offers WhatsApp, SMS, the other number and the lead in the
 * Tracker. The toolbar search filters by name or number.
 * 3.2: a "Today" card (+ the day's target, and the team for team leads / super admins), "＋ Add
 * lead" and live updates when signed in to ITTC directly, and "▶ Call all" (the power dialer).
 * 3.5: the 3.3 look — a blue "Today" card with the target ring, pill chips, lead cards (avatar,
 * stage pill, course · next call, the last note); swipe right calls, swipe left sets the next call.
 * 3.9: ☰ list / ▦ board beside the filter chips — the board ([IttcLeadsBoard]) shows the same leads
 * as a column per stage; in direct mode a card dragged to another stage (or "Move to…" in its long
 * press sheet) moves the lead the lead page's way ([IttcStageMove]), with Undo.
 * 3.10: a compact top — the Today card is one row of four numbers (Overdue / Today also filter the
 * list; Target opens the day in full), the two chip rows are one filter bar (whose · when, the stage,
 * ✕) with a filter sheet, and the top folds away while the list scrolls down.
 * 3.11: "My list" / "All list" for everyone signed in here (the Tracker's switch): direct mode fetches
 * every lead the person may see in the Tracker; a 👤 / 👥 button before ☰ / ▦ picks whose (My by
 * default, remembered). The badge, the reminders and Done today / Target stay my own.
 */
class IttcLeadsFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<IttcLeadsFragment.LeadsInnerBinding>(context, attributeSet) {

    class LeadsInnerBinding(val binding: IttcFragmentLeadsBinding) : InnerBinding {
        override val fragmentList: MyRecyclerView? = null
        override val recentsList: MyRecyclerView? = null
    }

    private enum class Filter { TODAY, OVERDUE, NEW, HOT, ALL }

    /** Whose leads: My / All (3.11: everyone, direct mode); Team Leads / Callers a super admin's only */
    private enum class Scope { MY, ALL, TEAM_LEADS, CALLERS }

    private lateinit var binding: IttcFragmentLeadsBinding
    private var all: List<IttcBridge.PhoneLead> = emptyList()
    /** the filter the tab opens with (and ✕ / "Clear all" go back to) */
    private val defaultFilter = Filter.TODAY
    private var filter = defaultFilter
    private var query = ""
    private var loadedOnce = false
    private var lastAsk = 0L
    private var textColor = 0
    private var primaryColor = 0
    private val adapter = LeadsAdapter()
    private var meta: IttcBridge.PhoneMeta? = null
    private var scope = Scope.MY
    /** 3.11: direct mode with the server's All list, as last found (also before this tab's first read) */
    private var whoseDirect = lastDirect
    /** the list says whose each lead is (an older Tracker does not: all of it is mine) */
    private var ownersKnown = false
    /** who the list was last for (another person signed in: their whose choice is read again) */
    private var lastMeId: String? = null
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
    private var actionsPalette: IttcTheme.Palette? = null

    private fun pal(): IttcTheme.Palette = palette ?: IttcTheme.of(context).also { palette = it }

    companion object {
        /** start / release in order, never two at once (the refcount stays right) */
        private val liveExec = Executors.newSingleThreadExecutor()
        @Volatile private var remindersAt = 0L
        /** (3.11: direct mode with the All list as last found, for the next tab made) */
        @Volatile private var lastDirect = false
        /** (3.8's folded Today card, gone in 3.10: the remembered choice is removed) */
        private const val KEY_TODAY_COLLAPSED_OLD = "leads_today_collapsed"
        private const val KEY_BOARD = "leads_board"
        private const val UNDO_MS = 5_000L
        /** a board move is shown over the server's list until the server has it (or this long after it left the phone) */
        private const val PENDING_SHOWN_MS = 60_000L
        private const val PENDING_MAX_MS = 10 * 60_000L
        private const val TOP_FOLD_MS = 150L
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = IttcFragmentLeadsBinding.bind(this)
        innerBinding = LeadsInnerBinding(binding)
    }

    // 🎓 ITTC 3.6: dragging the leads list folds the main screen's dial pad
    override fun ittcScrollList(): RecyclerView = binding.ittcLeadsList

    override fun setupFragment() {
        binding.ittcLeadsList.adapter = adapter
        readChoice()
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
        // 📊 3.10: the stats row — Overdue / Today set the list's filter, Done today / Target: the day's details
        // (the 3.8 folding Today card is gone, and its remembered fold with it)
        if (prefs.contains(KEY_TODAY_COLLAPSED_OLD)) prefs.edit().remove(KEY_TODAY_COLLAPSED_OLD).apply()
        binding.ittcLeadsCellOverdue.setOnClickListener { tapFilterCell(Filter.OVERDUE) }
        binding.ittcLeadsCellToday.setOnClickListener { tapFilterCell(Filter.TODAY) }
        binding.ittcLeadsCellDone.setOnClickListener { showTodaySheet() }
        binding.ittcLeadsCellTarget.setOnClickListener { showTodaySheet() }
        // 🔎 one filter bar: whose · when / the stage open the filter sheet, ✕ back to the defaults
        binding.ittcLeadsScopePill.setOnClickListener { showFilterSheet(focusStage = false) }
        binding.ittcLeadsStagePill.setOnClickListener { showFilterSheet(focusStage = true) }
        binding.ittcLeadsClear.setOnClickListener { clearFilters() }
        binding.ittcLeadsWhose.setOnClickListener { toggleWhose() }
        binding.ittcLeadsFilters.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) post { applyPillWidths() }
        }
        // 🎨 the system's pressed ripple over the custom pill buttons; the list fills the page
        listOf(
            binding.ittcLeadsAdd, binding.ittcLeadsCallAll, binding.ittcLeadsStop,
            binding.ittcLeadsCellOverdue, binding.ittcLeadsCellToday, binding.ittcLeadsCellDone, binding.ittcLeadsCellTarget,
            binding.ittcLeadsScopePill, binding.ittcLeadsStagePill, binding.ittcLeadsClear, binding.ittcLeadsWhose,
        ).forEach { IttcUi.ripple(it) }
        binding.ittcLeadsList.setHasFixedSize(true)
        setupSwipes()
        setupTopFold()
        // ▦ 3.9: the board (remembered)
        boardOn = prefs.getBoolean(KEY_BOARD, false)
        binding.ittcLeadsViewList.setOnClickListener { setBoard(false) }
        binding.ittcLeadsViewBoard.setOnClickListener { setBoard(true) }
        listOf(binding.ittcLeadsViewList, binding.ittcLeadsViewBoard, binding.ittcLeadsUndoAction).forEach { IttcUi.ripple(it) }
        // (the last list at once, then the Tracker's)
        all = IttcBridge.myLeadsCache
        meta = IttcBridge.metaCache
        lastMeId = meta?.meId
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
        // (3.9: no drag left half done, no Undo bar timer)
        boardOrNull?.cancelDrag()
        removeCallbacks(hideMovedNow)
        // (still counted as attached during this call: say it)
        syncLive(attached = false)
        super.onDetachedFromWindow()
    }

    // ▦ 3.9: the screen turned (the main screen keeps its views): the board's columns at the new width
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        boardOrNull?.configurationChanged()
        // (the filter bar's pills: their room at the new width)
        barSignature = null
        render()
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

    /** Whose (My / All / a person) as kept */
    private fun readChoice() {
        scope = prefs.getString("leads_scope", null)?.let { v -> Scope.values().firstOrNull { it.name == v } } ?: Scope.MY
        person = prefs.getString("leads_person", null)
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
        actionsPalette = null
        swipePainter = null
        statsSignature = null
        barSignature = null
        adapter.colorsChanged()
        boardOrNull?.colorsChanged()
        toggleStyled = null
        setBackgroundColor(p.bg)
        // (3.10: the header lies over the list: the list scrolls under it)
        binding.ittcLeadsHeader.setBackgroundColor(p.bg)
        binding.ittcLeadsPlaceholder.setTextColor(p.text2)
        binding.ittcLeadsHint.setTextColor(p.muted)
        render()
    }

    fun refreshItems() {
        // (the tab shown again / refreshed: its top is never left folded away)
        if (::binding.isInitialized) showTop(animate = false)
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
        // (board moves shown before the server has them: which still wait in the outbox)
        val moved = pendingMoves.keys.toList()
        ensureBackgroundThread {
            val queued: Set<String> = if (moved.isEmpty()) emptySet() else try {
                moved.filter { IttcDirect.pendingFor(ctx, it) > 0 }.toSet()
            } catch (_: Throwable) {
                emptySet()
            }
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
            // (3.11: the server sent the All list — an older database sends my own only: no choice then)
            val allList = isDirect && try {
                IttcDirect.allList(ctx)
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
                whoseDirect = allList
                lastDirect = allList
                // (the Tracker installed / signed in after this tab opened: watch it now)
                if (observer == null && isAttachedToWindow) observer = IttcBridge.watchLeads(context) { reload() }
                loadedOnce = true
                all = withPending(leads ?: emptyList(), queued)
                // (3.11: signed out / someone else signed in: the whose choice as kept now — sign-out
                // forgot the last person's; every choice is saved when made, so reading it is safe)
                if (m?.meId != lastMeId) {
                    lastMeId = m?.meId
                    readChoice()
                }
                meta = m
                noTracker = leads == null
                // (the main screen's Leads badge: my leads due today or overdue)
                if (leads != null) IttcTabBadges.leadsChanged()
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
            renderStatsNow()
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab: today row")
            binding.ittcLeadsStats.beVisibleIf(false)
        }
    }

    /** Today's numbers as the Today card had them (null: none to show — no row, as no card before) */
    private class TodayNumbers(
        val calls: Int,
        val connected: Int,
        val talkSec: Int,
        val target: Int,
        val team: Boolean,
        /** stage changes · new leads (direct mode) */
        val extra: String?,
        val offline: Boolean,
    )

    private fun todayNumbers(): TodayNumbers? {
        val t = today
        val ph = phoneToday
        var target = meta?.dailyTarget ?: 0
        return when {
            t != null -> {
                val me = t.optJSONObject("me") ?: JSONObject()
                t.optInt("target", 0).takeIf { it > 0 }?.let { target = it }
                TodayNumbers(
                    calls = me.optInt("calls"),
                    connected = me.optInt("connected"),
                    talkSec = me.optInt("talk_sec"),
                    target = target,
                    team = t.optJSONArray("team") != null,
                    extra = context.getString(R.string.ittc_a_today_extra, me.optInt("stage_changes"), me.optInt("new_leads")),
                    offline = todayOffline,
                )
            }
            ph != null -> TodayNumbers(ph[0], ph[1], ph[2], target, team = false, extra = null, offline = false)
            else -> null
        }
    }

    /** Overdue / Today as last counted for the stats row (the leads in the list's whose + stage, as the old chips) */
    private var overdueN = 0
    private var todayN = 0
    /** (the row as last drawn: the same → not styled again) */
    private var statsSignature: List<Any?>? = null

    /**
     * 📊 3.10: one row of four — Overdue N (red while there are any) · Today N · Done today N (calls
     * made) · Target calls/target ›. Overdue / Today are the list's filter (the chosen one outlined).
     */
    private fun renderStatsNow() {
        val b = binding
        val n = todayNumbers()
        if (n == null) {
            b.ittcLeadsStats.beVisibleIf(false)
            return
        }
        val p = pal()
        val sig = listOf(p, filter, overdueN, todayN, n.calls, n.connected, n.target, n.offline)
        if (sig != statsSignature) {
            statsSignature = sig
            b.ittcLeadsStats.background = IttcTheme.rounded(context, p.surface, 16f)
            val overdue = overdueN > 0
            styleCell(b.ittcLeadsCellOverdue, filter == Filter.OVERDUE, if (overdue) p.dangerSoft else Color.TRANSPARENT)
            b.ittcLeadsCellOverdueN.text = overdueN.toString()
            b.ittcLeadsCellOverdueN.setTextColor(if (overdue) p.danger else p.text)
            b.ittcLeadsCellOverdueLabel.setTextColor(if (overdue) p.danger else p.muted)
            b.ittcLeadsCellOverdue.contentDescription = context.getString(R.string.ittc_cp_overdue_desc, overdueN)

            styleCell(b.ittcLeadsCellToday, filter == Filter.TODAY, Color.TRANSPARENT)
            b.ittcLeadsCellTodayN.text = todayN.toString()
            b.ittcLeadsCellTodayN.setTextColor(p.text)
            b.ittcLeadsCellTodayLabel.setTextColor(p.muted)
            b.ittcLeadsCellToday.contentDescription = context.getString(R.string.ittc_cp_today_desc, todayN)

            styleCell(b.ittcLeadsCellDone, false, Color.TRANSPARENT)
            b.ittcLeadsCellDoneN.text = n.calls.toString()
            b.ittcLeadsCellDoneN.setTextColor(p.call)
            b.ittcLeadsCellDoneLabel.setTextColor(p.muted)
            b.ittcLeadsCellDone.contentDescription = context.getString(R.string.ittc_cp_done_desc, n.calls)

            // (no target set: today's connected calls in its place)
            styleCell(b.ittcLeadsCellTarget, false, Color.TRANSPARENT)
            val hasTarget = n.target > 0
            b.ittcLeadsCellTargetN.text = if (hasTarget) context.getString(R.string.ittc_cp_of, n.calls, n.target) else n.connected.toString()
            b.ittcLeadsCellTargetN.setTextColor(if (hasTarget && n.calls >= n.target) p.call else p.text)
            b.ittcLeadsCellTargetLabel.setText(if (hasTarget) R.string.ittc_cp_target else R.string.ittc_cp_connected)
            b.ittcLeadsCellTargetLabel.setTextColor(p.muted)
            val chevron = resources.getColoredDrawableWithColor(R.drawable.ic_chevron_right_vector, p.muted)
            val size = IttcTheme.dp(context, 12f)
            chevron.setBounds(0, 0, size, size)
            b.ittcLeadsCellTargetLabel.setCompoundDrawablesRelative(null, null, chevron, null)
            b.ittcLeadsCellOffline.setTextColor(p.muted)
            b.ittcLeadsCellOffline.beVisibleIf(n.offline)
            val targetDesc = if (hasTarget) {
                context.getString(R.string.ittc_cp_target_desc, n.calls, n.target)
            } else {
                context.getString(R.string.ittc_cp_connected_desc, n.connected)
            }
            b.ittcLeadsCellTarget.contentDescription = if (n.offline) targetDesc + " · " + context.getString(R.string.ittc_a_offline) else targetDesc
        }
        IttcUi.fadeIn(b.ittcLeadsStats)
    }

    /** A stats cell: chosen — the soft accent with a 2 dp accent border; else [fill] (red-tinted overdue / none) */
    private fun styleCell(v: View, on: Boolean, fill: Int) {
        val p = pal()
        v.background = if (on) IttcTheme.rounded(context, p.primarySoft, 12f, p.primary, 2f) else IttcTheme.rounded(context, fill, 12f)
        v.isSelected = on
    }

    /** Overdue / Today tapped: that filter; tapped again when chosen: back to the tab's default */
    private fun tapFilterCell(f: Filter) {
        filter = if (filter == f) defaultFilter else f
        render()
    }

    /** 📊 Today in full (what the Today card showed): the ring, calls · connected · talk time, the day's extras, Team */
    private fun showTodaySheet() {
        val act = activity ?: return
        val n = todayNumbers() ?: return
        try {
            val c: Context = act
            val p = pal()
            val sheet = IttcCUi.sheet(act)
            val body = sheet.body
            val head = IttcCUi.row(c)
            val title = context.getString(R.string.ittc_a_today) + if (n.offline) " · " + context.getString(R.string.ittc_a_offline) else ""
            head.addView(IttcCUi.text(c, title, 18f, p.text, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (n.team) {
                head.addView(
                    IttcCUi.button(c, context.getString(R.string.ittc_a_team), IttcCUi.Style.OUTLINE) {
                        sheet.dialog.dismiss()
                        IttcTeamActivity.open(context)
                    },
                )
            }
            body.addView(head)

            val row = IttcCUi.row(c)
            val ring = IttcRingView(c)
            ring.setColors(p.outline, p.call, p.text, p.muted)
            ring.set(
                n.calls,
                n.target,
                n.calls.toString(),
                if (n.target > 0) context.getString(R.string.ittc_a_of_target, n.target) else context.getString(R.string.ittc_a_calls),
            )
            ring.contentDescription = if (n.target > 0) {
                context.getString(R.string.ittc_a_ring_target, n.calls, n.target)
            } else {
                context.getString(R.string.ittc_a_ring_calls, n.calls)
            }
            val ringSize = IttcTheme.dp(context, 88f)
            row.addView(ring, LinearLayout.LayoutParams(ringSize, ringSize))
            val numbers = IttcCUi.row(c)
            listOf(
                n.calls.toString() to R.string.ittc_a_calls,
                n.connected.toString() to R.string.ittc_a_connected,
                IttcToday.duration(n.talkSec) to R.string.ittc_a_talk_time,
            ).forEach { (value, label) ->
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                col.addView(IttcCUi.text(c, value, 18f, p.text, bold = true).apply { maxLines = 1 })
                col.addView(IttcCUi.text(c, context.getString(label), 11f, p.muted).apply { maxLines = 1 })
                numbers.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            row.addView(
                numbers,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcTheme.dp(context, 18f) },
            )
            body.addView(row, IttcCUi.topMargin(c, 14))

            // (stage changes · new leads / target reached)
            var extra = n.extra
            if (n.target > 0 && n.calls >= n.target) {
                extra = listOfNotNull(context.getString(R.string.ittc_a_target_done), extra).joinToString(" · ")
            }
            if (!extra.isNullOrEmpty()) body.addView(IttcCUi.text(c, extra, 12f, p.muted), IttcCUi.topMargin(c, 12))
            IttcCUi.showSafely(act, sheet.dialog)
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab: today sheet")
        }
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

    /**
     * 3.11: whose can be chosen — a super admin (everyone's list, as before) and, in direct mode,
     * everyone (the All list comes from the server). Tracker mode / an older database: only my own
     * leads come (a super admin's everyone's), so there is nothing to choose.
     */
    private val canChooseWhose get() = isSuper || whoseDirect

    /** The whose in effect: My when it cannot be chosen; Team Leads / Callers for a super admin only */
    private fun whose(sc: Scope = scope): Scope = when {
        !canChooseWhose -> Scope.MY
        !isSuper && (sc == Scope.TEAM_LEADS || sc == Scope.CALLERS) -> Scope.MY
        else -> sc
    }

    /** Team Leads / Callers (a super admin's): shown in the pill, cleared by ✕ */
    private fun subScope(sc: Scope = whose()): Boolean = sc == Scope.TEAM_LEADS || sc == Scope.CALLERS

    /** Each row / card says whose lead it is (All, or all of the team leads / callers) */
    private fun ownerShown(): Boolean = when (whose()) {
        Scope.MY -> false
        Scope.ALL -> true
        else -> person == null
    }

    private fun inScope(l: IttcBridge.PhoneLead, sc: Scope = whose(), who: String? = person): Boolean {
        val m = meta ?: return true
        return when (whose(sc)) {
            Scope.ALL -> true
            // (meId unknown / no owners in the list — an older Tracker: all of it is mine)
            Scope.MY -> m.meId == null || !ownersKnown || l.assignedTo == m.meId
            Scope.TEAM_LEADS, Scope.CALLERS -> {
                if (who != null) {
                    l.assignedTo == who
                } else {
                    val role = if (sc == Scope.TEAM_LEADS) "team_lead" else "caller"
                    l.assignedTo != null && m.people.any { it.id == l.assignedTo && it.role == role }
                }
            }
        }
    }

    /** "👤 My" / "👥 All" / "🧑‍💼 Team Leads" / "📞 Rahim" … (the whose in effect) */
    private fun scopeLabel(): String {
        val m = meta
        val name = person?.let { id -> m?.people?.firstOrNull { it.id == id }?.name }
        return when (whose()) {
            Scope.MY -> context.getString(R.string.ittc_scope_my)
            Scope.ALL -> context.getString(R.string.ittc_scope_all)
            Scope.TEAM_LEADS -> name?.let { "🧑‍💼 $it" } ?: context.getString(R.string.ittc_scope_team_leads)
            Scope.CALLERS -> name?.let { "📞 $it" } ?: context.getString(R.string.ittc_scope_callers)
        }
    }

    /** The "when to call" filters in the filter sheet's order */
    private val whenFilters = listOf(Filter.OVERDUE, Filter.TODAY, Filter.NEW, Filter.HOT, Filter.ALL)

    private fun filterName(f: Filter): String = context.getString(
        when (f) {
            Filter.OVERDUE -> R.string.ittc_cp_f_overdue
            Filter.TODAY -> R.string.ittc_cp_f_today
            Filter.NEW -> R.string.ittc_cp_f_new
            Filter.HOT -> R.string.ittc_cp_f_hot
            Filter.ALL -> R.string.ittc_cp_f_all
        },
    )

    /** The toolbar search as a test (name, or ≥ 3 digits of a number); everything when there is none */
    private fun searchTest(): (IttcBridge.PhoneLead) -> Boolean {
        if (query.isEmpty()) return { _ -> true }
        val q = query.lowercase(Locale.getDefault())
        val digits = query.filter { it.isDigit() }
        return { l ->
            l.name.lowercase(Locale.getDefault()).contains(q) ||
                (digits.length >= 3 && listOfNotNull(l.phone, l.altPhone, l.whatsapp).any { it.filter(Char::isDigit).contains(digits) })
        }
    }

    /** The board was shown at the last render (no stage choice there) */
    private var lastBoardMode = false

    /** How many leads these choices would show (the list's own rules; the filter sheet's live counts) */
    private fun countFor(sc: Scope, who: String?, st: String?, f: Filter): Int {
        val today = startOfToday()
        val found = searchTest()
        val board = lastBoardMode
        return all.count { l ->
            inScope(l, sc, who) && (board || st == null || l.stageId == st) && (query.isNotEmpty() || matches(f, l, today)) && found(l)
        }
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
            binding.ittcLeadsBoard.beVisibleIf(false)
            showTop(animate = false)
        }
    }

    /** (the choices the top was last opened for: another one → the top shows again) */
    private var topKey: List<Any?>? = null

    private fun renderNow() {
        val today = startOfToday()
        // (direct mode: the server always says whose — even when every lead is unassigned)
        ownersKnown = direct || IttcBridge.cacheIsDirect || all.any { it.assignedTo != null }
        val scoped = all.filter { inScope(it) }
        // (a stage that is gone: all stages)
        val stages = meta?.stages.orEmpty()
        if (meta != null && stageId != null && stages.none { it.id == stageId }) stageId = null
        // ▦ 3.9: the board has a column per stage (the stage choice is the list's)
        val boardMode = boardOn && stages.isNotEmpty()
        lastBoardMode = boardMode
        renderViewToggle(stages.isNotEmpty(), boardMode)
        val staged = if (boardMode) scoped else scoped.filter { stageId == null || it.stageId == stageId }
        // (the stats row's Overdue / Today: counted as the 3.9 filter chips were)
        overdueN = staged.count { matches(Filter.OVERDUE, it, today) }
        todayN = staged.count { matches(Filter.TODAY, it, today) }
        renderFilterBar(boardMode, stages)
        renderStatus()
        val found = searchTest()
        val shown = staged.filter { l ->
            (query.isNotEmpty() || matches(filter, l, today)) && found(l)
        }.sortedWith(compareBy<IttcBridge.PhoneLead> { it.nextCall == null }.thenBy { it.nextCall ?: Long.MAX_VALUE })
        if (boardMode) board.submit(shown, stages, today, boardLook()) else adapter.submit(shown)
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
            // (the board shows its columns, empty ones too)
            shown.isEmpty() && !boardMode -> context.getString(if (direct) R.string.ittc_l2_leads_none_direct else R.string.ittc_leads_none)
            else -> null
        }
        binding.ittcLeadsPlaceholder.text = placeholder
        IttcUi.fadeIf(binding.ittcLeadsPlaceholder, placeholder != null)
        // (no leads from anywhere yet: a tap opens the ITTC sign-in)
        if (noTracker && org.fossify.phone.direct.IttcDirect.configured) {
            binding.ittcLeadsPlaceholder.setOnClickListener {
                context.startActivity(android.content.Intent(context, org.fossify.phone.activities.IttcAccountActivity::class.java))
            }
        } else {
            binding.ittcLeadsPlaceholder.setOnClickListener(null)
            binding.ittcLeadsPlaceholder.isClickable = false
        }
        IttcUi.fadeIf(binding.ittcLeadsList, placeholder == null && !boardMode)
        IttcUi.fadeIf(binding.ittcLeadsBoard, placeholder == null && boardMode)
        if (!boardMode) {
            boardOrNull?.cancelDrag()
            hideMoved()
        }
        binding.ittcLeadsHint.beVisibleIf(placeholder == null && !boardMode && !swipeLearned())
        // 3.10: the top is shown again for other choices, the board and the placeholders; folded away
        // over a list that no longer scrolls: back once it is laid out
        val key = listOf(filter, stageId, whose(), person, boardMode, query)
        if (boardMode || placeholder != null || key != topKey) showTop(animate = false)
        topKey = key
        if (topHidden) {
            val list = binding.ittcLeadsList
            list.post { if (!list.canScrollVertically(-1)) showTop(animate = true) }
        }
    }

    // ------------------------------------------------------------- 🔎 3.10: the filter bar
    /** (the bar as last drawn: the same → not styled again) */
    private var barSignature: List<Any?>? = null

    /**
     * [when ▾] (a super admin's Team Leads / Callers: [🧑‍💼 whose · when ▾]) · [● stage ▾] (the list only) ·
     * [✕] when anything is not the default · 3.11: [👤 / 👥] My / All · [☰ ▦]. A changed choice: the soft
     * accent with an accent border; else the surface, outlined.
     */
    private fun renderFilterBar(boardMode: Boolean, stages: List<IttcBridge.Stage>) {
        val b = binding
        val p = pal()
        val sc = whose()
        // (My / All: the 👤 / 👥 button says it; the pill only names Team Leads / Callers)
        val whoChanged = subScope(sc)
        val whenChanged = filter != defaultFilter
        val showStage = !boardMode && stages.isNotEmpty()
        val stage = stageId?.let { id -> stages.firstOrNull { it.id == id } }
        val scopeText = (if (whoChanged) scopeLabel() + " · " else "") + filterName(filter)
        val stageText = stage?.name ?: context.getString(R.string.ittc_l3_stage_all)
        val clear = whoChanged || whenChanged || (showStage && stage != null)
        val sig = listOf(
            p, scopeText, whoChanged || whenChanged, showStage, stage?.id, stageText, clear, b.ittcLeadsViewToggle.visibility,
            canChooseWhose, sc,
        )
        if (sig == barSignature) return
        barSignature = sig
        renderWhoseButton(sc)
        stylePill(b.ittcLeadsScopePill, "$scopeText ▾", whoChanged || whenChanged, null)
        b.ittcLeadsScopePill.contentDescription = context.getString(R.string.ittc_cp_scope_desc, scopeText)
        b.ittcLeadsStagePill.beVisibleIf(showStage)
        if (showStage) {
            stylePill(b.ittcLeadsStagePill, "$stageText ▾", stage != null, stage?.let { IttcTheme.stageColors(context, it.name).second })
            b.ittcLeadsStagePill.contentDescription = context.getString(R.string.ittc_cp_stage_desc, stageText)
        }
        b.ittcLeadsClear.beVisibleIf(clear)
        // (the 36 dp circle inside its 44 dp touch area)
        b.ittcLeadsClear.background = InsetDrawable(IttcTheme.rounded(context, p.surface, 999f, p.outline), IttcTheme.dp(context, 4f))
        // (an inset background replaces the view's padding with its insets: the ✕ keeps its 18 dp)
        IttcTheme.dp(context, 13f).let { pad -> b.ittcLeadsClear.setPadding(pad, pad, pad, pad) }
        b.ittcLeadsClear.setColorFilter(p.text2)
        applyPillWidths()
    }

    private fun stylePill(v: TextView, label: String, on: Boolean, dotColor: Int?) {
        val p = pal()
        v.text = label
        if (on) IttcTheme.pill(v, p.primarySoft, p.primary, p.primary) else IttcTheme.pill(v, p.surface, p.text2, p.outline)
        v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (dotColor != null) {
            val size = IttcTheme.dp(context, 8f)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setSize(size, size)
            }
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            v.compoundDrawablePadding = IttcTheme.dp(context, 6f)
        } else {
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
        v.isSelected = on
    }

    /** The two pills share the room the bar leaves (each its full label when both fit; else the longer one is cut) */
    private fun applyPillWidths() {
        if (!::binding.isInitialized) return
        val b = binding
        val bar = b.ittcLeadsFilters
        val scopePill = b.ittcLeadsScopePill
        val stagePill = b.ittcLeadsStagePill
        val gap = IttcTheme.dp(context, 8f)
        var room = (if (bar.width > 0) bar.width else resources.displayMetrics.widthPixels) - bar.paddingLeft - bar.paddingRight
        if (b.ittcLeadsViewToggle.visibility == VISIBLE) room -= gap + 2 * resources.getDimensionPixelSize(R.dimen.ittc_touch)
        if (b.ittcLeadsWhose.visibility == VISIBLE) room -= gap + resources.getDimensionPixelSize(R.dimen.ittc_touch)
        if (b.ittcLeadsClear.visibility == VISIBLE) room -= IttcTheme.dp(context, 4f) + resources.getDimensionPixelSize(R.dimen.ittc_touch)
        val showStage = stagePill.visibility == VISIBLE
        if (showStage) room -= gap
        room = room.coerceAtLeast(IttcTheme.dp(context, 96f))
        fun wanted(v: TextView) = v.paint.measureText(v.text.toString()).toInt() + v.compoundPaddingLeft + v.compoundPaddingRight + 1
        var scopeMax = room
        var stageMax = room
        if (showStage) {
            val a = wanted(scopePill)
            val st = wanted(stagePill)
            val half = room / 2
            if (a + st > room) {
                when {
                    a <= half -> stageMax = room - a
                    st <= half -> scopeMax = room - st
                    else -> {
                        scopeMax = half
                        stageMax = room - half
                    }
                }
            }
        }
        if (scopePill.maxWidth != scopeMax) scopePill.maxWidth = scopeMax
        if (stagePill.maxWidth != stageMax) stagePill.maxWidth = stageMax
    }

    /**
     * ✕: the default filter, all stages (the list's stage stays while the board shows); Team Leads /
     * Callers back to my leads. 3.11: My / All stays (the 👤 / 👥 button's own choice).
     */
    private fun clearFilters() {
        filter = defaultFilter
        if (!lastBoardMode) stageId = null
        if (subScope()) scope = Scope.MY
        person = null
        saveChoice()
        render()
    }

    /** 3.11: 👤 My list / 👥 All list (everyone who can choose; hidden in Tracker mode, where the list is my own) */
    private fun renderWhoseButton(sc: Scope) {
        val b = binding
        val v = b.ittcLeadsWhose
        v.beVisibleIf(canChooseWhose)
        if (!canChooseWhose) return
        val p = pal()
        val mine = sc == Scope.MY
        v.setImageResource(if (mine) R.drawable.ic_person_vector else R.drawable.ic_people_vector)
        // (All: the soft accent pill inside the 44 dp touch area; My: the plain icon)
        v.background = if (mine) null else InsetDrawable(IttcTheme.rounded(context, p.primarySoft, 999f), IttcTheme.dp(context, 4f))
        // (an inset background replaces the view's padding: the icon keeps its 20 dp)
        IttcTheme.dp(context, 12f).let { pad -> v.setPadding(pad, pad, pad, pad) }
        v.setColorFilter(if (mine) p.text2 else p.primary)
        v.isSelected = !mine
        v.contentDescription = context.getString(if (mine) R.string.ittc_al_desc_my else R.string.ittc_al_desc_all)
    }

    /** 👤 ↔ 👥: My list ↔ All list (a super admin in Team Leads / Callers: back to My) */
    private fun toggleWhose() {
        if (!canChooseWhose) return
        scope = if (whose() == Scope.MY) Scope.ALL else Scope.MY
        person = null
        saveChoice()
        render()
        context.toast(if (scope == Scope.MY) R.string.ittc_al_my_list else R.string.ittc_al_all_list)
    }

    /** The filter sheet's choices until "Show N leads" */
    private class Choice(var filter: Filter, var scope: Scope, var person: String?, var stageId: String?)

    private fun showFilterSheet(focusStage: Boolean) {
        val act = activity ?: return
        try {
            showFilterSheetNow(act, focusStage)
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab: filter sheet")
        }
    }

    /**
     * 🔎 "Show leads": WHOSE (a super admin: My / All / Team Leads / Callers, then one of them), WHEN TO
     * CALL (Overdue / Today / New / Hot / All) as two columns of buttons with their counts, STAGE (the
     * list: a row per stage) and "Show N leads". The counts follow the choices made here at once; the
     * list changes on "Show".
     */
    private fun showFilterSheetNow(act: Activity, focusStage: Boolean) {
        val c: Context = act
        val p = pal()
        val sheet = IttcCUi.sheet(act)
        val body = sheet.body
        val ch = Choice(filter, whose(), person, stageId)
        val stages = if (lastBoardMode) emptyList<IttcBridge.Stage>() else meta?.stages.orEmpty()
        var stageHeader: View? = null
        fun sectionTitle(text: String): View {
            val v = IttcCUi.section(c, text)
            body.addView(v, IttcCUi.topMargin(c, 18).apply { bottomMargin = IttcCUi.dp(c, 2) })
            return v
        }
        fun draw() {
            body.removeAllViews()
            val head = IttcCUi.row(c)
            head.addView(
                IttcCUi.text(c, context.getString(R.string.ittc_cp_sheet_title), 18f, p.text, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            head.addView(
                IttcCUi.button(c, context.getString(R.string.ittc_cp_clear_all), IttcCUi.Style.GHOST) {
                    ch.filter = defaultFilter
                    ch.scope = Scope.MY
                    ch.person = null
                    if (!lastBoardMode) ch.stageId = null
                    draw()
                }.apply { setTextColor(p.primary) },
            )
            body.addView(head)

            val m = meta
            if (canChooseWhose && m != null) {
                sectionTitle(context.getString(R.string.ittc_cp_whose))
                // (3.11: My / All for everyone; Team Leads / Callers a super admin's only)
                val options = listOf(Scope.MY to R.string.ittc_scope_my, Scope.ALL to R.string.ittc_scope_all) +
                    if (isSuper) listOf(Scope.TEAM_LEADS to R.string.ittc_scope_team_leads, Scope.CALLERS to R.string.ittc_scope_callers) else emptyList()
                grid(
                    body,
                    options.map { (sc, label) ->
                        optionButton(c, context.getString(label), countFor(sc, null, ch.stageId, ch.filter), ch.scope == sc, false) {
                            ch.scope = sc
                            ch.person = null
                            draw()
                        }
                    },
                )
                // (Team Leads / Callers: one of them)
                if (ch.scope == Scope.TEAM_LEADS || ch.scope == Scope.CALLERS) {
                    val role = if (ch.scope == Scope.TEAM_LEADS) "team_lead" else "caller"
                    val people = m.people.filter { it.role == role }
                    if (people.isNotEmpty()) {
                        sectionTitle(context.getString(if (role == "team_lead") R.string.ittc_scope_team_leads else R.string.ittc_scope_callers))
                        grid(
                            body,
                            people.map { pr ->
                                optionButton(c, pr.name, countFor(ch.scope, pr.id, ch.stageId, ch.filter), ch.person == pr.id, false) {
                                    ch.person = if (ch.person == pr.id) null else pr.id
                                    draw()
                                }
                            },
                        )
                    }
                }
            }

            sectionTitle(context.getString(R.string.ittc_cp_when))
            grid(
                body,
                whenFilters.map { f ->
                    val n = countFor(ch.scope, ch.person, ch.stageId, f)
                    optionButton(c, filterName(f), n, ch.filter == f, f == Filter.OVERDUE && n > 0) {
                        ch.filter = f
                        draw()
                    }
                },
            )

            if (stages.isNotEmpty()) {
                stageHeader = sectionTitle(context.getString(R.string.ittc_cp_stage))
                body.addView(
                    stageRow(c, null, context.getString(R.string.ittc_l3_stage_all), countFor(ch.scope, ch.person, null, ch.filter), ch.stageId == null) {
                        ch.stageId = null
                        draw()
                    },
                )
                stages.forEach { st ->
                    body.addView(
                        stageRow(c, IttcTheme.stageColors(context, st.name).second, st.name, countFor(ch.scope, ch.person, st.id, ch.filter), ch.stageId == st.id) {
                            ch.stageId = if (ch.stageId == st.id) null else st.id
                            draw()
                        },
                    )
                }
            }

            val total = countFor(ch.scope, ch.person, ch.stageId, ch.filter)
            body.addView(
                IttcCUi.button(c, if (total == 1) context.getString(R.string.ittc_cp_show_one) else context.getString(R.string.ittc_cp_show_n, total), IttcCUi.Style.PRIMARY) {
                    sheet.dialog.dismiss()
                    filter = ch.filter
                    scope = ch.scope
                    person = ch.person
                    stageId = ch.stageId
                    saveChoice()
                    render()
                }.apply { minHeight = IttcCUi.dp(c, 48) },
                IttcCUi.topMargin(c, 20),
            )
        }
        draw()
        IttcCUi.showSafely(act, sheet.dialog)
        // (from the stage pill: the stages in sight)
        if (focusStage) {
            stageHeader?.let { h ->
                h.doOnLayout {
                    h.post { (body.parent as? NestedScrollView)?.smoothScrollTo(0, h.top) }
                }
            }
        }
    }

    /** Two columns of [items] (48 dp rows, 8 dp apart) */
    private fun grid(body: LinearLayout, items: List<View>) {
        val c = body.context
        val height = IttcCUi.dp(c, 48)
        items.chunked(2).forEach { pair ->
            val r = IttcCUi.row(c)
            r.addView(pair[0], LinearLayout.LayoutParams(0, height, 1f).apply { marginEnd = IttcCUi.dp(c, 8) })
            r.addView(pair.getOrNull(1) ?: View(c), LinearLayout.LayoutParams(0, height, 1f))
            body.addView(r, IttcCUi.topMargin(c, 8))
        }
    }

    /** A filter sheet choice: "Today  12" (chosen: the soft accent, 2 dp border; overdue: red-tinted) */
    private fun optionButton(c: Context, label: String, count: Int, on: Boolean, danger: Boolean, onClick: () -> Unit): TextView {
        val p = pal()
        val fg = when {
            on -> p.primary
            danger -> p.danger
            else -> p.text
        }
        return TextView(c).apply {
            text = SpannableStringBuilder(label).apply {
                append("  ")
                val start = length
                append(count.toString())
                setSpan(ForegroundColorSpan(fg.adjustAlpha(0.7f)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(fg)
            typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            val pad = IttcCUi.dp(c, 10)
            setPadding(pad, 0, pad, 0)
            background = when {
                on -> IttcTheme.rounded(c, p.primarySoft, 12f, p.primary, 2f)
                danger -> IttcTheme.rounded(c, p.dangerSoft, 12f, p.danger.adjustAlpha(0.3f))
                else -> IttcTheme.rounded(c, p.surface, 12f, p.outline)
            }
            isSelected = on
            contentDescription = context.getString(R.string.ittc_cp_option_desc, label, count)
            IttcUi.ripple(this)
            setOnClickListener { onClick() }
        }
    }

    /** A filter sheet stage row (48 dp): ● name … count ✓ ([dotColor] null: "All stages", no dot) */
    private fun stageRow(c: Context, dotColor: Int?, name: String, count: Int, on: Boolean, onClick: () -> Unit): View {
        val p = pal()
        val r = IttcCUi.row(c).apply {
            minimumHeight = IttcCUi.dp(c, 48)
            setPadding(IttcCUi.dp(c, 10), 0, IttcCUi.dp(c, 10), 0)
            background = IttcTheme.rounded(c, if (on) p.primarySoft else Color.TRANSPARENT, 12f)
            isClickable = true
            isFocusable = true
            isSelected = on
            contentDescription = context.getString(R.string.ittc_cp_option_desc, name, count)
            setOnClickListener { onClick() }
        }
        val size = IttcCUi.dp(c, 10)
        r.addView(
            View(c).apply {
                if (dotColor != null) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dotColor)
                    }
                }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(size, size),
        )
        r.addView(
            IttcCUi.text(c, name, 15f, if (on) p.primary else p.text, bold = on).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcCUi.dp(c, 12) },
        )
        r.addView(
            IttcCUi.text(c, count.toString(), 13f, p.muted).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = IttcCUi.dp(c, 8) },
        )
        val check = IttcCUi.dp(c, 20)
        r.addView(
            ImageView(c).apply {
                setImageDrawable(resources.getColoredDrawableWithColor(R.drawable.ic_check_vector, p.primary))
                visibility = if (on) VISIBLE else INVISIBLE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(check, check).apply { marginStart = IttcCUi.dp(c, 8) },
        )
        IttcUi.ripple(r)
        return r
    }

    // ------------------------------------------------------------- 3.10: the top folds away while the list scrolls down
    /**
     * The header lies over the list (the list keeps its full height, with a top padding of the header's
     * height), so folding moves only the header (its translationY): the list never moves, its scroll is
     * the finger's own. Folded, the stats row + the filter bar are above the screen; Call all, the status
     * line and the hint (the rest of the header) slide up to the top.
     */
    private var topHidden = false
    /** the list's scroll in one direction since it last turned */
    private var scrollRun = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopFold() {
        val list = binding.ittcLeadsList
        val header = binding.ittcLeadsHeader
        val step = IttcTheme.dp(context, 12f)
        // (a touch on the header's empty parts stays there: never a tap on a row hidden under it)
        header.setOnTouchListener { _, _ -> true }
        // the list's top padding follows the header's height (at the top: it stays at the top)
        header.addOnLayoutChangeListener { _, _, newTop, _, newBottom, _, oldTop, _, oldBottom ->
            if (newBottom - newTop != oldBottom - oldTop) post { syncListPadding() }
            // (folded while the top's height changed: still just out of sight)
            if (topHidden && header.translationY != -binding.ittcLeadsTop.height.toFloat()) {
                header.animate().cancel()
                header.translationY = -binding.ittcLeadsTop.height.toFloat()
            }
        }
        // (folded over a list that no longer scrolls up — it got shorter, a new list: back)
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (topHidden && !list.canScrollVertically(-1)) post { if (topHidden && !list.canScrollVertically(-1)) showTop(animate = true) }
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // (a layout's own (0, 0) and anything not moved by a finger / fling: nothing)
                if (dy == 0 || recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) return
                if (!recyclerView.canScrollVertically(-1)) {
                    scrollRun = 0
                    showTop(animate = true)
                    return
                }
                if ((dy > 0) != (scrollRun > 0)) scrollRun = 0
                scrollRun += dy
                if (scrollRun > step) {
                    scrollRun = 0
                    hideTop()
                } else if (scrollRun < -step) {
                    scrollRun = 0
                    showTop(animate = true)
                }
            }
        })
    }

    /** The list's top padding: the header's height (+ the list's own 4 dp); at the top it stays at the top */
    private fun syncListPadding() {
        if (!::binding.isInitialized) return
        val list = binding.ittcLeadsList
        val want = binding.ittcLeadsHeader.height + resources.getDimensionPixelSize(R.dimen.ittc_space_xs)
        if (list.paddingTop == want) return
        val atTop = !list.canScrollVertically(-1)
        list.setPadding(list.paddingLeft, want, list.paddingRight, list.paddingBottom)
        if (atTop) list.scrollToPosition(0)
    }

    /** Fold the top away — only over the list, and only once the list has scrolled past the header */
    private fun hideTop() {
        if (topHidden || lastBoardMode || !isAttachedToWindow) return
        val list = binding.ittcLeadsList
        val header = binding.ittcLeadsHeader
        val h = binding.ittcLeadsTop.height
        if (h == 0 || list.visibility != VISIBLE) return
        if (list.computeVerticalScrollOffset() <= header.height) return
        topHidden = true
        // (out of sight: TalkBack skips it too)
        binding.ittcLeadsTop.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        header.animate().translationY(-h.toFloat()).setDuration(TOP_FOLD_MS).start()
    }

    /** The top back (at once, or sliding down) */
    private fun showTop(animate: Boolean) {
        if (!topHidden) return
        topHidden = false
        val header = binding.ittcLeadsHeader
        binding.ittcLeadsTop.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        header.animate().cancel()
        if (animate && isAttachedToWindow) {
            header.animate().translationY(0f).setDuration(TOP_FOLD_MS).start()
        } else {
            header.translationY = 0f
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

    /**
     * 3.8: the lead's actions in a bottom sheet — call / the other number / WhatsApp / SMS, in direct
     * mode the lead page's own actions (it opens and runs the chosen one), and open the lead
     */
    private fun showOptions(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        try {
            IttcLeadSheet.show(act, l, direct, canHandOver = isSuper)
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads tab: lead sheet")
        }
    }

    // ------------------------------------------------------------- ▦ 3.9: the board
    /** The board is chosen (shown when there are stages) */
    private var boardOn = false
    /** made the first time the board shows (the list alone never pays for it) */
    private var boardOrNull: IttcLeadsBoard? = null
    private val board: IttcLeadsBoard
        get() = boardOrNull ?: IttcLeadsBoard(context, binding, boardHost).also {
            boardOrNull = it
            it.setup()
        }
    private var toggleStyled: Pair<IttcTheme.Palette, Boolean>? = null

    private val boardHost = object : IttcLeadsBoard.Host {
        override fun palette(): IttcTheme.Palette = pal()

        // (as the lead page: the stage is changed when signed in to ITTC here)
        override fun canMove(): Boolean = direct && activity != null

        override fun owner(l: IttcBridge.PhoneLead): String? =
            if (ownerShown()) ownerName(l) else null

        override fun whenText(l: IttcBridge.PhoneLead, now: Long, today: Long): String = this@IttcLeadsFragment.whenText(l, now, today)

        override fun span(ms: Long): String = this@IttcLeadsFragment.span(ms)

        override fun initials(name: String?): String = initialsOf(name)

        // (as a row's tap: the lead's own screen in direct mode, else the quick choices)
        override fun open(l: IttcBridge.PhoneLead) {
            val act = activity
            if (direct && act != null) IttcLeadActivity.open(act, l.leadId) else showOptions(l)
        }

        override fun longPress(l: IttcBridge.PhoneLead) = showBoardOptions(l)

        override fun call(l: IttcBridge.PhoneLead) = this@IttcLeadsFragment.call(l.callNumber)

        override fun whatsApp(number: String) {
            activity?.let { IttcLeadSheet.openWhatsApp(it, number) }
        }

        override fun drop(l: IttcBridge.PhoneLead, stage: IttcBridge.Stage) = requestMove(l, stage)

        override fun sheetActivity(): Activity? = activity
    }

    /** What the board's cards look like besides their leads (as the list's rows: theme, owner shown, the minute) */
    private fun boardLook(): List<Any?> {
        val ownerShown = ownerShown()
        return listOf(pal(), ownerShown, if (ownerShown) meta?.let { it.meId to it.people } else null, System.currentTimeMillis() / 60_000L)
    }

    private fun setBoard(on: Boolean) {
        if (boardOn == on) return
        boardOn = on
        prefs.edit().putBoolean(KEY_BOARD, on).apply()
        render()
    }

    /** ☰ / ▦: the chosen one white (raised) on the soft track; styled again only when it changes */
    private fun renderViewToggle(show: Boolean, boardMode: Boolean) {
        val b = binding
        b.ittcLeadsViewToggle.beVisibleIf(show)
        if (!show) return
        val p = pal()
        val key = p to boardMode
        if (toggleStyled == key) return
        toggleStyled = key
        b.ittcLeadsViewToggle.background = IttcTheme.rounded(context, p.surfaceAlt, 999f)
        val inset = IttcTheme.dp(context, 4f)
        listOf(b.ittcLeadsViewList to !boardMode, b.ittcLeadsViewBoard to boardMode).forEach { (v, on) ->
            // (dark: the chosen one in the soft accent, a white pill would glare)
            v.background = if (on) InsetDrawable(IttcTheme.rounded(context, if (p.dark) p.primarySoft else p.surface, 999f), inset) else null
            // (the inset background would replace the icon's padding: keep it the same on both)
            IttcTheme.dp(context, 12f).let { pad -> v.setPadding(pad, pad, pad, pad) }
            v.elevation = if (on) IttcTheme.dp(context, 2f).toFloat() else 0f
            v.setColorFilter(if (on) p.primary else p.muted)
            v.isSelected = on
        }
    }

    /** The long press sheet on the board: + "Move to…" (direct mode: the stages of the lead's course) */
    private fun showBoardOptions(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        try {
            // (3.11: no "Move to…" on a lead I may only see)
            val movable = direct && l.canEdit
            val targets = if (movable) meta?.stages.orEmpty().filter { it.forCourse(l.courseId) } else emptyList()
            val onMove: ((IttcBridge.Stage) -> Unit)? = if (movable) ({ st: IttcBridge.Stage -> requestMove(l, st) }) else null
            IttcLeadSheet.show(act, l, direct, canHandOver = isSuper, moveTo = targets, onMove = onMove)
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads board: lead sheet")
        }
    }

    /**
     * Move [l] to [st] (a drop on the board, or "Move to…"): the lead page's rules — a stage that
     * needs a note or a date, or closes the lead, is asked first (a sheet); Admitted goes to the
     * admit form; any other stage moves at once (with Undo).
     */
    private fun requestMove(l: IttcBridge.PhoneLead, st: IttcBridge.Stage) {
        val act = activity ?: return
        if (!direct || l.stageId == st.id) return
        // (3.11: a lead of the All list I may see but not change)
        if (!l.canEdit) {
            context.toast(R.string.ittc_al_view_only)
            return
        }
        if (!st.forCourse(l.courseId)) {
            context.toast(context.getString(R.string.ittc_bd_not_for_course, st.name))
            return
        }
        val from = meta?.stages.orEmpty().firstOrNull { it.id == l.stageId }
        try {
            if (IttcStageMove.isAdmitted(st.name) || IttcStageMove.needsConfirm(st)) {
                IttcStageMoveSheet.show(
                    act, l, from?.name ?: l.stage, st, meta?.notePresets.orEmpty(),
                    onMove = { note, due -> moveNow(l, from, st, note, due, undo = false) },
                    onAdmit = { IttcAdmitActivity.open(act, l.leadId) },
                )
            } else {
                moveNow(l, from, st, null, null, undo = false)
            }
        } catch (e: Throwable) {
            IttcCrash.record(context, e, "Leads board: move")
        }
    }

    /** A stage move shown here before the server's list has it */
    private class PendingMove(val stageId: String, val stage: String, val due: Long?, val at: Long) {
        /** when it was seen gone from the outbox (sent); 0: still there / not looked yet */
        var goneAt = 0L
    }

    private val pendingMoves = HashMap<String, PendingMove>()

    /** The server's / Tracker's list with this tab's own moves on top, until the list has them ([queued]: still in the outbox) */
    private fun withPending(list: List<IttcBridge.PhoneLead>, queued: Set<String>): List<IttcBridge.PhoneLead> {
        if (pendingMoves.isEmpty()) return list
        val now = System.currentTimeMillis()
        val settled = HashSet<String>()
        // (still in the outbox: shown however long it waits; sent: until the list has it, or a while after it left)
        pendingMoves.forEach { (id, pm) ->
            if (id in queued) {
                pm.goneAt = 0L
            } else if (pm.goneAt == 0L) {
                pm.goneAt = now
            }
        }
        val out = list.map { l ->
            val pm = pendingMoves[l.leadId] ?: return@map l
            if (l.stageId == pm.stageId || (pm.goneAt != 0L && now - pm.goneAt > PENDING_SHOWN_MS)) {
                settled += l.leadId
                l
            } else {
                l.copy(stage = pm.stage, stageId = pm.stageId, nextCall = pm.due ?: l.nextCall)
            }
        }
        val entries = pendingMoves.entries.iterator()
        while (entries.hasNext()) {
            val e = entries.next()
            // (the 10 min cap: only for one that has left the outbox, e.g. a lead gone from the list)
            if (e.key in settled || (e.key !in queued && now - e.value.at > PENDING_MAX_MS)) entries.remove()
        }
        return out
    }

    /**
     * Queue the move (the lead page's way: the outbox, sent at once with internet, kept without), show
     * it at once, then "Rahim moved to Interested · Undo" ([undo]: this is the Undo — no Undo again)
     */
    private fun moveNow(l: IttcBridge.PhoneLead, from: IttcBridge.Stage?, to: IttcBridge.Stage, note: String?, due: Long?, undo: Boolean) {
        val act = activity ?: return
        val name = l.name.ifEmpty { l.callNumber.orEmpty() }
        IttcStageMove.queue(act, l.leadId, IttcStageMove.item(to.id, note, due)) { ok ->
            if (!::binding.isInitialized) return@queue
            if (!ok) {
                context.toast(R.string.ittc_lp_no_app)
                return@queue
            }
            val moved = l.copy(stage = to.name, stageId = to.id, nextCall = due ?: l.nextCall)
            pendingMoves[l.leadId] = PendingMove(to.id, to.name, due, System.currentTimeMillis())
            all = all.map { if (it.leadId == l.leadId) moved else it }
            render()
            // (Undo: back to the stage it came from, when that needs no date; a note when it needs one)
            val back = from?.takeIf {
                !undo && due == null && it.id != to.id && !it.askDate && it.forCourse(l.courseId) && !IttcStageMove.isAdmitted(it.name)
            }
            if (undo) {
                showMoved(context.getString(R.string.ittc_bd_moved_back, name, to.name), null)
            } else {
                showMoved(
                    context.getString(R.string.ittc_bd_moved, name, to.name),
                    back?.let { f ->
                        {
                            val undoNote = if (f.noteRequired) context.getString(R.string.ittc_bd_undo_note, to.name) else null
                            moveNow(moved, to, f, undoNote, null, undo = true)
                        }
                    },
                )
            }
        }
    }

    private val hideMovedNow = Runnable { IttcUi.fadeIf(binding.ittcLeadsUndo, false) }

    /** The bar over the board for a few seconds ([undo]: its Undo) */
    private fun showMoved(text: String, undo: (() -> Unit)?) {
        val b = binding
        val p = pal()
        b.ittcLeadsUndo.background = if (p.dark) IttcTheme.rounded(context, p.surfaceAlt, 14f, p.outline) else IttcTheme.rounded(context, p.text, 14f)
        b.ittcLeadsUndoText.setTextColor(if (p.dark) p.text else p.bg)
        b.ittcLeadsUndoAction.setTextColor(if (p.dark) p.call else p.ring)
        b.ittcLeadsUndoText.text = text
        b.ittcLeadsUndoAction.beVisibleIf(undo != null)
        b.ittcLeadsUndoAction.setOnClickListener {
            hideMoved()
            undo?.invoke()
        }
        IttcUi.fadeIn(b.ittcLeadsUndo)
        removeCallbacks(hideMovedNow)
        postDelayed(hideMovedNow, UNDO_MS)
    }

    private fun hideMoved() {
        removeCallbacks(hideMovedNow)
        if (binding.ittcLeadsUndo.visibility != GONE) binding.ittcLeadsUndo.visibility = GONE
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
        /** (what the rows were last drawn with besides the list: the same → nothing to draw again) */
        private var signature: List<Any?>? = null
        /** the start of today, worked out once per list (not once per row) */
        private var dayStart = 0L
        /** a stage's pill colours, worked out once per stage (until the theme changes) */
        private val stageColors = HashMap<String, Pair<Int, Int>>()

        fun colorsChanged() {
            stageColors.clear()
            signature = null
        }

        fun submit(list: List<IttcBridge.PhoneLead>) {
            // (everything a row's look depends on: the palette, whose name shows, the people's names
            // and the minute — "in 20 min" moves on with the clock)
            val ownerShown = ownerShown()
            val sig = listOf(
                pal(),
                ownerShown,
                if (ownerShown) meta?.let { it.meId to it.people } else null,
                System.currentTimeMillis() / 60_000L,
            )
            if (sig == signature && list == items) return
            signature = sig
            dayStart = startOfToday()
            items = list
            notifyDataSetChanged()
        }

        private fun stageColorsFor(stage: String): Pair<Int, Int> =
            stageColors.getOrPut(stage) { IttcTheme.stageColors(context, stage) }

        fun at(position: Int): IttcBridge.PhoneLead? = items.getOrNull(position)

        /** (each card's shapes made once; only their colours change on bind) */
        inner class Holder(val b: IttcItemLeadBinding) : RecyclerView.ViewHolder(b.root) {
            val card = GradientDrawable().apply { cornerRadius = IttcTheme.dp(context, 16f).toFloat() }
            val avatar = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val pill = GradientDrawable().apply { cornerRadius = IttcTheme.dp(context, 999f).toFloat() }
            val callBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val waBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

            init {
                b.ittcLeadHolder.background = card
                b.ittcLeadAvatar.background = avatar
                b.ittcLeadStage.background = pill
                b.ittcLeadCall.background = callBg
                b.ittcLeadWhatsapp.background = waBg
                // (the pressed ripples keep to the card's rounded corners and the round 📞)
                b.ittcLeadHolder.clipToOutline = true
                IttcUi.ripple(b.ittcLeadCall)
                IttcUi.ripple(b.ittcLeadWhatsapp)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(IttcItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val l = items[position]
            val p = pal()
            val now = System.currentTimeMillis()
            val today = dayStart
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
                    val (sBg, sFg) = stageColorsFor(stage)
                    holder.pill.setColor(sBg)
                    ittcLeadStage.setTextColor(sFg)
                    ittcLeadStage.text = stage
                }

                val owner = if (ownerShown()) ownerName(l) else null
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
                // 💬 WhatsApp beside 📞 (when there is a number to write to)
                val wa = IttcLeadSheet.whatsAppNumber(l)
                ittcLeadWhatsapp.beVisibleIf(wa != null)
                if (wa != null) {
                    val (waBack, waFore) = IttcLeadSheet.whatsAppColors(p)
                    holder.waBg.setColor(waBack)
                    ittcLeadWhatsapp.setColorFilter(waFore)
                    ittcLeadWhatsapp.contentDescription = context.getString(R.string.ittc_l3_whatsapp_lead, title)
                    ittcLeadWhatsapp.setOnClickListener { activity?.let { IttcLeadSheet.openWhatsApp(it, wa) } }
                } else {
                    ittcLeadWhatsapp.setOnClickListener(null)
                }
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
