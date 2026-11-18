package org.fossify.phone.fragments

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.phone.R
import org.fossify.phone.activities.IttcLeadSheet
import org.fossify.phone.databinding.IttcFragmentLeadsBinding
import org.fossify.phone.databinding.IttcItemBoardCardBinding
import org.fossify.phone.databinding.IttcItemBoardColumnBinding
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 🎓 ITTC 3.9: the Leads tab's pipeline board — a column per stage side by side (a horizontal list
 * that stops at each column; the next one peeks in), a row of stage chips above it (● New 42: a tap
 * goes to the column; the column in sight is marked), and in each column its lead cards.
 *
 * Built from the list the tab already has (no extra reading): [submit] gets the leads after the
 * tab's filters (whose / Today… / search) and only the columns that changed are drawn again (each
 * column's cards are diffed); all columns share one pool of card views.
 *
 * 🖐 Moving a lead (direct mode, when the host says the stage may be changed): long press a card
 * and, still holding, move the finger — the card is lifted (View.startDragAndDrop, a slightly bigger,
 * tilted copy) once it has moved past the touch slop. Let go without moving: the lead's sheet (with
 * "Move to…", also the way for TalkBack users). While dragging: a hint on top, the column under the
 * finger outlined in its stage colour with a "Drop here" slot, the stage chips take a drop too, the
 * board scrolls by itself near its left / right edge and "Drop here to cancel" waits at the bottom.
 * All drops go through one listener on the board's root, which finds the target itself (columns
 * scrolled in during the drag work too).
 */
internal class IttcLeadsBoard(private val context: Context, private val b: IttcFragmentLeadsBinding, private val host: Host) {
    interface Host {
        fun palette(): IttcTheme.Palette
        /** Leads may be moved here (signed in to ITTC directly) */
        fun canMove(): Boolean
        /** The line under the name when the owner is shown (a super admin looking at others'), else null */
        fun owner(l: IttcBridge.PhoneLead): String?
        fun whenText(l: IttcBridge.PhoneLead, now: Long, today: Long): String
        fun span(ms: Long): String
        fun initials(name: String?): String
        fun open(l: IttcBridge.PhoneLead)
        fun longPress(l: IttcBridge.PhoneLead)
        fun call(l: IttcBridge.PhoneLead)
        fun whatsApp(number: String)
        /** A card was dropped on another stage's column / chip */
        fun drop(l: IttcBridge.PhoneLead, stage: IttcBridge.Stage)
    }

    /** One column: a stage (null: the leads in no known stage, "Other") and its leads in order */
    data class Column(
        val id: String,
        val stage: IttcBridge.Stage?,
        val name: String,
        val leads: List<IttcBridge.PhoneLead>,
        /** due today or overdue / called today */
        val due: Int,
        val done: Int,
    )

    private val res = context.resources
    private fun dp(v: Float) = IttcTheme.dp(context, v)

