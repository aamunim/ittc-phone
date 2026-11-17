package org.fossify.phone.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcFragmentLeadsBinding
import org.fossify.phone.databinding.IttcItemLeadBinding
import org.fossify.phone.extensions.startCallIntent
import org.fossify.phone.helpers.IttcBridge
import java.util.Calendar
import java.util.Locale

/**
 * 🎓 ITTC: the "Leads" tab — open leads from the Admission Tracker: the signed-in user's own
 * (≤ 500); a super admin sees everyone's (≤ 2000) and picks whose in one chip: All / My /
 * Team Leads / Callers (all of them, or one person).
 * Chips: due today / overdue / never called / hot / all, and a row of pipeline stages.
 * Live: the Tracker announces each new list (a ContentObserver) and it shows at once.
 * 📞 calls at once; a tap on the row offers WhatsApp, SMS, the other number and the lead in the
 * Tracker. The toolbar search filters by name or number.
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
        // (the last list at once, then the Tracker's)
        all = IttcBridge.myLeadsCache
        meta = IttcBridge.metaCache
        render()
    }

    // ⚡ a new list from the Tracker shows at once while this tab is on screen
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observer == null) observer = IttcBridge.watchLeads(context) { reload() }
    }

    override fun onDetachedFromWindow() {
        IttcBridge.unwatchLeads(context, observer)
        observer = null
        removeCallbacks(reloadNow)
        super.onDetachedFromWindow()
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
        binding.ittcLeadsPlaceholder.setTextColor(textColor)
        render()
    }

    fun refreshItems() {
        // (ask the Tracker for a fresh list now and then; it answers when it runs)
        ask(force = false)
        load()
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
            val leads = IttcBridge.myLeads(ctx)
            val m = IttcBridge.phoneMeta(ctx)
            post {
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
            }
        }
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

    private fun render() {
        if (!::binding.isInitialized) return
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
        val placeholder = when {
            !loadedOnce && all.isEmpty() -> context.getString(R.string.ittc_leads_loading)
            noTracker -> context.getString(R.string.ittc_leads_no_tracker)
            shown.isEmpty() -> context.getString(R.string.ittc_leads_none)
            else -> null
        }
        binding.ittcLeadsPlaceholder.text = placeholder
        binding.ittcLeadsPlaceholder.beVisibleIf(placeholder != null)
        binding.ittcLeadsList.beVisibleIf(placeholder == null)
    }

    private fun chipView(label: String, on: Boolean, color: Int? = null, onClick: (android.view.View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getTextSize() * 0.9f)
            val pad = (resources.displayMetrics.density * 12).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundResource(R.drawable.ittc_chip_background)
            val fill = color ?: primaryColor
            background.mutate().applyColorFilter(if (on) fill else (textColor and 0x00FFFFFF) or 0x22000000)
            setTextColor(if (on) fill.getContrastColor() else textColor)
            setOnClickListener { onClick(it) }
        }

    private fun addChip(row: ViewGroup, chip: TextView) {
        row.addView(
            chip,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (resources.displayMetrics.density * 8).toInt()
            },
        )
    }

    private fun renderChips(today: Long, list: List<IttcBridge.PhoneLead>) {
        val row = binding.ittcLeadsChips
        row.removeAllViews()
        // (a super admin: whose leads, one choice in one chip)
        if (isSuper) addChip(row, chipView(scopeLabel(), true) { pickScope(it) })
        listOf(
            Filter.TODAY to R.string.ittc_chip_today,
            Filter.OVERDUE to R.string.ittc_chip_overdue,
            Filter.NEW to R.string.ittc_chip_new,
            Filter.HOT to R.string.ittc_chip_hot,
            Filter.ALL to R.string.ittc_chip_all,
        ).forEach { (f, label) ->
            val n = list.count { matches(f, it, today) }
            addChip(
                row,
                chipView(context.getString(label, n), f == filter) {
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
                chipView("${st.name} $n", st.id == stageId, st.color) {
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
        binding.ittcLeadsStatus.setTextColor(if (trackerClosed) 0xFFE53935.toInt() else textColor)
        binding.ittcLeadsStatus.beVisibleIf(text != null)
    }

    // ------------------------------------------------------------- rows
    private fun whenText(l: IttcBridge.PhoneLead): String? {
        val next = l.nextCall ?: return l.lastContacted?.let {
            "last call " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: "never called"
        val today = startOfToday()
        return when {
            next < today -> "⚠ was due " + DateUtils.getRelativeTimeSpanString(next, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
            next < today + DateUtils.DAY_IN_MILLIS -> "today " + DateUtils.formatDateTime(context, next, DateUtils.FORMAT_SHOW_TIME)
            else -> DateUtils.formatDateTime(context, next, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
        }
    }

    private fun dotColor(l: IttcBridge.PhoneLead): Int {
        val today = startOfToday()
        val next = l.nextCall
        return when {
            next != null && next < today -> 0xFFE53935.toInt()
            next != null && next < today + DateUtils.DAY_IN_MILLIS -> 0xFFFB8C00.toInt()
            l.attempts == 0 && l.lastContacted == null -> 0xFF1E88E5.toInt()
            else -> 0xFF43A047.toInt()
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
        items += context.getString(R.string.ittc_lead_open) to { IttcBridge.openLead(act, l.leadId) }
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

    private inner class LeadsAdapter : RecyclerView.Adapter<LeadsAdapter.Holder>() {
        private var items: List<IttcBridge.PhoneLead> = emptyList()

        fun submit(list: List<IttcBridge.PhoneLead>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: IttcItemLeadBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(IttcItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val l = items[position]
            holder.b.apply {
                val size = context.getTextSize()
                ittcLeadName.text = listOfNotNull(l.name.ifEmpty { l.callNumber }, if (l.rating.equals("hot", true)) "🔥" else null).joinToString(" ")
                ittcLeadName.setTextColor(textColor)
                ittcLeadName.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                val owner = if (isSuper && scope != Scope.MY && person == null) ownerName(l) else null
                ittcLeadLine.text = listOfNotNull(owner, l.course, l.batch, l.stage, whenText(l)).joinToString(" · ")
                ittcLeadLine.setTextColor(IttcBridge.stageColor(l.stage, primaryColor, textColor))
                ittcLeadLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.8f)
                ittcLeadNote.text = l.lastNote?.let { "“$it”" }
                ittcLeadNote.setTextColor(textColor)
                ittcLeadNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.8f)
                ittcLeadNote.beVisibleIf(!l.lastNote.isNullOrBlank())
                ittcLeadDot.background.mutate().applyColorFilter(dotColor(l))
                ittcLeadCall.applyColorFilter(primaryColor)
                ittcLeadCall.setOnClickListener { call(l.callNumber) }
                ittcLeadCall.beVisibleIf(l.callNumber != null)
                ittcLeadHolder.setOnClickListener { showOptions(l) }
            }
        }
    }
}
