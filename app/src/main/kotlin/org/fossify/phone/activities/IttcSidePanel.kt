package org.fossify.phone.activities

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isBlackAndWhiteTheme
import org.fossify.commons.extensions.isWhiteTheme
import org.fossify.commons.helpers.AppTextScale
import org.fossify.commons.views.MySearchMenu
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcSidePanelBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.IttcDisplay
import org.fossify.phone.helpers.IttcMode
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi

/**
 * 🎓 ITTC Phone 3.12: the main screen's side panel — ☰ at the top left or a swipe from the left
 * edge opens it (300 dp, over the screen with a scrim; androidx DrawerLayout, which the app already
 * has through AppCompat / Material). It holds who is signed in, ITTC / Personal mode (signed in
 * only), the text size, dark theme, "Open with the dial pad", Display & text size, Settings, About.
 *
 * The text size is live: while the slider moves, the text of the screen behind takes the new size
 * at once ([IttcDisplay.previewText]: one pass over the views on screen, no new screen per step);
 * it is saved when the slider is let go, and the screen is made again with the real size when the
 * panel closes (the other screens when they show again).
 */
class IttcSidePanel(
    private val activity: MainActivity,
    private val content: View,
    private val onOpenWithDialpadChanged: () -> Unit,
    private val onSettings: () -> Unit,
    private val onAbout: () -> Unit,
) {
    val drawer: DrawerLayout = DrawerLayout(activity)
    private val b = IttcSidePanelBinding.inflate(activity.layoutInflater, drawer, false)
    private val madeVersion = IttcDisplay.version(activity)
    private var menuButton: ImageView? = null
    private var textPct = IttcDisplay.textPercent(activity)
    private var name: String? = null

    private val modeListener: () -> Unit = { showMode() }

    init {
        drawer.id = R.id.ittc_drawer
        drawer.addView(content, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val widthDp = activity.resources.configuration.screenWidthDp
        val panelDp = if (widthDp > 0) minOf(PANEL_DP, widthDp - 56) else PANEL_DP
        drawer.addView(
            b.root,
            DrawerLayout.LayoutParams(dp(panelDp.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        // (the panel below the status bar and above the navigation bar; the screen keeps its own insets)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            b.ittcPanelHolder.setPadding(0, bars.top, 0, bars.bottom + dp(16f))
            insets
        }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                showValues()
            }

            override fun onDrawerClosed(drawerView: View) {
                // (a new text size: the screen made again with it now; when away, on its return)
                if (IttcDisplay.version(activity) != madeVersion && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    activity.recreate()
                }
            }
        })
        setup()
        IttcMode.addListener(modeListener)
    }

    val isOpen: Boolean get() = drawer.isDrawerOpen(GravityCompat.START)

    fun open() = drawer.openDrawer(GravityCompat.START)

    fun close(animate: Boolean = true) = drawer.closeDrawer(GravityCompat.START, animate)

    fun destroy() {
        IttcMode.removeListener(modeListener)
    }

    // ------------------------------------------------------------------------- ☰ in the top bar
    /** ☰ at the start of the main top bar, before the search box */
    fun addMenuButton(menu: MySearchMenu) {
        val container = menu.binding.searchBarContainer
        val size = dp(48f)
        val button = ImageView(activity).apply {
            setImageResource(R.drawable.ittc_ic_menu)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = activity.getString(R.string.ittc_sp_open)
            setBackgroundResource(org.fossify.commons.R.drawable.ripple_all_corners)
            setOnClickListener { open() }
        }
        container.addView(button, FrameLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        container.setPaddingRelative(dp(4f), container.paddingTop, container.paddingEnd, container.paddingBottom)
        (menu.binding.toolbarContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = size + dp(4f)
            menu.binding.toolbarContainer.layoutParams = lp
        }
        menuButton = button
    }

    // ------------------------------------------------------------------------- the panel
    private fun setup() {
        b.ittcPanelModeIttc.setOnClickListener { setMode(false) }
        b.ittcPanelModePersonal.setOnClickListener { setMode(true) }

        b.ittcPanelTextSeek.max = (AppTextScale.MAX - AppTextScale.MIN) / AppTextScale.STEP
        b.ittcPanelTextSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                textPct = AppTextScale.MIN + progress * AppTextScale.STEP
                b.ittcPanelTextValue.text = activity.getString(R.string.ittc_ds_percent, textPct)
                IttcDisplay.previewText(activity, content, textPct)
                if (!tracking) IttcDisplay.setTextPercent(activity, textPct)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                tracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                IttcDisplay.setTextPercent(activity, textPct)
            }
        })

        b.ittcPanelDarkHolder.setOnClickListener { setDark(!b.ittcPanelDark.isChecked) }
        b.ittcPanelDialpadHolder.setOnClickListener {
            b.ittcPanelDialpad.toggle()
            activity.config.ittcOpenWithDialpad = b.ittcPanelDialpad.isChecked
            onOpenWithDialpadChanged()
        }
        b.ittcPanelDisplay.setOnClickListener {
            IttcDisplayActivity.open(activity)
            close(animate = false)
        }
        b.ittcPanelSettings.setOnClickListener {
            onSettings()
            close(animate = false)
        }
        b.ittcPanelAbout.setOnClickListener {
            onAbout()
            close(animate = false)
        }
        listOf(b.ittcPanelModeIttc, b.ittcPanelModePersonal).forEach { IttcUi.ripple(it) }
        showValues()
        showAccount(false, null, null)
    }

    /** Colours for the app's theme (on every return to the screen) */
    fun applyColors() {
        val p = IttcTheme.of(activity)
        val accent = activity.getProperPrimaryColor()
        val radius = dp(20f).toFloat()
        val rtl = drawer.layoutDirection == View.LAYOUT_DIRECTION_RTL
        b.root.background = GradientDrawable().apply {
            setColor(p.surface)
            cornerRadii = if (rtl) {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
        b.ittcPanelName.setTextColor(p.text)
        b.ittcPanelStatus.setTextColor(p.muted)
        b.ittcPanelModeLabel.setTextColor(p.muted)
        b.ittcPanelMode.background = IttcTheme.rounded(activity, p.surfaceAlt, 999f)
        b.ittcPanelTextCard.background = IttcTheme.rounded(activity, p.surfaceAlt, 16f)
        b.ittcPanelTextTitle.setTextColor(p.text)
        b.ittcPanelTextValue.setTextColor(p.primary)
        b.ittcPanelTextSeek.progressTintList = IttcTheme.tint(p.primary)
        b.ittcPanelTextSeek.thumbTintList = IttcTheme.tint(p.primary)
        b.ittcPanelTextSeek.progressBackgroundTintList = IttcTheme.tint(p.outline)
        b.ittcPanelDark.setColors(p.text, accent, p.surface)
        b.ittcPanelDialpad.setColors(p.text, accent, p.surface)
        b.ittcPanelDivider.setBackgroundColor(p.divider)
        listOf(b.ittcPanelDisplay, b.ittcPanelSettings, b.ittcPanelAbout).forEach { link ->
            link.setTextColor(p.text)
            link.compoundDrawableTintList = IttcTheme.tint(p.text2)
        }
        menuButton?.setColorFilter(activity.getProperBackgroundColor().getContrastColor())
        showMode()
        showAccountColors()
    }

    /** The switches and the text size as saved (each time the panel opens) */
    private fun showValues() {
        textPct = IttcDisplay.textPercent(activity)
        b.ittcPanelTextSeek.progress = (textPct - AppTextScale.MIN) / AppTextScale.STEP
        b.ittcPanelTextValue.text = activity.getString(R.string.ittc_ds_percent, textPct)
        b.ittcPanelDark.isChecked = IttcTheme.of(activity).dark
        b.ittcPanelDialpad.isChecked = activity.config.ittcOpenWithDialpad
    }

    // ------------------------------------------------------------------------- who / mode
    /** Who is signed in (read off the main thread by the caller); the mode switch only when signed in */
    fun showAccount(signedIn: Boolean, name: String?, email: String?) {
        this.name = name?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() }
        b.ittcPanelName.text = this.name ?: activity.getString(R.string.ittc_sp_guest)
        b.ittcPanelStatus.text = activity.getString(if (signedIn) R.string.ittc_sp_signed_in else R.string.ittc_sp_signed_out)
        b.ittcPanelModeHolder.visibility = if (signedIn) View.VISIBLE else View.GONE
        showAccountColors()
        showMode()
    }

    private fun showAccountColors() {
        val shown = name ?: activity.getString(R.string.ittc_sp_guest)
        val (bg, fg) = IttcTheme.avatarColors(activity, shown)
        b.ittcPanelAvatar.text = IttcTheme.initials(shown)
        b.ittcPanelAvatar.background = IttcTheme.rounded(activity, bg, 999f)
        b.ittcPanelAvatar.setTextColor(fg)
    }

    private fun setMode(personal: Boolean) {
        if (!IttcMode.canSwitch(activity)) return
        IttcMode.setPersonal(activity, personal)
        showMode()
    }

    private fun showMode() {
        val p = IttcTheme.of(activity)
        val personal = IttcMode.isPersonal(activity)
        segment(b.ittcPanelModeIttc, !personal, p)
        segment(b.ittcPanelModePersonal, personal, p)
    }

    private fun segment(v: TextView, on: Boolean, p: IttcTheme.Palette) {
        v.background = if (on) IttcTheme.rounded(activity, p.surface, 999f) else null
        v.setTextColor(if (on) p.primary else p.muted)
        v.isSelected = on
    }

    // ------------------------------------------------------------------------- dark theme
    /**
     * The app's own light / dark colours (Settings → Customize colours' Light / Dark themes; the
     * accent stays). Every screen is made again with them.
     */
    private fun setDark(dark: Boolean) {
        val c = activity.baseConfig
        val res = activity.resources
        if (activity.isWhiteTheme() || activity.isBlackAndWhiteTheme()) c.primaryColor = res.getColor(org.fossify.commons.R.color.color_primary, activity.theme)
        c.isSystemThemeEnabled = false
        if (dark) {
            c.textColor = res.getColor(org.fossify.commons.R.color.theme_dark_text_color, activity.theme)
            c.backgroundColor = res.getColor(org.fossify.commons.R.color.theme_dark_background_color, activity.theme)
        } else {
            c.textColor = res.getColor(org.fossify.commons.R.color.theme_light_text_color, activity.theme)
            c.backgroundColor = res.getColor(org.fossify.commons.R.color.theme_light_background_color, activity.theme)
        }
        b.ittcPanelDark.isChecked = dark
        // (the other open screens follow when they show again; this one now, the panel kept open)
        AppTextScale.bumpVersion(activity)
        activity.recreate()
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()

    private companion object {
        const val PANEL_DP = 300
    }
}
