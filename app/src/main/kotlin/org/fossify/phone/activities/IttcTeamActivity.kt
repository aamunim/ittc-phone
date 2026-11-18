package org.fossify.phone.activities

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityTeamBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcToday
import org.json.JSONObject

/**
 * 👥 ITTC: the team's day (a team lead's callers; a super admin: every caller and team lead), from
 * the server's phone_today(): calls, connected, talk time, overdue (and stage changes / new leads
 * where the server gives them), most calls first. Tap the status line to refresh.
 * 3.5: the card look (my day as a blue hero, one card per person with an avatar).
 */
class IttcTeamActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityTeamBinding::inflate)
    private var loading = false
    private var data: JSONObject? = null
    private lateinit var p: IttcTheme.Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = IttcTheme.of(this)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcTeamScrollview))
            // (no colour change on scroll: the bar keeps the page's soft background)
            ittcTeamStatus.setOnClickListener { load(force = true) }
        }
        updateTextColors(binding.ittcTeamScrollview)
        binding.ittcTeamCoordinator.setBackgroundColor(p.bg)
        // (the numbers kept from the Leads tab at once, then fresh ones)
        data = IttcToday.peek()
        render()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcTeamAppbar, NavigationIcon.Arrow, topBarColor = p.bg)
        load(force = false)
    }

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        // (only the status line: the list on screen stays until the answer comes)
        renderStatus()
        if (data == null) showPlaceholder(null)
        ensureBackgroundThread {
            var signedOut = false
            val answer = try {
                if (!IttcDirect.isActive(this)) {
                    signedOut = true
                    null
                } else {
                    IttcToday.direct(this, maxAgeMs = 20_000, force = force)
                }
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (signedOut) {
                    data = null
                    showPlaceholder(getString(R.string.ittc_l2_team_signed_out))
                    binding.ittcTeamStatus.text = null
                    return@runOnUiThread
                }
                if (answer != null) data = answer
                render()
            }
        }
    }

    private fun showPlaceholder(text: String?) {
        binding.ittcTeamPlaceholder.text = text
        binding.ittcTeamPlaceholder.setTextColor(p.muted)
        binding.ittcTeamPlaceholder.beVisibleIf(text != null)
    }

    private fun renderStatus() {
        binding.ittcTeamStatus.setTextColor(if (IttcToday.offline) p.danger else p.muted)
        binding.ittcTeamStatus.text = when {
            loading -> getString(R.string.ittc_l2_team_loading)
            IttcToday.offline && !IttcToday.lastError.isNullOrEmpty() -> getString(R.string.ittc_l2_team_error, IttcToday.lastError)
            IttcToday.offline -> getString(R.string.ittc_l2_team_offline)
            data != null && IttcToday.fetchedAt > 0 -> getString(
                R.string.ittc_l2_team_updated,
                DateUtils.getRelativeTimeSpanString(IttcToday.fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> null
        }
    }

    private fun render() {
        renderStatus()
        val list = binding.ittcTeamList
        list.removeAllViews()
        val d = data
        if (d == null) {
            showPlaceholder(if (loading) null else getString(R.string.ittc_l2_team_offline))
            return
        }
        d.optJSONObject("me")?.let { me -> list.addView(row(getString(R.string.ittc_l2_team_me), null, me, hero = true), rowParams()) }
        val team = d.optJSONArray("team")
        val people = (0 until (team?.length() ?: 0)).mapNotNull { team?.optJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("calls") }.thenBy { it.optString("name") })
        people.forEach { p ->
            val role = when (p.optString("role")) {
                "team_lead" -> "🧑‍💼"
                "caller" -> "📞"
                else -> null
            }
            list.addView(row(p.optString("name").ifBlank { "—" }, role, p, hero = false), rowParams())
        }
        showPlaceholder(if (people.isEmpty()) getString(R.string.ittc_l2_team_none) else null)
    }

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        val m = IttcTheme.dp(this@IttcTeamActivity, 16f)
        setMargins(m, 0, m, IttcTheme.dp(this@IttcTeamActivity, 8f))
    }

    /** A card: avatar, "Rahim 🧑‍💼" and "📞 23 calls · ✅ 9 connected · ⏱ 41m · ⚠ 4 overdue"; [hero]: my own
     * day on the blue hero */
    private fun row(name: String, badge: String?, o: JSONObject, hero: Boolean): LinearLayout {
        val pad = IttcTheme.dp(this, 14f)
        val nameColor = if (hero) p.onPrimary else p.text
        val textColor = if (hero) p.onPrimaryMuted else p.text2
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = IttcTheme.rounded(context, if (hero) p.primary else p.surface, if (hero) 20f else 16f)
            val (avBg, avFg) = IttcTheme.avatarColors(context, name)
            val size = IttcTheme.dp(context, 44f)
            // (my own day: no avatar, the hero says it)
            if (!hero) addView(TextView(context).apply {
                text = IttcTheme.initials(name)
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(avFg)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(avBg)
                }
            }, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = listOfNotNull(name, badge).joinToString(" ")
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(nameColor)
                })
                val parts = arrayListOf(
                    getString(R.string.ittc_l2_team_line, o.optInt("calls"), o.optInt("connected"), IttcToday.duration(o.optInt("talk_sec"))),
                )
                if (o.has("stage_changes")) parts += getString(R.string.ittc_l2_team_stages, o.optInt("stage_changes"))
                if (o.has("new_leads")) parts += getString(R.string.ittc_l2_team_new, o.optInt("new_leads"))
                if (o.optInt("overdue") > 0) parts += getString(R.string.ittc_l2_team_overdue, o.optInt("overdue"))
                addView(TextView(context).apply {
                    text = parts.joinToString(" · ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(textColor)
                    setPadding(0, IttcTheme.dp(context, 3f), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!hero) marginStart = IttcTheme.dp(context, 12f) })
        }
    }

    companion object {
        fun open(context: Context) {
            try {
                context.startActivity(Intent(context, IttcTeamActivity::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
