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
 * 🎓 ITTC: the "Leads" tab — the signed-in user's own open leads (≤ 500), from the Admission
 * Tracker. Chips: due today / overdue / never called / hot / all. 📞 calls at once; a tap on the
 * row offers WhatsApp, SMS, the other number and the lead in the Tracker. The toolbar search
 * filters by name or number.
 */
class IttcLeadsFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<IttcLeadsFragment.LeadsInnerBinding>(context, attributeSet) {

    class LeadsInnerBinding(val binding: IttcFragmentLeadsBinding) : InnerBinding {
        override val fragmentList: MyRecyclerView? = null
        override val recentsList: MyRecyclerView? = null
    }

    private enum class Filter { TODAY, OVERDUE, NEW, HOT, ALL }

    private lateinit var binding: IttcFragmentLeadsBinding
    private var all: List<IttcBridge.PhoneLead> = emptyList()
    private var filter = Filter.TODAY
    private var query = ""
    private var loadedOnce = false
    private var lastAsk = 0L
    private var textColor = 0
    private var primaryColor = 0
    private val adapter = LeadsAdapter()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = IttcFragmentLeadsBinding.bind(this)
        innerBinding = LeadsInnerBinding(binding)
    }

    override fun setupFragment() {
        binding.ittcLeadsList.adapter = adapter
        // (the last list at once, then the Tracker's)
        all = IttcBridge.myLeadsCache
        render()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        this.primaryColor = properPrimaryColor
        binding.ittcLeadsPlaceholder.setTextColor(textColor)
        render()
    }

    fun refreshItems() {
        val ctx = context ?: return
        // (ask the Tracker for a fresh list now and then; it answers when it runs)
        if (System.currentTimeMillis() - lastAsk > 5 * 60 * 1000) {
            lastAsk = System.currentTimeMillis()
            IttcBridge.askRefresh(ctx)
        }
        ensureBackgroundThread {
            val leads = IttcBridge.myLeads(ctx)
            post {
                loadedOnce = true
                all = leads ?: emptyList()
                noTracker = leads == null
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

    private fun render() {
        if (!::binding.isInitialized) return
        val today = startOfToday()
        renderChips(today)
        val q = query.lowercase(Locale.getDefault())
        val digits = query.filter { it.isDigit() }
        val shown = all.filter { l ->
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

    private fun renderChips(today: Long) {
        val row = binding.ittcLeadsChips
        row.removeAllViews()
        listOf(
            Filter.TODAY to R.string.ittc_chip_today,
            Filter.OVERDUE to R.string.ittc_chip_overdue,
            Filter.NEW to R.string.ittc_chip_new,
            Filter.HOT to R.string.ittc_chip_hot,
            Filter.ALL to R.string.ittc_chip_all,
        ).forEach { (f, label) ->
            val n = all.count { matches(f, it, today) }
            val chip = TextView(context).apply {
                text = context.getString(label, n)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getTextSize() * 0.9f)
                val pad = (resources.displayMetrics.density * 12).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                setBackgroundResource(R.drawable.ittc_chip_background)
                val on = f == filter
                background.mutate().applyColorFilter(if (on) primaryColor else (textColor and 0x00FFFFFF) or 0x22000000)
                setTextColor(if (on) 0xFFFFFFFF.toInt() else textColor)
                setOnClickListener {
                    filter = f
                    render()
                }
            }
            row.addView(
                chip,
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (resources.displayMetrics.density * 8).toInt()
                },
            )
        }
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
                ittcLeadLine.text = listOfNotNull(l.course, l.batch, l.stage, whenText(l)).joinToString(" · ")
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
