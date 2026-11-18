package org.fossify.phone.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcCActivitySearchBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcNotify
import org.fossify.phone.direct.IttcVisitFeeApi
import org.fossify.phone.helpers.IttcTheme

/**
 * 🔎 Search every lead I may see (ITTC Phone 3.4, direct mode) — name, phone (any part, e.g. the
 * last 4 digits), other numbers, student ID, guardian, e-mail, area; closed leads too — as the
 * Tracker's search (the server's row security decides what is found). With nothing typed:
 * 🕘 Recent (what I worked on, my_recent_leads) or ⭐ Starred (lead_stars).
 * Typing waits 300 ms; an older answer that comes back late is dropped.
 */
class IttcSearchActivity : SimpleActivity() {
    private val binding by viewBinding(IttcCActivitySearchBinding::inflate)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    @Volatile private var seq = 0
    private val notifyHold = IttcNotify.ScreenHold()
    private var list = "recent"
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val p = IttcTheme.of(this)
        binding.apply {
            setupEdgeToEdge(padBottomImeAndSystem = listOf(ittcCSearchList))
            setupMaterialScrollListener(ittcCSearchList, ittcCSearchAppbar)
            ittcCSearchHolder.setBackgroundColor(p.bg)
            ittcCSearchField.background = IttcTheme.rounded(this@IttcSearchActivity, p.surface, 999f, p.outline)
            ittcCSearchField.setTextColor(p.text)
            ittcCSearchField.setHintTextColor(p.muted)
            ittcCSearchEmpty.setTextColor(p.muted)
            ittcCSearchList.layoutManager = LinearLayoutManager(this@IttcSearchActivity)
            ittcCSearchList.adapter = adapter
            ittcCSearchList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
            })
            ittcCSearchField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = schedule(300)
            })
            ittcCSearchField.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    schedule(0)
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        showChips()
        schedule(0)
        binding.ittcCSearchField.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcCSearchAppbar, NavigationIcon.Arrow)
        notifyHold.resume(this)
    }

    override fun onPause() {
        notifyHold.pause()
        super.onPause()
    }

    override fun onDestroy() {
        pending?.let { handler.removeCallbacks(it) }
        seq++
        super.onDestroy()
    }

    private fun query() = binding.ittcCSearchField.text?.toString()?.trim().orEmpty()

    private fun showChips() {
        val holder = binding.ittcCSearchChips
        holder.removeAllViews()
        val typing = query().isNotEmpty()
        holder.visibility = if (typing) View.GONE else View.VISIBLE
        if (typing) return
        listOf("recent" to R.string.ittc_c_search_recent, "starred" to R.string.ittc_c_search_starred).forEach { (key, label) ->
            holder.addView(
                IttcCUi.chip(this, getString(label), list == key) {
                    if (list == key) return@chip
                    list = key
                    showChips()
                    schedule(0)
                },
                IttcCUi.wrap(this),
            )
        }
    }

    private fun schedule(delayMs: Long) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { search() }
        pending = r
        handler.postDelayed(r, delayMs)
    }

    /** One search (or Recent / Starred); only the newest answer is shown */
    private fun search() {
        if (isFinishing || isDestroyed) return
        showChips()
        val text = query()
        val mine = ++seq
        if (!IttcDirect.isActive(this)) {
            show(mine, emptyList(), getString(R.string.ittc_c_signed_out))
            return
        }
        // ("@" alone or one character: not a search yet; Bengali vowel signs count)
        if (text.isNotEmpty() && text.count { !it.isWhitespace() && it != '@' } < 2) {
            show(mine, emptyList(), getString(R.string.ittc_c_search_type_more))
            return
        }
        binding.ittcCSearchProgress.visibility = View.VISIBLE
        val app = applicationContext
        val which = list
        ensureBackgroundThread {
            if (mine != seq) return@ensureBackgroundThread
            var error: String? = null
            val found = try {
                when {
                    text.isNotEmpty() -> IttcVisitFeeApi.search(app, text)
                    which == "starred" -> IttcVisitFeeApi.byIds(app, IttcVisitFeeApi.starredIds(app))
                    else -> IttcVisitFeeApi.byIds(app, IttcVisitFeeApi.recentIds(app))
                }
            } catch (t: Throwable) {
                error = IttcCUi.errorText(app, t)
                emptyList()
            }
            runOnUiThread {
                val empty = error ?: when {
                    found.isNotEmpty() -> null
                    text.isNotEmpty() -> getString(R.string.ittc_c_search_none, text)
                    which == "starred" -> getString(R.string.ittc_c_search_no_starred)
                    else -> getString(R.string.ittc_c_search_no_recent)
                }
                show(mine, found, empty)
            }
        }
    }

    private fun show(mine: Int, found: List<IttcVisitFeeApi.Found>, empty: String?) {
        if (mine != seq || isFinishing || isDestroyed) return
        binding.ittcCSearchProgress.visibility = View.GONE
        adapter.set(found)
        binding.ittcCSearchEmpty.text = empty.orEmpty()
        binding.ittcCSearchEmpty.visibility = if (found.isEmpty() && !empty.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ rows
    private inner class Holder(val root: LinearLayout, val avatar: TextView, val name: TextView, val line: TextView, val stage: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<IttcVisitFeeApi.Found> = emptyList()

        fun set(list: List<IttcVisitFeeApi.Found>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val p = IttcTheme.of(c)
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(IttcCUi.dp(c, 14), IttcCUi.dp(c, 12), IttcCUi.dp(c, 14), IttcCUi.dp(c, 12))
                IttcTheme.card(this, p)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = IttcCUi.dp(c, 4)
                    bottomMargin = IttcCUi.dp(c, 4)
                }
            }
            val size = IttcCUi.dp(c, 42)
            val avatar = IttcCUi.text(c, "", 15f, p.text, bold = true).apply { gravity = Gravity.CENTER }
            root.addView(avatar, LinearLayout.LayoutParams(size, size).apply { marginEnd = IttcCUi.dp(c, 12) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val name = IttcCUi.text(c, "", 15f, p.text, bold = true).apply { maxLines = 1 }
            val line = IttcCUi.text(c, "", 13f, p.muted).apply { maxLines = 1 }
            col.addView(name)
            col.addView(line)
            root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stage = IttcCUi.pill(c, "", p.surfaceAlt, p.muted).apply { maxLines = 1 }
            root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = IttcCUi.dp(c, 8) })
            return Holder(root, avatar, name, line, stage)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = items[position]
            val c = holder.root.context
            val (aBg, aFg) = IttcTheme.avatarColors(c, f.name)
            holder.avatar.text = IttcTheme.initials(f.name)
            holder.avatar.background = IttcTheme.rounded(c, aBg, 999f)
            holder.avatar.setTextColor(aFg)
            holder.name.text = f.name.ifBlank { f.phone.orEmpty() }
            holder.line.text = listOfNotNull(f.course, f.owner ?: c.getString(R.string.ittc_c_search_unassigned), f.phone).joinToString(" · ")
            if (f.stage.isNullOrBlank()) {
                holder.stage.visibility = View.GONE
            } else {
                holder.stage.visibility = View.VISIBLE
                holder.stage.text = f.stage
                val (bg, fg) = IttcTheme.stageColors(c, f.stage)
                IttcTheme.pill(holder.stage, bg, fg)
            }
            holder.root.setOnClickListener {
                hideKeyboard()
                IttcLeadActivity.open(this@IttcSearchActivity, f.id)
            }
        }
    }

    companion object {
        fun open(context: Context) {
            try {
                context.startActivity(
                    Intent(context, IttcSearchActivity::class.java).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            } catch (_: Throwable) {
            }
        }
    }
}
