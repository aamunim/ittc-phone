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
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityTeamBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcToday
import org.json.JSONObject

/**
 * 👥 ITTC: the team's day (a team lead's callers; a super admin: every caller and team lead), from
 * the server's phone_today(): calls, connected, talk time, overdue (and stage changes / new leads
 * where the server gives them), most calls first. Tap the status line to refresh.
 */
class IttcTeamActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityTeamBinding::inflate)
    private var loading = false
    private var data: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcTeamScrollview))
            setupMaterialScrollListener(ittcTeamScrollview, ittcTeamAppbar)
            ittcTeamStatus.setOnClickListener { load(force = true) }
        }
        updateTextColors(binding.ittcTeamScrollview)
        // (the numbers kept from the Leads tab at once, then fresh ones)
        data = IttcToday.peek()
        render()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcTeamAppbar, NavigationIcon.Arrow)
        load(force = false)
    }

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        render()
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
        binding.ittcTeamPlaceholder.setTextColor(getProperTextColor())
        binding.ittcTeamPlaceholder.beVisibleIf(text != null)
    }

    private fun render() {
        val textColor = getProperTextColor()
        val primary = getProperPrimaryColor()
        binding.ittcTeamStatus.setTextColor(if (IttcToday.offline) 0xFFE53935.toInt() else textColor)
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
        val list = binding.ittcTeamList
        list.removeAllViews()
        val d = data
        if (d == null) {
            showPlaceholder(if (loading) null else getString(R.string.ittc_l2_team_offline))
            return
        }
        d.optJSONObject("me")?.let { me -> list.addView(row(getString(R.string.ittc_l2_team_me), null, me, primary, textColor)) }
        val team = d.optJSONArray("team")
        val people = (0 until (team?.length() ?: 0)).mapNotNull { team?.optJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("calls") }.thenBy { it.optString("name") })
        people.forEach { p ->
            val role = when (p.optString("role")) {
                "team_lead" -> "🧑‍💼"
                "caller" -> "📞"
                else -> null
            }
            list.addView(row(p.optString("name").ifBlank { "—" }, role, p, textColor, textColor))
        }
        showPlaceholder(if (people.isEmpty()) getString(R.string.ittc_l2_team_none) else null)
    }

    /** "Rahim 🧑‍💼" and "📞 23 calls · ✅ 9 connected · ⏱ 41m · ⚠ 4 overdue" */
    private fun row(name: String, badge: String?, o: JSONObject, nameColor: Int, textColor: Int): LinearLayout {
        val pad = resources.getDimensionPixelSize(R.dimen.activity_margin)
        val small = resources.getDimensionPixelSize(R.dimen.medium_margin)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, small, pad, small)
            addView(TextView(context).apply {
                text = listOfNotNull(name, badge).joinToString(" ")
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textColor)
                alpha = 0.85f
            })
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
