package org.fossify.phone.activities

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyMaterialSwitch
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityModeBinding
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.helpers.IttcMode
import org.fossify.phone.helpers.IttcModeAuto
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * 🎓 ITTC Phone 3.12: ITTC & Personal mode — the mode now, "Switch by office hours" (days, from,
 * to), "Office SIM is ITTC" (which SIM), what a lead's call does in Personal mode (when the admin
 * lets people choose) and what the admin limits. Each change is saved at once; the office hours'
 * alarms follow ([IttcModeAuto.settingsChanged]). Made again when the mode or the admin's settings
 * change ([IttcMode]'s listeners).
 */
class IttcModeActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityModeBinding::inflate)
    private lateinit var p: IttcTheme.Palette

    /** The phone's SIMs (read once, off the main thread): Telecom's account id → "SIM 1 (…)" */
    private var sims: List<Pair<String, String>>? = null

    private val modeListener: () -> Unit = { if (!isFinishing && !isDestroyed) build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = IttcTheme.of(this)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.ittcModeScroll))
        binding.ittcModeCoordinator.setBackgroundColor(p.bg)
        IttcMode.addListener(modeListener)
        build()
        val app = applicationContext
        ensureBackgroundThread {
            val list = try {
                app.getAvailableSIMCardLabels().map { it.handle.id to "SIM ${it.id} · ${it.label}" }
            } catch (_: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sims = list
                build()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForDisplaySize) return
        setupTopAppBar(binding.ittcModeAppbar, NavigationIcon.Arrow, topBarColor = p.bg)
    }

    override fun onDestroy() {
        IttcMode.removeListener(modeListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------------- the screen
    private fun build() {
        val holder = binding.ittcModeHolder
        holder.removeAllViews()
        val admin = IttcMode.admin(this)
        val personal = IttcMode.isPersonal(this)

        // the mode now
        caption(holder, R.string.ittc_mode_now)
        card(holder).apply {
            val seg = LinearLayout(this@IttcModeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = IttcTheme.rounded(this@IttcModeActivity, p.surfaceAlt, 999f)
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            segment(seg, R.string.ittc_sp_mode_ittc, !personal) { IttcMode.request(this@IttcModeActivity, false) }
            if (admin.personalAllowed || personal) {
                segment(seg, R.string.ittc_sp_mode_personal, personal) { IttcMode.request(this@IttcModeActivity, true) }
            }
            addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            line(this, if (personal) R.string.ittc_mode_now_personal else R.string.ittc_mode_now_ittc)
        }

        if (admin.personalAllowed) {
            buildHours(holder)
            buildSim(holder)
            if (admin.letUserChangeLeadCall) buildLeadCall(holder)
        }

        // what the admin limits
        val info = when {
            !admin.personalAllowed -> getString(R.string.ittc_mode_admin_off)
            admin.lockOfficeHours -> getString(R.string.ittc_mode_admin_locked, hoursText(IttcMode.hours(this)))
            else -> getString(R.string.ittc_mode_admin_info)
        }
        holder.addView(
            TextView(this).apply {
                text = info
                setTextColor(p.text2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = IttcTheme.rounded(this@IttcModeActivity, if (admin.limits) p.primarySoft else p.surfaceAlt, 14f)
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            },
            spaced(),
        )
    }

    private fun buildHours(holder: LinearLayout) {
        val locked = IttcMode.hoursLocked(this)
        val h = IttcMode.hours(this)
        caption(holder, R.string.ittc_mode_hours_caption)
        card(holder).apply {
            switchRow(this, R.string.ittc_mode_auto, getString(R.string.ittc_mode_auto_sub), IttcMode.autoSwitch(this@IttcModeActivity)) { on ->
                IttcMode.setAutoSwitch(this@IttcModeActivity, on)
                IttcModeAuto.settingsChanged(this@IttcModeActivity, autoTurnedOn = on)
            }

            // the days: the week from Saturday (as the office's), short names in the phone's language
            val names = DateFormatSymbols.getInstance().shortWeekdays
            val row = LinearLayout(this@IttcModeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(6, 0, 1, 2, 3, 4, 5).forEach { day ->
                val on = day in h.days
                val chip = TextView(this@IttcModeActivity).apply {
                    // (Calendar's SUNDAY is 1: getDay's 0)
                    text = names.getOrNull(day + Calendar.SUNDAY)?.take(3) ?: day.toString()
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
                    minHeight = dp(36f)
                    IttcTheme.pill(this, if (on) p.primary else p.surfaceAlt, if (on) p.onPrimary else p.text2)
                    isSelected = on
                    contentDescription = names.getOrNull(day + Calendar.SUNDAY)
                    isEnabled = !locked
                    alpha = if (locked) 0.6f else 1f
                    IttcUi.ripple(this)
                    setOnClickListener {
                        val days = if (on) h.days - day else h.days + day
                        saveHours(h.copy(days = days))
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (day == 5) 0 else dp(4f)
                })
            }
            addView(row, spaced(top = 12f))

            // from / to
            val times = LinearLayout(this@IttcModeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            timeButton(times, R.string.ittc_mode_from, h.from, locked) { saveHours(h.copy(from = it)) }
            timeButton(times, R.string.ittc_mode_to, h.to, locked) { saveHours(h.copy(to = it)) }
            addView(times, spaced(top = 12f))
            line(this, if (h.empty) R.string.ittc_mode_hours_none else R.string.ittc_mode_notice_note)
        }
    }

    private fun saveHours(h: IttcMode.Hours) {
        IttcMode.setHours(this, h)
        IttcModeAuto.settingsChanged(this)
    }

    private fun buildSim(holder: LinearLayout) {
        val list = sims
        val chosen = IttcMode.officeSimId(this)
        caption(holder, R.string.ittc_mode_sim_caption)
        card(holder).apply {
            val label = IttcMode.officeSimLabel(this@IttcModeActivity)
            val sub = if (label != null) getString(R.string.ittc_mode_sim_sub, label) else getString(R.string.ittc_mode_sim_sub_none)
            switchRow(this, R.string.ittc_mode_sim, sub, IttcMode.officeSimOn(this@IttcModeActivity)) { on ->
                // (on with none chosen yet: the first SIM)
                val first = if (on && chosen == null) list?.firstOrNull() else null
                if (first != null) IttcMode.setOfficeSim(this@IttcModeActivity, true, first.first, first.second)
                else IttcMode.setOfficeSim(this@IttcModeActivity, on)
            }
            if (!IttcMode.officeSimOn(this@IttcModeActivity) || list == null) return@apply
            if (list.size < 2) {
                line(this, R.string.ittc_mode_sim_one)
                if (list.isEmpty()) return@apply
            }
            line(this, R.string.ittc_mode_sim_pick)
            list.forEach { (id, name) ->
                option(this, name, id == chosen) { IttcMode.setOfficeSim(this@IttcModeActivity, true, id, name) }
            }
        }
    }

    private fun buildLeadCall(holder: LinearLayout) {
        val now = IttcMode.leadCallInPersonal(this)
        caption(holder, R.string.ittc_mode_lead_caption)
        card(holder).apply {
            listOf(
                IttcMode.LEAD_NOTHING to R.string.ittc_mode_lead_nothing,
                IttcMode.LEAD_HINT to R.string.ittc_mode_lead_hint,
                IttcMode.LEAD_LOG to R.string.ittc_mode_lead_log,
            ).forEach { (value, text) ->
                option(this, getString(text), value == now) {
                    IttcMode.setLeadCall(this@IttcModeActivity, value)
                    build()
                }
            }
        }
    }

    // ------------------------------------------------------------------------- pieces
    private fun dp(v: Float): Int = IttcTheme.dp(this, v)

    private fun spaced(top: Float = 8f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun caption(holder: LinearLayout, text: Int) {
        holder.addView(
            TextView(this).apply {
                setText(text)
                setTextColor(p.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.06f
                setPadding(dp(4f), 0, 0, 0)
            },
            spaced(top = 16f),
        )
    }

    private fun card(holder: LinearLayout): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            IttcTheme.card(this, p)
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            holder.addView(this, spaced())
        }

    private fun line(parent: LinearLayout, text: Int) {
        parent.addView(
            TextView(this).apply {
                setText(text)
                setTextColor(p.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            },
            spaced(top = 10f),
        )
    }

    private fun segment(parent: LinearLayout, text: Int, on: Boolean, onClick: () -> Unit) {
        parent.addView(
            TextView(this).apply {
                setText(text)
                gravity = Gravity.CENTER
                minHeight = dp(40f)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (on) p.primary else p.muted)
                background = if (on) IttcTheme.rounded(this@IttcModeActivity, p.surface, 999f) else null
                isSelected = on
                IttcUi.ripple(this)
                setOnClickListener { if (!on) onClick() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun switchRow(parent: LinearLayout, title: Int, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MyMaterialSwitch(this).apply {
            setText(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setColors(p.text, getProperPrimaryColor(), p.surface)
            isChecked = checked
            minHeight = dp(48f)
            setOnClickListener { onChange(isChecked) }
        }
        parent.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(
            TextView(this).apply {
                text = sub
                setTextColor(p.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            },
        )
    }

    /** A choice with a ● / ○ (radio) */
    private fun option(parent: LinearLayout, text: String, on: Boolean, onClick: () -> Unit) {
        parent.addView(
            TextView(this).apply {
                this.text = getString(if (on) R.string.ittc_mode_option_on else R.string.ittc_mode_option_off, text)
                setTextColor(if (on) p.primary else p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                if (on) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44f)
                isSelected = on
                IttcUi.ripple(this)
                setOnClickListener { if (!on) onClick() }
            },
            spaced(top = 2f),
        )
    }

    private fun timeButton(parent: LinearLayout, label: Int, minutes: Int, locked: Boolean, onPick: (Int) -> Unit) {
        val view = TextView(this).apply {
            text = getString(R.string.ittc_mode_time_value, getString(label), clock(minutes))
            gravity = Gravity.CENTER
            minHeight = dp(44f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            IttcTheme.pill(this, p.surfaceAlt, p.text)
            isEnabled = !locked
            alpha = if (locked) 0.6f else 1f
            IttcUi.ripple(this)
            setOnClickListener {
                TimePickerDialog(
                    this@IttcModeActivity,
                    { _, hour, minute -> onPick(hour * 60 + minute) },
                    minutes / 60, minutes % 60,
                    android.text.format.DateFormat.is24HourFormat(this@IttcModeActivity),
                ).show()
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount == 0) marginEnd = dp(8f)
        })
    }

    /** A time of the day as the phone writes times (9:00 AM / 09:00) */
    private fun clock(minutes: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return IttcModeAuto.timeText(this, c.timeInMillis)
    }

    private fun hoursText(h: IttcMode.Hours): String = "${clock(h.from)}–${clock(h.to)}"

    companion object {
        fun open(context: Context) {
            try {
                context.startActivity(Intent(context, IttcModeActivity::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