    private var columns: List<Column> = emptyList()
    /** what every card's look depends on besides its lead (theme, owner shown, the minute) */
    private var look: Any? = null
    /** bumped when [look] changes: a column's cards are drawn again once it binds with the new one */
    private var lookGen = 0
    private var dayStart = 0L
    private val stageColors = HashMap<String, Pair<Int, Int>>()
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 48) }
    /** each stage's scroll place, kept while its column view is used for another one */
    private val scrollStates = HashMap<String, Parcelable?>()
    private val columnsAdapter = ColumnsAdapter()
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val snap = StartSnapHelper()
    private var current = -1
    private val chipViews = ArrayList<TextView>()
    private var chipsSignature: Any? = null

    // drag state
    private class DragState(val lead: IttcBridge.PhoneLead, val fromId: String)
    private var dragging: DragState? = null
    private var dragSource: View? = null
    /** the stage under the finger (a valid one), or [CANCEL] */
    private var target: String? = null
    private var lastX = 0f
    private var lastY = 0f
    private var scrollDir = 0
    private val tmp = Rect()

    fun colorsChanged() {
        stageColors.clear()
        chipsSignature = null
        look = null
    }

    fun setup() {
        val list = b.ittcLeadsBoardList
        list.layoutManager = layoutManager
        list.adapter = columnsAdapter
        list.setHasFixedSize(true)
        // (a column's change is drawn in place: no cross-fade of the whole column)
        list.itemAnimator = null
        snap.attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0) updateCurrent()
            }
        })
        b.ittcLeadsBoard.setOnDragListener { _, e -> onDrag(e) }
    }

    private fun colorsOf(name: String): Pair<Int, Int> = stageColors.getOrPut(name) { IttcTheme.stageColors(context, name) }

    /** The width of a column: the screen less 72 dp (the next one peeks in), at most 340 dp */
    private fun columnWidth(): Int = min(res.displayMetrics.widthPixels - dp(72f), dp(340f)).coerceAtLeast(dp(200f))

    // ------------------------------------------------------------------ data
    /**
     * The board for [leads] (already filtered and in order) in [stages]' columns; [lookKey]: what the
     * cards' look depends on. Only changed columns are drawn again.
     */
    fun submit(leads: List<IttcBridge.PhoneLead>, stages: List<IttcBridge.Stage>, today: Long, lookKey: Any) {
        dayStart = today
        val tomorrow = today + android.text.format.DateUtils.DAY_IN_MILLIS
        val byId = HashMap<String, ArrayList<IttcBridge.PhoneLead>>(stages.size * 2)
        stages.forEach { byId[it.id] = ArrayList() }
        // (an older Tracker without stage ids: by the stage's name)
        val byName = stages.associateBy { it.name.lowercase() }
        val other = ArrayList<IttcBridge.PhoneLead>()
        leads.forEach { l ->
            val bucket = l.stageId?.let { byId[it] } ?: l.stage?.lowercase()?.let { n -> byName[n]?.let { byId[it.id] } }
            if (bucket != null) bucket.add(l) else other.add(l)
        }
        fun column(id: String, stage: IttcBridge.Stage?, name: String, list: List<IttcBridge.PhoneLead>): Column {
            var due = 0
            var done = 0
            list.forEach { l ->
                if (l.nextCall != null && l.nextCall < tomorrow) due++
                if (l.lastContacted != null && l.lastContacted >= today) done++
            }
            return Column(id, stage, name, list, due, done)
        }
        val cols = ArrayList<Column>(stages.size + 1)
        stages.forEach { st -> cols += column(st.id, st, st.name, byId[st.id].orEmpty()) }
        if (other.isNotEmpty()) cols += column(OTHER, null, context.getString(R.string.ittc_bd_other), other)

        val old = columns
        columns = cols
        if (lookKey != look) {
            look = lookKey
            lookGen++
            columnsAdapter.notifyDataSetChanged()
        } else if (old.size != cols.size || old.indices.any { old[it].id != cols[it].id }) {
            columnsAdapter.notifyDataSetChanged()
        } else {
            cols.indices.forEach { i -> if (cols[i] != old[i]) columnsAdapter.notifyItemChanged(i, PAYLOAD_DATA) }
        }
        renderChips()
        // (the first time: which column is in sight, once they are laid out)
        if (current < 0) b.ittcLeadsBoardList.post { updateCurrent() }
    }

    // ------------------------------------------------------------------ chips
    private fun renderChips() {
        val row = b.ittcLeadsBoardChips
        val sig = listOf(host.palette(), columns.map { Triple(it.id, it.name, it.leads.size) })
        if (sig == chipsSignature && row.childCount == columns.size) {
            styleChips()
            return
        }
        chipsSignature = sig
        row.removeAllViews()
        chipViews.clear()
        columns.forEachIndexed { i, col ->
            val chip = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                gravity = Gravity.CENTER
                includeFontPadding = false
                val pad = dp(12f)
                setPadding(pad, 0, pad, 0)
                tag = col.id
                val size = dp(8f)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (col.stage != null) colorsOf(col.name).second else host.palette().muted)
                        setSize(size, size)
                    },
                    null, null, null,
                )
                compoundDrawablePadding = dp(6f)
                contentDescription = context.getString(R.string.ittc_bd_column_desc, col.name, col.leads.size)
                setOnClickListener { scrollTo(i) }
            }
            chipViews += chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)).apply { marginEnd = dp(8f) },
            )
        }
        styleChips(force = true)
    }

    /** (the look each chip has now: current, drop target, plain — restyled only when it changes) */
    private val chipLooks = HashMap<TextView, Int>()

    private fun styleChips(force: Boolean = false) {
        val p = host.palette()
        chipViews.forEachIndexed { i, chip ->
            val col = columns.getOrNull(i) ?: return@forEachIndexed
            val state = when {
                target == col.id -> 2
                i == current -> 1
                else -> 0
            }
            if (!force && chipLooks[chip] == state) return@forEachIndexed
            chipLooks[chip] = state
            val (sBg, sFg) = if (col.stage != null) colorsOf(col.name) else p.surfaceAlt to p.text2
            val label = SpannableStringBuilder(col.name).apply {
                append("  ")
                val start = length
                append(col.leads.size.toString())
                val fg = if (state == 0) p.text2 else sFg
                setSpan(ForegroundColorSpan(fg.adjustAlpha(0.7f)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.text = label
            chip.typeface = if (state == 0) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            when (state) {
                2 -> {
                    IttcTheme.pill(chip, sBg, sFg)
                    (chip.background as? GradientDrawable)?.setStroke(dp(2f), sFg)
                }
                1 -> IttcTheme.pill(chip, sBg, sFg, sFg.adjustAlpha(0.45f))
                else -> IttcTheme.pill(chip, p.surface, p.text2, p.outline)
            }
            chip.isSelected = state == 1
            IttcUi.ripple(chip)
        }
        if (force) chipLooks.keys.retainAll(chipViews.toSet())
    }

    // ------------------------------------------------------------------ scrolling
    private fun scrollTo(position: Int) {
        if (position !in columns.indices) return
        val scroller = object : LinearSmoothScroller(context) {
            override fun getHorizontalSnapPreference() = SNAP_TO_START
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    /** The column whose start is nearest the board's start: marked in the chips (and scrolled into sight) */
    private fun updateCurrent() {
        val list = b.ittcLeadsBoardList
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(v)
            if (pos == RecyclerView.NO_POSITION) continue
            val d = abs(layoutManager.getDecoratedLeft(v) - list.paddingLeft)
            if (d < bestDist) {
                bestDist = d
                best = pos
            }
        }
        // (at the very end: the last column, even when it cannot reach the start)
        if (!list.canScrollHorizontally(1) && list.childCount > 0 && columns.isNotEmpty() && list.canScrollHorizontally(-1)) best = columns.lastIndex
        if (best == current || best < 0) return
        current = best
        styleChips()
        chipViews.getOrNull(best)?.let { reveal(it) }
    }

    private fun reveal(chip: View) {
        val scroll = b.ittcLeadsBoardChipsScroll
        chip.doOnLayout {
            if (chip.parent == null || scroll.width == 0) return@doOnLayout
            val room = dp(24f)
            val left = chip.left - room
            val right = chip.right + room
            val x = scroll.scrollX
            when {
                left < x -> scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
                right > x + scroll.width -> scroll.smoothScrollTo(right - scroll.width, 0)
            }
        }
    }

    /** Stops each column at the board's start (one column a fling) */
    private inner class StartSnapHelper : SnapHelper() {
        override fun calculateDistanceToFinalSnap(lm: RecyclerView.LayoutManager, targetView: View): IntArray =
            intArrayOf(if (lm.canScrollHorizontally()) lm.getDecoratedLeft(targetView) - lm.paddingLeft else 0, 0)

        override fun findSnapView(lm: RecyclerView.LayoutManager): View? {
            val list = b.ittcLeadsBoardList
            if (lm.childCount == 0) return null
            // (the end reached: stay, the last column is in full sight)
            if (!list.canScrollHorizontally(1)) return null
            var best: View? = null
            var bestDist = Int.MAX_VALUE
            for (i in 0 until lm.childCount) {
                val v = lm.getChildAt(i) ?: continue
                val d = abs(lm.getDecoratedLeft(v) - lm.paddingLeft)
                if (d < bestDist) {
                    bestDist = d
                    best = v
                }
            }
            return best
        }

        override fun findTargetSnapPosition(lm: RecyclerView.LayoutManager, velocityX: Int, velocityY: Int): Int {
            val count = lm.itemCount
            if (count == 0 || lm.childCount == 0) return RecyclerView.NO_POSITION
            // (the column over the start edge: forward → the next one, back → this one)
            var anchor = RecyclerView.NO_POSITION
            var anchorLeft = Int.MIN_VALUE
            for (i in 0 until lm.childCount) {
                val v = lm.getChildAt(i) ?: continue
                val left = lm.getDecoratedLeft(v)
                if (left <= lm.paddingLeft && left > anchorLeft) {
                    anchorLeft = left
                    anchor = lm.getPosition(v)
                }
            }
            if (anchor == RecyclerView.NO_POSITION) {
                lm.getChildAt(0)?.let { anchor = lm.getPosition(it) } ?: return RecyclerView.NO_POSITION
                return anchor.coerceIn(0, count - 1)
            }
            return (if (velocityX > 0) anchor + 1 else anchor).coerceIn(0, count - 1)
        }
    }

    private fun snapNow() {
        val view = snap.findSnapView(layoutManager) ?: return
        val d = snap.calculateDistanceToFinalSnap(layoutManager, view) ?: return
        if (d[0] != 0) b.ittcLeadsBoardList.smoothScrollBy(d[0], 0)
    }

    // ------------------------------------------------------------------ columns
    private inner class ColumnsAdapter : RecyclerView.Adapter<ColumnHolder>() {
        override fun getItemCount() = columns.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColumnHolder {
            val vb = IttcItemBoardColumnBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            vb.root.layoutParams = RecyclerView.LayoutParams(columnWidth(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12f) }
            return ColumnHolder(vb)
        }

        override fun onBindViewHolder(holder: ColumnHolder, position: Int) = holder.bind(columns[position], full = true)

        override fun onBindViewHolder(holder: ColumnHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else holder.bind(columns[position], full = false)
        }

        override fun onViewRecycled(holder: ColumnHolder) {
            holder.saveScroll()
        }
    }

    private inner class ColumnHolder(val vb: IttcItemBoardColumnBinding) : RecyclerView.ViewHolder(vb.root) {
        val bg = GradientDrawable().apply { cornerRadius = dp(18f).toFloat() }
        val countBg = GradientDrawable().apply { cornerRadius = dp(999f).toFloat() }
        val trackBg = GradientDrawable().apply { cornerRadius = dp(999f).toFloat() }
        val fillBg = GradientDrawable().apply { cornerRadius = dp(999f).toFloat() }
        val dropBg = GradientDrawable().apply { cornerRadius = dp(14f).toFloat() }
        val cards = CardsAdapter()
        var stageId: String? = null
        var gen = -1
        var lit: Boolean? = null

        init {
            vb.root.background = bg
            vb.root.clipToOutline = true
            vb.ittcBoardColCount.background = countBg
            vb.ittcBoardColTrack.background = trackBg
            vb.ittcBoardColFill.background = fillBg
            vb.ittcBoardColDrop.background = dropBg
            vb.ittcBoardColList.apply {
                this.layoutManager = LinearLayoutManager(this.context)
                setRecycledViewPool(pool)
                setHasFixedSize(true)
                this.adapter = cards
            }
        }

        fun saveScroll() {
            stageId?.let { scrollStates[it] = vb.ittcBoardColList.layoutManager?.onSaveInstanceState() }
        }

        fun bind(col: Column, full: Boolean) {
            val p = host.palette()
            val (sBg, sFg) = if (col.stage != null) colorsOf(col.name) else p.surfaceAlt to p.muted
            if (full || gen != lookGen) {
                // (light: white cards on a soft column; dark: the lighter cards on the darker column)
                bg.setColor(if (p.dark) p.surface else p.surfaceAlt)
                vb.ittcBoardColAccent.setBackgroundColor(sFg)
                vb.ittcBoardColName.setTextColor(p.text)
                countBg.setColor(sBg)
                vb.ittcBoardColCount.setTextColor(sFg)
                vb.ittcBoardColDue.setTextColor(p.muted)
                trackBg.setColor(p.outline.adjustAlpha(0.6f))
                fillBg.setColor(p.call)
                vb.ittcBoardColEmpty.setTextColor(p.muted)
                vb.ittcBoardColDrop.setTextColor(sFg)
                dropBg.setColor(sBg.adjustAlpha(0.6f))
                dropBg.setStroke(dp(2f), sFg, dp(6f).toFloat(), dp(4f).toFloat())
                lit = null
            }
            vb.ittcBoardColName.text = col.name
            vb.ittcBoardColCount.text = col.leads.size.toString()
            vb.root.contentDescription = null
            vb.ittcBoardColName.contentDescription = context.getString(R.string.ittc_bd_column_desc, col.name, col.leads.size)
            vb.ittcBoardColCount.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // "5 to call today · 2 done" over a bar: done of (done + to call)
            vb.ittcBoardColDue.text = when {
                col.due == 0 && col.done == 0 -> context.getString(R.string.ittc_bd_none_due)
                col.done == 0 -> context.getString(R.string.ittc_bd_to_call, col.due)
                else -> context.getString(R.string.ittc_bd_to_call_done, col.due, col.done)
            }
            val total = col.due + col.done
            vb.ittcBoardColTrack.weightSum = total.coerceAtLeast(1).toFloat()
            (vb.ittcBoardColFill.layoutParams as LinearLayout.LayoutParams).let { lp ->
                val w = col.done.toFloat()
                if (lp.weight != w) {
                    lp.weight = w
                    vb.ittcBoardColFill.layoutParams = lp
                }
            }

            // the cards: another stage in this view → its own list and scroll place at once
            val same = stageId == col.id
            if (!same) {
                saveScroll()
                stageId = col.id
                cards.submitList(null)
                cards.submitList(col.leads)
                val state = scrollStates[col.id]
                if (state != null) vb.ittcBoardColList.layoutManager?.onRestoreInstanceState(state) else vb.ittcBoardColList.scrollToPosition(0)
            } else {
                cards.submitList(col.leads)
            }
            if (gen != lookGen) {
                gen = lookGen
                if (same && cards.itemCount > 0) cards.notifyItemRangeChanged(0, cards.itemCount)
            }
            vb.ittcBoardColEmpty.beVisibleIf(col.leads.isEmpty())
            highlight(col)
        }

        /** Outlined in the stage's colour with a "Drop here" slot while a card can be dropped here */
        fun highlight(col: Column) {
            val on = target == col.id
            if (lit == on) return
            lit = on
            val p = host.palette()
            val sFg = if (col.stage != null) colorsOf(col.name).second else p.muted
            if (on) bg.setStroke(dp(2f), sFg, dp(8f).toFloat(), dp(5f).toFloat()) else bg.setStroke(0, 0)
            vb.ittcBoardColDrop.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ cards
    private object Diff : DiffUtil.ItemCallback<IttcBridge.PhoneLead>() {
        override fun areItemsTheSame(oldItem: IttcBridge.PhoneLead, newItem: IttcBridge.PhoneLead) = oldItem.leadId == newItem.leadId
        override fun areContentsTheSame(oldItem: IttcBridge.PhoneLead, newItem: IttcBridge.PhoneLead) = oldItem == newItem
    }

    private inner class CardsAdapter : ListAdapter<IttcBridge.PhoneLead, CardHolder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(IttcItemBoardCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(getItem(position))
    }

    private inner class CardHolder(val vb: IttcItemBoardCardBinding) : RecyclerView.ViewHolder(vb.root) {
        val card = GradientDrawable().apply { cornerRadius = dp(14f).toFloat() }
        val avatar = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val callBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val waBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        var lead: IttcBridge.PhoneLead? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var touching = false
        private var armed = false
        private var armX = 0f
        private var armY = 0f
        private var rawX = 0f
        private var rawY = 0f

        init {
            vb.ittcBoardCard.background = card
            vb.ittcBoardCardAvatar.background = avatar
            // (36 dp circles inside the 44 dp touch areas)
            val inset = dp(4f)
            vb.ittcBoardCardCall.background = InsetDrawable(callBg, inset)
            vb.ittcBoardCardWhatsapp.background = InsetDrawable(waBg, inset)
            vb.ittcBoardCard.clipToOutline = true
            vb.ittcBoardCard.isClickable = true
            vb.ittcBoardCard.isLongClickable = true
            IttcUi.ripple(vb.ittcBoardCard)
            IttcUi.ripple(vb.ittcBoardCardCall)
            IttcUi.ripple(vb.ittcBoardCardWhatsapp)
            vb.ittcBoardCardCall.setOnClickListener { lead?.let(host::call) }
            vb.ittcBoardCardWhatsapp.setOnClickListener { lead?.let { IttcLeadSheet.whatsAppNumber(it) }?.let(host::whatsApp) }
            vb.ittcBoardCard.setOnClickListener { lead?.let(host::open) }
            // (long press from a finger that is still down and may drag: wait for a move or the lift;
            // any other long press — TalkBack, a key — opens the sheet at once)
            vb.ittcBoardCard.setOnLongClickListener {
                val l = lead ?: return@setOnLongClickListener false
                if (touching && host.canMove() && dragging == null && columnOf(l)?.stage != null) {
                    armed = true
                    armX = rawX
                    armY = rawY
                    // (from now on the lists and the tabs leave this finger alone)
                    it.parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    host.longPress(l)
                }
                true
            }
            vb.ittcBoardCard.setOnTouchListener { v, e ->
                rawX = e.rawX
                rawY = e.rawY
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touching = true
                        armed = false
                    }
                    MotionEvent.ACTION_MOVE -> if (armed && hypot(e.rawX - armX, e.rawY - armY) > slop) {
                        armed = false
                        touching = false
                        v.isPressed = false
                        lead?.let { startDrag(v, it) }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        touching = false
                        if (armed) {
                            // (held and let go without moving: the sheet, as a long press)
                            armed = false
                            lead?.let(host::longPress)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        armed = false
                    }
                }
                false
            }
        }

        fun bind(l: IttcBridge.PhoneLead) {
            lead = l
            val p = host.palette()
            val now = System.currentTimeMillis()
            card.setColor(if (p.dark) p.surfaceAlt else p.surface)
            val title = l.name.ifEmpty { l.callNumber.orEmpty() }
            val (avBg, avFg) = IttcTheme.avatarColors(context, title)
            avatar.setColor(avBg)
            vb.ittcBoardCardAvatar.setTextColor(avFg)
            vb.ittcBoardCardAvatar.text = host.initials(l.name)
            vb.ittcBoardCardName.text = if (l.rating.equals("hot", true)) "$title 🔥" else title
            vb.ittcBoardCardName.setTextColor(p.text)

            val course = listOfNotNull(host.owner(l), l.course, l.batch).joinToString(" · ")
            vb.ittcBoardCardCourse.text = course
            vb.ittcBoardCardCourse.setTextColor(p.muted)
            vb.ittcBoardCardCourse.beVisibleIf(course.isNotEmpty())
            // (the number on the card, as asked; not repeated when the name is the number)
            val number = l.callNumber?.takeIf { l.name.isNotEmpty() }
            vb.ittcBoardCardPhone.text = number
            vb.ittcBoardCardPhone.setTextColor(p.muted)
            vb.ittcBoardCardPhone.beVisibleIf(number != null)

            // ⏰ overdue red ("Call was due 2 h ago"), today teal, later / none muted
            val next = l.nextCall
            val tomorrow = dayStart + android.text.format.DateUtils.DAY_IN_MILLIS
            val (whenText, color) = when {
                next != null && next < now -> context.getString(R.string.ittc_bd_call_was_due, host.span(now - next)) to p.danger
                next != null && next < tomorrow -> host.whenText(l, now, dayStart) to p.call
                else -> host.whenText(l, now, dayStart) to p.muted
            }
            vb.ittcBoardCardWhen.text = whenText
            vb.ittcBoardCardWhen.setTextColor(color)
            vb.ittcBoardCardWhen.setTypeface(null, if (color == p.danger) Typeface.BOLD else Typeface.NORMAL)
            vb.ittcBoardCardClock.setColorFilter(color)

            vb.ittcBoardCardNote.text = l.lastNote?.let { "“$it”" }
            vb.ittcBoardCardNote.setTextColor(p.text2)
            vb.ittcBoardCardNote.beVisibleIf(!l.lastNote.isNullOrBlank())

            callBg.setColor(p.callSoft)
            vb.ittcBoardCardCall.setColorFilter(p.call)
            vb.ittcBoardCardCall.beVisibleIf(l.callNumber != null)
            val wa = IttcLeadSheet.whatsAppNumber(l)
            vb.ittcBoardCardWhatsapp.beVisibleIf(wa != null)
            if (wa != null) {
                val (waBack, waFore) = IttcLeadSheet.whatsAppColors(p)
                waBg.setColor(waBack)
                vb.ittcBoardCardWhatsapp.setColorFilter(waFore)
                vb.ittcBoardCardWhatsapp.contentDescription = context.getString(R.string.ittc_l3_whatsapp_lead, title)
            }
            vb.ittcBoardCardCall.contentDescription = context.getString(R.string.call_number) + " " + title
            vb.ittcBoardCard.alpha = if (dragging?.lead?.leadId == l.leadId) DRAGGED_ALPHA else 1f
        }
    }

    private fun columnOf(l: IttcBridge.PhoneLead): Column? = columns.firstOrNull { c -> c.leads.any { it.leadId == l.leadId } }

    // ------------------------------------------------------------------ 🖐 drag & drop
    /** The card lifted: a little bigger and turned a little */
    private class TiltShadow(v: View) : View.DragShadowBuilder(v) {
        private var w = 0
        private var h = 0

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            val v = view
            if (v == null || v.width <= 0 || v.height <= 0) {
                super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint)
                return
            }
            // (room for the turned corners)
            w = (v.width * (SCALE + 0.06f)).toInt()
            h = (v.height * SCALE + v.width * 0.06f).toInt()
            outShadowSize.set(w, h)
            outShadowTouchPoint.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val v = view ?: return
            if (w == 0) {
                super.onDrawShadow(canvas)
                return
            }
            canvas.save()
            canvas.translate(w / 2f, h / 2f)
            canvas.rotate(TILT)
            canvas.scale(SCALE, SCALE)
            canvas.translate(-v.width / 2f, -v.height / 2f)
            v.draw(canvas)
            canvas.restore()
        }
    }

    private fun startDrag(v: View, l: IttcBridge.PhoneLead) {
        val from = columnOf(l) ?: return
        if (from.stage == null || dragging != null) return
        val state = DragState(l, from.id)
        val ok = try {
            v.startDragAndDrop(ClipData.newPlainText("lead", l.leadId), TiltShadow(v), state, 0)
        } catch (_: Throwable) {
            false
        }
        if (!ok) return
        dragging = state
        dragSource = v
        v.alpha = DRAGGED_ALPHA
        target = null
        showDragUi(true)
    }

    private fun showDragUi(on: Boolean) {
        val p = host.palette()
        if (on) {
            b.ittcLeadsUndo.visibility = View.GONE
            b.ittcLeadsBoardHint.background = IttcTheme.rounded(context, p.primary, 12f)
            b.ittcLeadsBoardHint.setTextColor(p.onPrimary)
            styleCancel(false)
        }
        b.ittcLeadsBoardHint.visibility = if (on) View.VISIBLE else View.GONE
        b.ittcLeadsBoardCancel.visibility = if (on) View.VISIBLE else View.GONE
    }

    private var cancelLit: Boolean? = null

    private fun styleCancel(lit: Boolean) {
        if (cancelLit == lit && b.ittcLeadsBoardCancel.background != null) return
        cancelLit = lit
        val p = host.palette()
        val v = b.ittcLeadsBoardCancel
        if (lit) {
            v.background = IttcTheme.rounded(context, p.danger, 16f)
            v.setTextColor(p.surface)
        } else {
            v.background = IttcTheme.rounded(context, p.surface, 16f, p.danger.adjustAlpha(0.5f), 2f).apply {
                setStroke(dp(2f), p.danger.adjustAlpha(0.6f), dp(8f).toFloat(), dp(5f).toFloat())
            }
            v.setTextColor(p.danger)
        }
    }

    /** [v]'s place in the board root's coordinates (scrolling parents counted) */
    private fun rectOf(v: View): Rect? {
        if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) return null
        tmp.set(0, 0, v.width, v.height)
        return try {
            b.ittcLeadsBoard.offsetDescendantRectToMyCoords(v, tmp)
            Rect(tmp)
        } catch (_: Throwable) {
            null
        }
    }

    /** The stage (column / chip) under ([x], [y]) in the board root; [CANCEL]; null: nothing */
    private fun targetAt(x: Float, y: Float): String? {
        val xi = x.toInt()
        val yi = y.toInt()
        rectOf(b.ittcLeadsBoardCancel)?.let { if (it.contains(xi, yi)) return CANCEL }
        val chipsRect = rectOf(b.ittcLeadsBoardChipsScroll)
        if (chipsRect != null && chipsRect.contains(xi, yi)) {
            chipViews.forEach { chip -> rectOf(chip)?.let { if (it.contains(xi, yi)) return chip.tag as? String } }
            return null
        }
        val list = b.ittcLeadsBoardList
        val listRect = rectOf(list) ?: return null
        if (!listRect.contains(xi, yi)) return null
        val child = list.findChildViewUnder(x - listRect.left, y - listRect.top) ?: return null
        val pos = list.getChildAdapterPosition(child)
        return columns.getOrNull(pos)?.id
    }

    /** A drop target only when it is another stage of the lead's course */
    private fun valid(id: String?): String? {
        val d = dragging ?: return null
        if (id == null || id == CANCEL) return id
        if (id == d.fromId || id == OTHER) return null
        val st = columns.firstOrNull { it.id == id }?.stage ?: return null
        return if (st.forCourse(d.lead.courseId)) id else null
    }

    private fun setTarget(id: String?) {
        if (id == target) return
        val old = target
        target = id
        listOf(old, id).forEach { t ->
            if (t != null && t != CANCEL) {
                val pos = columns.indexOfFirst { it.id == t }
                if (pos >= 0) columnsAdapter.notifyItemChanged(pos, PAYLOAD_HIGHLIGHT)
            }
        }
        styleCancel(id == CANCEL)
        styleChips()
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragging == null || scrollDir == 0) return
            val list = b.ittcLeadsBoardList
            if (list.canScrollHorizontally(scrollDir)) {
                list.scrollBy(scrollDir * dp(10f), 0)
                setTarget(valid(targetAt(lastX, lastY)))
            }
            list.postOnAnimation(this)
        }
    }

    private fun updateAutoScroll(x: Float, y: Float) {
        val frame = rectOf(b.ittcLeadsBoardFrame)
        val edge = dp(48f)
        val dir = when {
            frame == null || !frame.contains(x.toInt(), y.toInt()) -> 0
            x < frame.left + edge -> -1
            x > frame.right - edge -> 1
            else -> 0
        }
        if (dir == scrollDir) return
        scrollDir = dir
        val list = b.ittcLeadsBoardList
        list.removeCallbacks(autoScroll)
        if (dir != 0) list.postOnAnimation(autoScroll)
    }

    private fun onDrag(e: DragEvent): Boolean {
        val d = e.localState as? DragState ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> {
                lastX = e.x
                lastY = e.y
                setTarget(valid(targetAt(e.x, e.y)))
                updateAutoScroll(e.x, e.y)
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                setTarget(null)
                updateAutoScroll(-1f, -1f)
            }
            DragEvent.ACTION_DROP -> {
                val t = valid(targetAt(e.x, e.y))
                val stage = if (t != null && t != CANCEL) columns.firstOrNull { it.id == t }?.stage else null
                endDrag()
                if (stage != null) host.drop(d.lead, stage)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** Back to rest (once per drag; the dropped card's move is the host's) */
    private fun endDrag() {
        if (dragging == null) return
        dragging = null
        scrollDir = 0
        b.ittcLeadsBoardList.removeCallbacks(autoScroll)
        dragSource?.alpha = 1f
        dragSource = null
        setTarget(null)
        showDragUi(false)
        snapNow()
    }

    /** The tab is going away / the board is hidden: no drag left half done */
    fun cancelDrag() {
        endDrag()
    }

    companion object {
        private const val OTHER = "\u0000other"
        private const val CANCEL = "\u0000cancel"
        private const val PAYLOAD_DATA = "data"
        private const val PAYLOAD_HIGHLIGHT = "highlight"
        private const val DRAGGED_ALPHA = 0.35f
        private const val SCALE = 1.04f
        private const val TILT = 2.5f
    }
}
