package org.fossify.phone.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
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
 * Chips: due today / overdue / never called / hot / all, and a row of pipeline stages.
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
        private const val KEY_TODAY_COLLAPSED = "leads_today_collapsed"
        private const val KEY_BOARD = "leads_board"
        private const val UNDO_MS = 5_000L
        /** a board move is shown over the server's list until the server has it (or this long after it left the phone) */
        private const val PENDING_SHOWN_MS = 60_000L
        private const val PENDING_MAX_MS = 10 * 60_000L
        private const val TODAY_FOLD_MS = 180L
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
        // 📊 the Today card folds to one line (remembered)
        todayCollapsed = prefs.getBoolean(KEY_TODAY_COLLAPSED, false)
        binding.ittcLeadsTodayToggle.setOnClickListener { toggleToday() }
        binding.ittcLeadsTodayLine.setOnClickListener { toggleToday() }
        applyTodayCollapsed(animate = false)
        // 🎨 the system's pressed ripple over the custom pill buttons; the list fills the page
        listOf(binding.ittcLeadsAdd, binding.ittcLeadsCallAll, binding.ittcLeadsStop, binding.ittcLeadsTeam).forEach { IttcUi.ripple(it) }
        binding.ittcLeadsList.setHasFixedSize(true)
        setupSwipes()
        // ▦ 3.9: the board (remembered)
        boardOn = prefs.getBoolean(KEY_BOARD, false)
        binding.ittcLeadsViewList.setOnClickListener { setBoard(false) }
        binding.ittcLeadsViewBoard.setOnClickListener { setBoard(true) }
        listOf(binding.ittcLeadsViewList, binding.ittcLeadsViewBoard, binding.ittcLeadsUndoAction).forEach { IttcUi.ripple(it) }
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
        chipsSignature = null
        stagesSignature = null
        adapter.colorsChanged()
        boardOrNull?.colorsChanged()
        toggleStyled = null
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
                all = withPending(leads ?: emptyList(), queued)
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
            b.ittcLeadsTodayLine.setTextColor(strong)
            b.ittcLeadsTodayToggle.setColorFilter(strong)
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
        val todayTitle = context.getString(R.string.ittc_a_today) +
            if (t != null && todayOffline) " · " + context.getString(R.string.ittc_a_offline) else ""
        b.ittcLeadsTodayTitle.text = todayTitle
        // (folded: the same day in one line)
        val talk = IttcToday.duration(talkSec)
        b.ittcLeadsTodayLine.text = if (target > 0) {
            context.getString(R.string.ittc_l3_today_line_target, todayTitle, calls, target, connected, talk)
        } else {
            context.getString(R.string.ittc_l3_today_line, todayTitle, calls, connected, talk)
        }
        b.ittcLeadsStatCalls.text = calls.toString()
        b.ittcLeadsStatConnected.text = connected.toString()
        b.ittcLeadsStatTalk.text = IttcToday.duration(talkSec)
        b.ittcLeadsTodayStats.text = extra
        b.ittcLeadsTodayStats.beVisibleIf(!extra.isNullOrEmpty())
        b.ittcLeadsTeam.beVisibleIf(team)
        IttcUi.fadeIn(b.ittcLeadsToday)
    }

    /** 📊 The Today card folded to one line (the person's choice, kept) */
    private var todayCollapsed = false

    private fun toggleToday() {
        todayCollapsed = !todayCollapsed
        prefs.edit().putBoolean(KEY_TODAY_COLLAPSED, todayCollapsed).apply()
        // (the card and what is under it move smoothly; the list's rows are left alone)
        if (binding.ittcLeadsToday.visibility == VISIBLE && isAttachedToWindow) {
            try {
                TransitionManager.beginDelayedTransition(
                    this,
                    AutoTransition().setDuration(TODAY_FOLD_MS).excludeChildren(binding.ittcLeadsList, true)
                        .excludeChildren(binding.ittcLeadsBoard, true),
                )
            } catch (_: Throwable) {
            }
        }
        applyTodayCollapsed(animate = true)
    }

    /** Folded: the one line (+ ⌄); open: the ring and the numbers (+ ⌃ in the corner). Never shows the card itself */
    private fun applyTodayCollapsed(animate: Boolean) {
        val b = binding
        val folded = todayCollapsed
        b.ittcLeadsTodayFull.visibility = if (folded) GONE else VISIBLE
        b.ittcLeadsTodayLine.visibility = if (folded) VISIBLE else GONE
        val side = IttcTheme.dp(context, 18f)
        val vertical = IttcTheme.dp(context, if (folded) 8f else 18f)
        b.ittcLeadsToday.setPadding(side, vertical, side, vertical)
        val toggle = b.ittcLeadsTodayToggle
        (toggle.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = if (folded) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.TOP or Gravity.END
            lp.topMargin = if (folded) 0 else -IttcTheme.dp(context, 10f)
            lp.marginEnd = -IttcTheme.dp(context, 10f)
            toggle.layoutParams = lp
        }
        // (⌃ open → turned half way round: ⌄ folded)
        val turn = if (folded) 180f else 0f
        toggle.animate().cancel()
        if (animate) toggle.animate().rotation(turn).setDuration(TODAY_FOLD_MS).start() else toggle.rotation = turn
        toggle.contentDescription = context.getString(if (folded) R.string.ittc_l3_today_show else R.string.ittc_l3_today_hide)
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
        // ▦ 3.9: the board has a column per stage (the stage chips row and the stage choice are the list's)
        val boardMode = boardOn && stages.isNotEmpty()
        renderViewToggle(stages.isNotEmpty(), boardMode)
        val staged = if (boardMode) scoped else scoped.filter { stageId == null || it.stageId == stageId }
        renderChips(today, staged)
        if (boardMode) {
            binding.ittcLeadsStagesScroll.beVisibleIf(false)
        } else {
            renderStages(scoped.filter { query.isNotEmpty() || matches(filter, it, today) }, stages)
        }
        renderStatus()
        val q = query.lowercase(Locale.getDefault())
        val digits = query.filter { it.isDigit() }
        val shown = staged.filter { l ->
            (query.isNotEmpty() || matches(filter, l, today)) &&
                (q.isEmpty() || l.name.lowercase(Locale.getDefault()).contains(q) ||
                    (digits.length >= 3 && listOfNotNull(l.phone, l.altPhone, l.whatsapp).any { it.filter(Char::isDigit).contains(digits) }))
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
    }

    /**
     * 🎨 3.8 chips: one height (36 dp), 8 dp apart. The filter row is segmented: the chosen one filled
     * with the accent, the rest soft; "Overdue" red while there are any; a super admin's "whose" chip
     * soft blue. Stages: a dot in the stage's colour, the name and the count; chosen: the stage's colours
     * with a thin border.
     */
    private enum class ChipKind { NORMAL, DANGER, SCOPE }

    private fun baseChip(label: CharSequence, bold: Boolean, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            gravity = Gravity.CENTER
            includeFontPadding = false
            val pad = IttcTheme.dp(context, 14f)
            setPadding(pad, 0, pad, 0)
            setOnClickListener { onClick(it) }
        }

    private fun chipView(label: String, on: Boolean, kind: ChipKind = ChipKind.NORMAL, onClick: (View) -> Unit): TextView =
        baseChip(label, on || kind != ChipKind.NORMAL, onClick).apply {
            val p = pal()
            when {
                on && kind == ChipKind.DANGER -> IttcTheme.pill(this, p.danger, p.surface)
                on -> IttcTheme.pill(this, p.primary, p.onPrimary)
                kind == ChipKind.DANGER -> IttcTheme.pill(this, p.dangerSoft, p.danger, p.danger.adjustAlpha(0.3f))
                kind == ChipKind.SCOPE -> IttcTheme.pill(this, p.primarySoft, p.primary, p.primary.adjustAlpha(0.3f))
                else -> IttcTheme.pill(this, p.surfaceAlt, p.text2)
            }
            isSelected = on
            IttcUi.ripple(this)
        }

    /** A stage chip: ● name count ([colors] null: "All stages", no dot) */
    private fun stageChip(name: String, count: Int, on: Boolean, colors: Pair<Int, Int>?, onClick: (View) -> Unit): TextView {
        val p = pal()
        val (bg, fg) = when {
            on && colors != null -> colors
            on -> p.primarySoft to p.primary
            else -> p.surface to p.text2
        }
        val label = SpannableStringBuilder(name).apply {
            append("  ")
            val start = length
            append(count.toString())
            setSpan(ForegroundColorSpan(fg.adjustAlpha(0.7f)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return baseChip(label, on, onClick).apply {
            val stroke = if (on) fg.adjustAlpha(0.45f) else p.outline
            IttcTheme.pill(this, bg, fg, stroke)
            if (colors != null) {
                val size = IttcTheme.dp(context, 8f)
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors.second)
                    setSize(size, size)
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = IttcTheme.dp(context, 6f)
            }
            isSelected = on
            IttcUi.ripple(this)
        }
    }

    private fun addChip(row: ViewGroup, chip: TextView) {
        row.addView(
            chip,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, IttcTheme.dp(context, 36f)).apply {
                marginEnd = IttcTheme.dp(context, 8f)
            },
        )
    }

    /** The chosen chip scrolled into sight (after it is laid out), when the choice changed */
    private fun revealChip(scroll: HorizontalScrollView, chip: View?) {
        chip ?: return
        chip.doOnLayout {
            if (chip.parent == null || scroll.width == 0) return@doOnLayout
            val room = IttcTheme.dp(context, 24f)
            val left = chip.left - room
            val right = chip.right + room
            val x = scroll.scrollX
            when {
                left < x -> scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
                right > x + scroll.width -> scroll.smoothScrollTo(right - scroll.width, 0)
            }
        }
    }

    // (the chips as last drawn: the same labels, counts, choice and colours → not made again)
    private var chipsSignature: List<Any?>? = null
    private var stagesSignature: List<Any?>? = null
    /** the choices last scrolled into sight (another one → scrolled to) */
    private var chipsRevealed: Any? = Unit
    private var stagesRevealed: Any? = Unit

    private val chipFilters = listOf(
        Filter.TODAY to R.string.ittc_chip_today,
        Filter.OVERDUE to R.string.ittc_chip_overdue,
        Filter.NEW to R.string.ittc_chip_new,
        Filter.HOT to R.string.ittc_chip_hot,
        Filter.ALL to R.string.ittc_chip_all,
    )

    private fun renderChips(today: Long, list: List<IttcBridge.PhoneLead>) {
        val row = binding.ittcLeadsChips
        val counts = chipFilters.map { (f, _) -> list.count { matches(f, it, today) } }
        val scopeText = if (isSuper) scopeLabel() else null
        val signature = listOf(pal(), scopeText, filter, counts)
        if (signature == chipsSignature && row.childCount > 0) return
        chipsSignature = signature
        row.removeAllViews()
        // (a super admin: whose leads, one choice in one chip)
        if (scopeText != null) addChip(row, chipView(scopeText, false, ChipKind.SCOPE) { pickScope(it) })
        var chosen: View? = null
        chipFilters.forEachIndexed { i, (f, label) ->
            val n = counts[i]
            // (overdue is red while there are any)
            val kind = if (f == Filter.OVERDUE && n > 0) ChipKind.DANGER else ChipKind.NORMAL
            val chip = chipView(context.getString(label, n), f == filter, kind) {
                filter = f
                render()
            }
            if (f == filter) chosen = chip
            addChip(row, chip)
        }
        if (chipsRevealed != filter) {
            chipsRevealed = filter
            revealChip(binding.ittcLeadsChipsScroll, chosen)
        }
    }

    /** 🧭 All stages · New 42 · Interested 18 · … (the counts follow the chips above) */
    private fun renderStages(list: List<IttcBridge.PhoneLead>, stages: List<IttcBridge.Stage>) {
        val row = binding.ittcLeadsStages
        binding.ittcLeadsStagesScroll.beVisibleIf(stages.isNotEmpty())
        val counts = list.groupingBy { it.stageId }.eachCount()
        val signature = listOf(pal(), stageId, list.size, stages, counts)
        if (signature == stagesSignature) return
        stagesSignature = signature
        row.removeAllViews()
        if (stages.isEmpty()) return
        val all = stageChip(context.getString(R.string.ittc_l3_stage_all), list.size, stageId == null, null) {
            stageId = null
            saveChoice()
            render()
        }
        addChip(row, all)
        var chosen: View? = if (stageId == null) all else null
        stages.forEach { st ->
            val n = counts[st.id] ?: 0
            // (a stage with nobody in it stays out of the way, unless it is the chosen one)
            if (n == 0 && st.id != stageId) return@forEach
            val chip = stageChip(st.name, n, st.id == stageId, IttcTheme.stageColors(context, st.name)) {
                stageId = if (stageId == st.id) null else st.id
                saveChoice()
                render()
            }
            if (st.id == stageId) chosen = chip
            addChip(row, chip)
        }
        if (stagesRevealed != stageId) {
            stagesRevealed = stageId
            revealChip(binding.ittcLeadsStagesScroll, chosen)
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
            if (isSuper && scope != Scope.MY && person == null) ownerName(l) else null

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
    }

    /** What the board's cards look like besides their leads (as the list's rows: theme, owner shown, the minute) */
    private fun boardLook(): List<Any?> {
        val ownerShown = isSuper && scope != Scope.MY && person == null
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
            v.elevation = if (on) IttcTheme.dp(context, 2f).toFloat() else 0f
            v.setColorFilter(if (on) p.primary else p.muted)
            v.isSelected = on
        }
    }

    /** The long press sheet on the board: + "Move to…" (direct mode: the stages of the lead's course) */
    private fun showBoardOptions(l: IttcBridge.PhoneLead) {
        val act = activity ?: return
        try {
            val targets = if (direct) meta?.stages.orEmpty().filter { it.forCourse(l.courseId) } else emptyList()
            val onMove: ((IttcBridge.Stage) -> Unit)? = if (direct) ({ st: IttcBridge.Stage -> requestMove(l, st) }) else null
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
            val ownerShown = isSuper && scope != Scope.MY && person == null
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
