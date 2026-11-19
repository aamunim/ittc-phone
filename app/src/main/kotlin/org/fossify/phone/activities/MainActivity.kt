package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.snackbar.Snackbar
import me.grantland.widget.AutofitHelper
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.helpers.IttcDisclosure
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcNotify
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.adapters.ViewPagerAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.dialogs.ChangeSortingDialog
import org.fossify.phone.dialogs.FilterContactSourcesDialog
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.handleFullScreenNotificationsPermission
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.fragments.ContactsFragment
import org.fossify.phone.fragments.FavoritesFragment
import org.fossify.phone.fragments.IttcLeadsFragment
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.fragments.RecentsFragment
import org.fossify.phone.helpers.ITTC_TAB_LEADS
import org.fossify.phone.helpers.IttcMode
import org.fossify.phone.helpers.IttcTabBadges
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import org.fossify.phone.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.ittcVisibleTabs
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false
    var cachedContacts = ArrayList<Contact>()
    /** Goes up each time cachedContacts is filled again (Recents drops its per-row contact matches then) */
    var cachedContactsVersion = 0

    // 🎓 ITTC 3.6: the dial pad on the main screen; the contacts it searches (a list never changed
    // after it is set, safe to read off the main thread); a tab change counts only after a touch
    // (the first tab is chosen by code at start)
    private lateinit var ittcDialpad: IttcMainDialpad

    @Volatile
    private var ittcDialContacts: List<Contact> = emptyList()
    private var ittcUserTouched = false

    // 🎓 ITTC 3.12: the side panel (☰) and the top bar's 💼 / 🏠 mode button (signed in only)
    private lateinit var ittcPanel: IttcSidePanel
    private var ittcModeItem: MenuItem? = null
    private var ittcModeButton: TextView? = null
    private val ittcModeListener: () -> Unit = { if (!isFinishing && !isDestroyed) ittcModeChanged() }
    private var ittcLastTab = -1
    private var ittcMovingPager = false
    private var ittcStoredOpenWithDialpad = true

    // 🎓 ITTC 3.8: the kinds of the tabs shown, in their order (Call History, Leads, Contacts,
    // Favorites — ittcVisibleTabs): the one source of every tab position on this screen. Read once
    // (a change of the tabs shown in Settings restarts the app; 🎓 3.12: a change of ITTC / Personal
    // mode makes the tabs again — no Leads tab in Personal mode — see ittcRebuildTabs).
    private var tabKindsCache: List<Int>? = null
    private val tabKinds: List<Int>
        get() = tabKindsCache ?: ittcWantedTabs().also { tabKindsCache = it }

    private fun ittcWantedTabs(): List<Int> = ittcVisibleTabs(config.showTabs, IttcMode.isPersonal(this))

    /** The kind of the tab at [position] (null: none there) */
    private fun kindAt(position: Int): Int? = tabKinds.getOrNull(position)

    /** The position of the tab of [kind] (-1: hidden) */
    private fun positionOf(kind: Int): Int = tabKinds.indexOf(kind)

    /** The kind of the page on screen (before the pages exist: of the tab chosen) */
    private fun currentKind(): Int? = kindAt(
        if (binding.viewPager.adapter != null) binding.viewPager.currentItem else binding.mainTabsHolder.selectedTabPosition
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        org.fossify.phone.helpers.IttcCrash.install(this)
        super.onCreate(savedInstanceState)
        // 🎓 ITTC: each launch opens the Leads tab on All / All / All (a rotation keeps the choices)
        if (savedInstanceState == null) IttcLeadsFragment.resetSession()
        // 🎓 ITTC 3.12: the screen inside the side panel's drawer (☰ / a swipe from the left edge)
        ittcPanel = IttcSidePanel(
            activity = this,
            content = binding.root,
            onOpenWithDialpadChanged = {
                ittcStoredOpenWithDialpad = config.ittcOpenWithDialpad
                ittcDialpad.settingChanged()
            },
            onSettings = { launchSettings() },
            onAbout = { launchAbout() },
        )
        setContentView(ittcPanel.drawer)
        appLaunched(BuildConfig.APPLICATION_ID)
        // 🎓 ITTC: the reason the app stopped last time, once (to report it)
        if (savedInstanceState == null) org.fossify.phone.helpers.IttcCrash.showIfAny(this)
        setupOptionsMenu()
        setupIttcTopBar()
        ittcPanel.addMenuButton(binding.mainMenu)
        refreshMenuItems()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))
        binding.ittcUpdateBanner.setOnClickListener { IttcAccountActivity.openPlay(this) }

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        // 🎓 ITTC: first what the app does with calls and contacts (Google Play's "prominent
        // disclosure") — also when it was made the phone app in Android's settings — then the rest
        val disclosureFirst = !IttcDisclosure.agreed(this)
        IttcDisclosure.showOnce(this) {
            // (the lists were held back while the disclosure was open: load them now)
            if (disclosureFirst) refreshItems(true)
            if (isDefaultDialer()) {
                checkContactPermissions()

                if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                    val snackbar = Snackbar.make(
                        binding.mainHolder,
                        R.string.allow_displaying_over_other_apps,
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction(R.string.ok) {
                        config.wasOverlaySnackbarConfirmed = true
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }

                    snackbar.setBackgroundTint(getProperBackgroundColor().darkenColor())
                    snackbar.setTextColor(getProperTextColor())
                    snackbar.setActionTextColor(getProperTextColor())
                    snackbar.show()
                }

                handleFullScreenNotificationsPermission { granted ->
                    if (!granted) {
                        toast(org.fossify.commons.R.string.notifications_disabled)
                    }
                }
            } else {
                launchSetDefaultDialerIntent()
            }
        }

        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        IttcTabBadges.addListener(ittcBadgesListener)
        IttcMode.addListener(ittcModeListener)
        // 🎓 ITTC 3.12: the office hours' switch missed while away, the admin's limits, the alarms again
        if (savedInstanceState == null) {
            val app = applicationContext
            ensureBackgroundThread { org.fossify.phone.helpers.IttcModeAuto.check(app) }
        }
        Contact.sorting = config.sorting
        setupIttcDialpad(savedInstanceState)
        storedFontSize = config.fontSize
    }

    override fun onResume() {
        super.onResume()
        // 🎓 ITTC 3.12: a display size changed while away: the screen is being made again
        if (isRecreatingForDisplaySize) return
        if (storedShowTabs != config.showTabs) {
            // (the last tab is kept by kind: still right with other tabs shown, or the first one)
            System.exit(0)
            return
        }
        // 🎓 ITTC 3.12: the mode changed while away (the tile, the office hours): the tabs for it now
        if (ittcTabsStale || ittcWantedTabs() != tabKinds) {
            try {
                ittcRebuildTabs()
            } catch (e: Throwable) {
                org.fossify.phone.helpers.IttcCrash.record(this, e, "Main screen: mode tabs")
            }
        }

        updateMenuColors()
        ittcPanel.applyColors()
        // 🎓 ITTC 3.6: the dial pad's colours (the theme may have changed) and its setting
        ittcDialpad.applyColors()
        if (ittcStoredOpenWithDialpad != config.ittcOpenWithDialpad) {
            ittcStoredOpenWithDialpad = config.ittcOpenWithDialpad
            ittcDialpad.settingChanged()
        }
        val properPrimaryColor = getProperPrimaryColor()
        val dialpadIcon = resources.getColoredDrawableWithColor(R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        binding.mainDialpadButton.setImageDrawable(dialpadIcon)

        updateTextColors(binding.mainHolder)
        setupTabColors()

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        // 🎓 ITTC: no contacts / calls read behind the first-start disclosure
        if (!binding.mainMenu.isSearchOpen && IttcDisclosure.agreed(this)) {
            refreshItems(true)
        }
        // 🎓 ITTC 3.8: the tabs' counts (missed calls not seen yet, leads due)
        ittcRefreshBadges()

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        // 🎓 ITTC 3.2: signed in to ITTC — send what waits, fetch the lists (throttled), tell the Tracker
        ittcResumed = true
        if (IttcDisclosure.agreed(this)) ittcDirectResume()

        checkShortcuts()
        Handler().postDelayed({
            if (IttcDisclosure.agreed(this)) {
                getRecentsFragment()?.refreshItems()
                // (the Leads tab has read its list by now, and a call that just ended is in the log)
                if (!isFinishing && !isDestroyed) ittcRefreshBadges()
            }
        }, 2000)
    }

    // 🎓 ITTC: the Tracker's "Make it the phone app" while this is already open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("ask_default", false) && !isDefaultDialer()) {
            IttcDisclosure.showOnce(this) { launchSetDefaultDialerIntent() }
        }
    }

    override fun onPause() {
        super.onPause()
        // 🎓 ITTC 3.5: no live notification watch while the app is in the background
        ittcResumed = false
        if (ittcNotifyStarted) {
            ittcNotifyStarted = false
            try {
                IttcNotify.removeListener(ittcUnreadListener)
                IttcNotify.stop()
            } catch (_: Throwable) {
            }
        }
        storedShowTabs = config.showTabs
        storedFontSize = config.fontSize
        storedStartNameWithSurname = config.startNameWithSurname
        // 🎓 ITTC 3.8: the last tab by kind (not before the pages exist: nothing was shown yet)
        if (binding.viewPager.adapter != null) {
            currentKind()?.let { config.ittcLastTabKind = it }
            if (currentKind() == TAB_CALL_HISTORY && ittcRecentsShown) config.ittcRecentsSeenAt = System.currentTimeMillis()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
        ittcDialpad.save(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 🎓 ITTC 3.6: the dial pad's keys for the new height (folded when it no longer fits)
        ittcDialpad.configurationChanged()
        if (IttcDisclosure.agreed(this)) refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        // 🎓 ITTC 3.12: Back closes the side panel first
        if (ittcPanel.isOpen) {
            ittcPanel.close()
            return true
        }
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            // 🎓 ITTC 3.6: as on MIUI, Back folds the dial pad first (then clears what it typed), then leaves
            ittcDialpad.onBack()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ittcUserTouched = true
    }

    // 🎓 ITTC 3.6: the dial pad on the main screen (open on a fresh start with the setting on); the
    // round button opens it (or, with the setting off, the dial pad screen, as before)
    private fun setupIttcDialpad(savedInstanceState: Bundle?) {
        ittcStoredOpenWithDialpad = config.ittcOpenWithDialpad
        ittcDialpad = IttcMainDialpad(
            activity = this,
            main = binding,
            callLog = { getRecentsFragment()?.ittcCallLog().orEmpty() },
            contacts = { ittcDialContacts },
        )
        binding.mainDialpadButton.setOnClickListener { ittcDialpad.open() }
        ittcDialpad.restore(savedInstanceState)
    }

    /** 🎓 ITTC 3.6: a tab's page made: dragging its list folds the dial pad */
    fun ittcPageCreated(page: MyViewPagerFragment<*>) {
        page.ittcOnUserScroll { if (::ittcDialpad.isInitialized) ittcDialpad.collapse() }
    }

    override fun onDestroy() {
        super.onDestroy()
        IttcTabBadges.removeListener(ittcBadgesListener)
        IttcMode.removeListener(ittcModeListener)
        if (::ittcPanel.isInitialized) ittcPanel.destroy()
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val kind = currentKind()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = kind == TAB_CALL_HISTORY
            val contactsTab = kind == TAB_CONTACTS || kind == TAB_FAVORITES
            findItem(R.id.sort).isVisible = contactsTab
            findItem(R.id.filter).isVisible = contactsTab
            findItem(R.id.create_new_contact).isVisible = kind == TAB_CONTACTS
            findItem(R.id.change_view_type).isVisible = kind == TAB_FAVORITES
            findItem(R.id.column_count).isVisible = kind == TAB_FAVORITES && config.viewType == VIEW_TYPE_GRID
        }
        ittcSearchForTab(kind)
    }

    // 🎓 ITTC 3.8: no toolbar search on the Leads tab (its own 🔍 "Search leads" stays in the top
    // bar): the search field, its icon and its rounded box go; an open search is closed first
    // 🎓 ITTC 3.8.1: on the Leads tab (signed in) the search bar keeps its look but opens the lead search
    // screen — one search there, not two (the 🔍 in the bar is hidden on that tab)
    private var ittcLeadSearchMode = false
    private var ittcSignedInTop = false
    private var ittcSearchHint: CharSequence? = null

    private fun ittcSearchForTab(kind: Int?) {
        val leadMode = kind == ITTC_TAB_LEADS && ittcSignedInTop
        // (🎓 3.12: no lead search in Personal mode)
        ittcSearchItem?.isVisible = ittcSignedInTop && !leadMode && !IttcMode.isPersonal(this)
        if (leadMode == ittcLeadSearchMode) return
        ittcLeadSearchMode = leadMode
        val menu = binding.mainMenu
        val searchField = menu.binding.topToolbarSearch
        val icon = menu.binding.topToolbarSearchIcon
        if (leadMode) {
            if (menu.isSearchOpen) menu.closeSearch()
            searchField.clearFocus()
            if (ittcSearchHint == null) ittcSearchHint = searchField.hint
            searchField.hint = getString(R.string.ittc_a_search_leads)
            searchField.isFocusable = false
            searchField.isFocusableInTouchMode = false
            searchField.isCursorVisible = false
            searchField.setOnClickListener { IttcSearchActivity.open(this) }
            icon.setOnClickListener { IttcSearchActivity.open(this) }
        } else {
            ittcSearchHint?.let { searchField.hint = it }
            ittcSearchHint = null
            searchField.setOnClickListener(null)
            searchField.isClickable = false
            searchField.isFocusable = true
            searchField.isFocusableInTouchMode = true
            searchField.isCursorVisible = true
            // (the bar's own 🔍 as MySearchMenu.setupMenu sets it)
            icon.setOnClickListener {
                if (menu.isSearchOpen) {
                    menu.closeSearch()
                } else {
                    searchField.requestFocus()
                    showKeyboard(searchField)
                }
            }
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            // 🎓 ITTC 3.12: the mode button first (before the other icons)
            setupIttcModeButton()
            requireToolbar().inflateMenu(R.menu.menu)
            toggleHideOnScroll(false)
            setupMenu()

            // 🎓 ITTC 3.6: the search takes the place of the dial pad
            onSearchOpenListener = {
                if (::ittcDialpad.isInitialized) ittcDialpad.hideAll()
            }

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.onSearchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.onSearchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.clear_call_history -> clearCallHistory()
                    R.id.create_new_contact -> launchCreateNewContactIntent()
                    R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                    R.id.filter -> showFilterDialog()
                    R.id.settings -> launchSettings()
                    R.id.change_view_type -> changeViewType()
                    R.id.column_count -> changeColumnCount()
                    R.id.about -> launchAbout()
                    R.id.ittc_account -> startActivity(Intent(applicationContext, IttcAccountActivity::class.java))
                    R.id.ittc_a_menu_search_leads -> IttcSearchActivity.open(this@MainActivity)
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        RadioGroupDialog(this, ArrayList(items), currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this) {
            refreshMenuItems()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
        // 🎓 ITTC 3.5: the bell drawn by hand, in the colour of the other top bar icons
        ittcBellIcon?.setColorFilter(getProperBackgroundColor().getContrastColor())
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    getRecentsFragment()?.refreshItems(invalidate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun setupTabColors() {
        val current = if (binding.viewPager.adapter != null) binding.viewPager.currentItem else binding.mainTabsHolder.selectedTabPosition
        for (i in 0 until binding.mainTabsHolder.tabCount) {
            ittcStyleTab(binding.mainTabsHolder.getTabAt(i), i == current, animate = false)
        }

        // 🎓 ITTC 3.6: a flat surface bar with a thin line on top (no shadow)
        val p = IttcTheme.of(this)
        binding.mainTabsHolder.setBackgroundColor(p.surface)
        binding.ittcTabsDivider.setBackgroundColor(if (p.dark) p.divider else p.outline)
        binding.ittcTabsDivider.beVisibleIf(binding.mainTabsHolder.isVisible())
        ittcShowBadges()
    }

    private val ittcTabInterpolator = DecelerateInterpolator()

    /**
     * 🎓 ITTC 3.8: a tab as in Material 3 — chosen: the filled icon on a soft accent pill and a bold
     * label, in the app's accent; the others: the outlined icon and label, muted. [animate]: the pill
     * fades / grows in (or out) in 150 ms (only when the person changes the tab).
     */
    private fun ittcStyleTab(tab: TabLayout.Tab?, active: Boolean, animate: Boolean) {
        val view = tab?.customView ?: return
        val kind = kindAt(tab.position) ?: return
        val p = IttcTheme.of(this)
        val accent = getProperPrimaryColor()
        val color = if (active) accent else p.muted
        view.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
            setImageResource(getTabIconId(kind, active))
            setColorFilter(color)
        }
        view.findViewById<TextView>(R.id.tab_item_label)?.apply {
            setTextColor(color)
            setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val pill = view.findViewById<View>(R.id.ittc_tab_pill) ?: return
        // (each tab its own pill drawable, made again only when the colour changes)
        val pillColor = ittcPillColor(p, accent)
        if (pill.tag != pillColor) {
            pill.background = IttcTheme.rounded(this, pillColor, 999f)
            pill.tag = pillColor
        }
        val alpha = if (active) 1f else 0f
        val scale = if (active) 1f else 0.6f
        pill.animate().cancel()
        if (animate) {
            pill.animate().alpha(alpha).scaleX(scale).setDuration(150L).setInterpolator(ittcTabInterpolator).start()
        } else {
            pill.alpha = alpha
            pill.scaleX = scale
        }
    }

    /** The chosen tab's pill: the palette's soft primary with the app's own accent, else the accent made soft */
    private fun ittcPillColor(p: IttcTheme.Palette, accent: Int): Int =
        if (accent == p.primary || accent == resources.getColor(R.color.color_primary, theme)) {
            p.primarySoft
        } else {
            accent.adjustAlpha(if (p.dark) 0.24f else 0.14f)
        }

    private fun getTabIconId(kind: Int, selected: Boolean): Int = when (kind) {
        TAB_CONTACTS -> if (selected) R.drawable.ic_person_vector else R.drawable.ic_person_outline_vector
        TAB_FAVORITES -> if (selected) R.drawable.ic_star_vector else R.drawable.ic_star_outline_vector
        TAB_CALL_HISTORY -> if (selected) R.drawable.ic_clock_filled_vector else R.drawable.ic_clock_vector
        else -> if (selected) R.drawable.ittc_ic_leads else R.drawable.ittc_ic_leads_outline
    }

    // (once: the phone-app prompt from Recents comes back here and asks again)
    private var fragmentsInited = false

    private fun initFragments() {
        if (fragmentsInited) {
            refreshItems()
            return
        }
        fragmentsInited = true
        binding.viewPager.offscreenPageLimit = 3
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                refreshMenuItems()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        binding.mainTabsHolder.onGlobalLayout {
            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                val recentsPosition = positionOf(TAB_CALL_HISTORY)
                if (intent.action == Intent.ACTION_VIEW && recentsPosition >= 0) {
                    wantedTab = recentsPosition
                }

                binding.mainTabsHolder.getTabAt(wantedTab)?.select()
                refreshMenuItems()
            }, 100L)
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
        }

        // (🎓 ITTC 3.6: not with the main screen's own dial pad, open already)
        if (config.openDialPadAtLaunch && !launchedDialer && !config.ittcOpenWithDialpad) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        // (🎓 3.12: made again for a mode change — the old listener must not see the tabs go and
        // the first new one picked by itself: that would count Call History's missed calls as seen)
        binding.mainTabsHolder.clearOnTabSelectedListeners()
        binding.mainTabsHolder.removeAllTabs()
        tabKindsCache = ittcWantedTabs()
        // 🎓 ITTC 3.8: Call History, Leads (always; 🎓 3.12: not in Personal mode), Contacts, Favorites
        tabKinds.forEach { kind ->
            binding.mainTabsHolder.newTab().setCustomView(R.layout.ittc_tab_item).apply {
                customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(kind))
                customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(kind)
                AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                binding.mainTabsHolder.addTab(this)
            }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                ittcStyleTab(it, false, animate = ittcUserTouched)
                // (Call History was on screen: its missed calls are seen now)
                if (kindAt(it.position) == TAB_CALL_HISTORY && ittcRecentsShown) {
                    ittcRecentsShown = false
                    ittcRecentsSeen()
                }
            },
            tabSelectedAction = {
                // (moving the pager below re-selects this same tab from onPageSelected: that inner call only
                // hands the search text to the page now shown, so the pill's animation isn't cut short)
                if (ittcMovingPager) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                    return@onTabSelectionChanged
                }
                val changed = ittcLastTab != it.position
                // 🎓 ITTC 3.6: another tab (chosen by hand, not the one picked at start) hides the dial pad
                if (ittcLastTab != -1 && changed && ittcUserTouched) {
                    if (::ittcDialpad.isInitialized) ittcDialpad.hideAll()
                    // 🎓 ITTC 3.8: a light tick (the system's touch feedback setting decides)
                    binding.mainTabsHolder.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                ittcLastTab = it.position
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                ittcMovingPager = true
                try {
                    binding.viewPager.currentItem = it.position
                } finally {
                    ittcMovingPager = false
                }
                ittcStyleTab(it, true, animate = changed && ittcUserTouched)
                ittcSearchForTab(kindAt(it.position))

                when (kindAt(it.position)) {
                    TAB_CALL_HISTORY -> {
                        clearMissedCalls()
                        ittcRecentsShown = true
                        ittcRecentsSeen()
                    }

                    ITTC_TAB_LEADS -> {
                        getLeadsFragment()?.refreshItems()
                        // (its count again once the list is read)
                        binding.mainTabsHolder.postDelayed({ IttcTabBadges.leadsChanged() }, 1500L)
                    }
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount <= 1)
        binding.ittcTabsDivider.beGoneIf(binding.mainTabsHolder.tabCount <= 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    private fun getTabIcon(kind: Int): Drawable =
        resources.getColoredDrawableWithColor(getTabIconId(kind, false), getProperTextColor())

    private fun getTabLabel(kind: Int): String = getString(
        when (kind) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            else -> R.string.ittc_leads_tab
        }
    )

    // ------------------------------------------------------------------ 🎓 ITTC 3.8: tab badges

    /** Call History is (was) on screen since it was last chosen */
    private var ittcRecentsShown = false
    private var ittcMissed = 0
    private val ittcBadgesListener: () -> Unit = { if (!isFinishing && !isDestroyed) ittcShowBadges() }

    /** Call History looked at: its missed calls are seen */
    private fun ittcRecentsSeen() {
        config.ittcRecentsSeenAt = System.currentTimeMillis()
        ittcMissed = 0
        ittcShowBadges()
    }

    /** The counts again: leads due (from the list already read), missed calls (a light count, off the main thread) */
    private fun ittcRefreshBadges() {
        if (!IttcDisclosure.agreed(this)) return
        IttcTabBadges.leadsChanged()
        ittcShowBadges()
        if (positionOf(TAB_CALL_HISTORY) < 0) return
        if (currentKind() == TAB_CALL_HISTORY && ittcRecentsShown) {
            ittcRecentsSeen()
            return
        }
        val app = applicationContext
        ensureBackgroundThread {
            val n = IttcTabBadges.missedNotSeen(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // (Call History opened meanwhile: they are seen)
                ittcMissed = if (currentKind() == TAB_CALL_HISTORY && ittcRecentsShown) 0 else n
                ittcShowBadges()
            }
        }
    }

    private fun ittcShowBadges() {
        ittcSetBadge(TAB_CALL_HISTORY, ittcMissed)
        ittcSetBadge(ITTC_TAB_LEADS, IttcTabBadges.leadsDue)
    }

    /** A tab's small red count (99+; none at 0), said with the tab's name by TalkBack */
    private fun ittcSetBadge(kind: Int, count: Int) {
        val tab = binding.mainTabsHolder.getTabAt(positionOf(kind).takeIf { it >= 0 } ?: return) ?: return
        val badge = tab.customView?.findViewById<TextView>(R.id.ittc_tab_badge) ?: return
        val n = count.coerceAtLeast(0)
        val red = IttcTheme.of(this).dangerStrong
        if (badge.tag != red) {
            badge.background = IttcTheme.rounded(this, red, 999f)
            badge.setTextColor(red.getContrastColor())
            badge.tag = red
        }
        val text = if (n > 99) "99+" else n.toString()
        if (badge.text.toString() != text) badge.text = text
        IttcUi.fadeIf(badge, n > 0)
        val label = getTabLabel(kind)
        tab.setContentDescription(
            when {
                n <= 0 -> label
                kind == TAB_CALL_HISTORY -> resources.getQuantityString(R.plurals.ittc_v38_tab_missed, n, label, n)
                else -> resources.getQuantityString(R.plurals.ittc_v38_tab_due, n, label, n)
            }
        )
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity, tabKinds)
                viewPager.currentItem = if (openLastTab) positionOf(config.ittcLastTabKind).coerceAtLeast(0) else getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                }
            } else {
                refreshFragments()
            }
        }
    }

    private fun launchDialpad() {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    fun refreshFragments() {
        cacheContacts()
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
        getLeadsFragment()?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> = tabKinds.mapTo(ArrayList<MyViewPagerFragment<*>?>()) { fragmentOf(it) }

    private fun fragmentOf(kind: Int): MyViewPagerFragment<*>? = when (kind) {
        TAB_CONTACTS -> getContactsFragment()
        TAB_FAVORITES -> getFavoritesFragment()
        TAB_CALL_HISTORY -> getRecentsFragment()
        ITTC_TAB_LEADS -> getLeadsFragment()
        else -> null
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = currentKind()?.let { fragmentOf(it) }

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getLeadsFragment(): IttcLeadsFragment? = findViewById(R.id.ittc_leads_fragment)

    /** The tab to open: the one set in Settings (or the last used), the first one when it is hidden */
    private fun getDefaultTab(): Int {
        val kind = when (val wanted = config.defaultTab) {
            TAB_LAST_USED -> config.ittcLastTabKind
            else -> wanted
        }
        return positionOf(kind).coerceAtLeast(0)
    }

    // 🎓 ITTC 3.2: direct mode on every return to the main screen (all off the main thread), and
    // the "update from Google Play" banner when the admin needs a newer version
    private fun ittcDirectResume() {
        val app = applicationContext
        ensureBackgroundThread {
            if (!IttcDirect.isSignedIn(app)) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.ittcUpdateBanner.beGone()
                        showIttcTopBar(false, 0)
                        ittcPanel.showAccount(false, null, null)
                    }
                }
                return@ensureBackgroundThread
            }
            // 🎓 ITTC 3.5: 🔍 and 🔔 (the last unread count at once, a fresh one in a moment)
            val unread = try {
                IttcNotify.unreadCached(app)
            } catch (_: Throwable) {
                0
            }
            // 🎓 ITTC 3.12: who is signed in, for the side panel's header
            val meName = try {
                IttcDirect.me(app)?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            } catch (_: Throwable) {
                null
            }
            val meEmail = try {
                IttcDirect.email(app)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showIttcTopBar(true, unread)
                ittcPanel.showAccount(true, meName, meEmail)
                if (ittcResumed && !ittcNotifyStarted) {
                    ittcNotifyStarted = true
                    try {
                        // (a new notification while the app is open: the badge at once)
                        IttcNotify.addListener(ittcUnreadListener)
                        IttcNotify.start(app)
                    } catch (_: Throwable) {
                    }
                }
            }
            try {
                IttcNotify.refreshAsync(app) { n ->
                    runOnUiThread { if (!isFinishing && !isDestroyed) ittcUnreadChanged(n) }
                }
            } catch (_: Throwable) {
            }
            IttcDirect.kick(app)
            IttcDirect.refreshAsync(app)
            IttcDirect.syncTrackerFlag(app)
            val update = IttcDirect.updateNeeded(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.ittcUpdateBanner.beVisibleIf(update)
            }
        }
    }

    // 🎓 ITTC 3.5: in the top bar, when signed in to ITTC: 🔍 search leads and 🔔 notifications
    // (with the unread count); both hidden otherwise
    private var ittcSearchItem: MenuItem? = null
    private var ittcBellItem: MenuItem? = null
    private var ittcBellIcon: ImageView? = null
    private var ittcBellBadge: TextView? = null
    private var ittcResumed = false
    private var ittcNotifyStarted = false
    private val ittcUnreadListener: (Int) -> Unit = { n -> if (!isFinishing && !isDestroyed) ittcUnreadChanged(n) }

    /** 🎓 3.12: kept for the way back from Personal mode (the bell is hidden meanwhile) */
    private fun ittcUnreadChanged(n: Int) {
        ittcUnread = n
        showIttcBadge(if (IttcMode.isPersonal(this)) 0 else n)
    }

    private fun setupIttcTopBar() {
        val menu = binding.mainMenu.requireToolbar().menu
        ittcSearchItem = menu.add(Menu.NONE, R.id.ittc_a_menu_search_leads, Menu.NONE, R.string.ittc_a_search_leads).apply {
            setIcon(R.drawable.ic_search_vector)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
        }

        val size = resources.getDimensionPixelSize(R.dimen.ittc_touch)
        val bell = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(size, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            minimumHeight = size
            contentDescription = getString(R.string.ittc_a_notifications)
            isClickable = true
            isFocusable = true
            setBackgroundResource(org.fossify.commons.R.drawable.ripple_all_corners)
            setOnClickListener { IttcInboxActivity.open(this@MainActivity) }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bell_vector)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setColorFilter(getProperBackgroundColor().getContrastColor())
        }
        val iconSize = (24 * resources.displayMetrics.density).toInt()
        bell.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        val p = org.fossify.phone.helpers.IttcTheme.of(this)
        val badge = TextView(this).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(p.dangerStrong.getContrastColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val dp = resources.displayMetrics.density
            minWidth = (16 * dp).toInt()
            minHeight = (16 * dp).toInt()
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            background = org.fossify.phone.helpers.IttcTheme.rounded(this@MainActivity, p.dangerStrong, 999f)
        }
        bell.addView(
            badge,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                val dp = resources.displayMetrics.density
                topMargin = (2 * dp).toInt()
                marginEnd = (2 * dp).toInt()
            },
        )
        ittcBellIcon = icon
        ittcBellBadge = badge
        ittcBellItem = menu.add(Menu.NONE, R.id.ittc_a_menu_inbox, Menu.NONE, R.string.ittc_a_notifications).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = bell
            isVisible = false
        }
    }

    // 🎓 ITTC 3.12: 💼 / 🏠 in the top bar's corner (signed in only): a tap switches ITTC / Personal
    // mode, a long press opens the mode's settings (when the app has that screen)
    private fun setupIttcModeButton() {
        val size = resources.getDimensionPixelSize(R.dimen.ittc_touch)
        val button = TextView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(size, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            minimumHeight = size
            gravity = Gravity.CENTER
            // (dp: an icon in a fixed box, whatever the text size)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            setBackgroundResource(org.fossify.commons.R.drawable.ripple_all_corners)
            setOnClickListener { toggleIttcMode() }
            setOnLongClickListener {
                openIttcModeSettings()
                true
            }
        }
        ittcModeButton = button
        ittcModeItem = binding.mainMenu.requireToolbar().menu.add(Menu.NONE, R.id.ittc_a_menu_mode, Menu.NONE, R.string.ittc_sp_mode).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = button
            isVisible = false
        }
        showIttcMode()
    }

    private fun showIttcMode() {
        val button = ittcModeButton ?: return
        val personal = IttcMode.isPersonal(this)
        button.text = getString(if (personal) R.string.ittc_mode_icon_personal else R.string.ittc_mode_icon_ittc)
        button.contentDescription = getString(if (personal) R.string.ittc_mode_button_personal else R.string.ittc_mode_button_ittc)
    }

    private fun toggleIttcMode() {
        val personal = !IttcMode.isPersonal(this)
        // (🎓 3.12: the admin's limits: a toast says why not)
        if (!IttcMode.request(this, personal)) return
        showIttcMode()
        toast(if (personal) R.string.ittc_mode_personal_toast else R.string.ittc_mode_ittc_toast)
    }

    /** 🎓 ITTC 3.12: the mode's settings (office hours, office SIM, a lead's call in Personal mode) */
    fun openIttcModeSettings() {
        IttcModeActivity.open(this)
    }

    private var ittcUnread = 0

    private fun showIttcTopBar(signedIn: Boolean, unread: Int) {
        ittcSignedInTop = signedIn
        ittcUnread = unread
        ittcModeItem?.isVisible = signedIn
        if (signedIn) showIttcMode()
        // (🎓 3.12: 🔍 and 🔔 only in ITTC mode)
        val ittc = signedIn && !IttcMode.isPersonal(this)
        ittcSearchItem?.isVisible = ittc
        ittcSearchForTab(currentKind())
        ittcBellItem?.isVisible = ittc
        showIttcBadge(if (ittc) unread else 0)
    }

    /**
     * 🎓 ITTC 3.12: ITTC / Personal mode changed (here, in the side panel, the tile, the office
     * hours, the admin): the button, 🔍 / 🔔, the tabs (Leads only in ITTC mode), the lead pills in
     * Call History and Contacts, the badges. Never in the way of the phone: an error is recorded only.
     */
    private fun ittcModeChanged() {
        try {
            showIttcMode()
            showIttcTopBar(ittcSignedInTop, ittcUnread)
            // (the tabs only while on screen: in the background — the office hours' alarm — on the return)
            if (ittcWantedTabs() != tabKinds) {
                if (ittcResumed) ittcRebuildTabs() else ittcTabsStale = true
            }
            getRecentsFragment()?.ittcModeChanged()
            getContactsFragment()?.ittcModeChanged()
            IttcTabBadges.leadsChanged()
            ittcShowBadges()
        } catch (e: Throwable) {
            org.fossify.phone.helpers.IttcCrash.record(this, e, "Main screen: mode change")
        }
    }

    /**
     * 🎓 ITTC 3.12: the tabs made again for the mode (from the same ordered list, ittcVisibleTabs):
     * the pages too when they exist, on the same kind of tab — Call History when it was Leads.
     */
    private var ittcTabsStale = false

    private fun ittcRebuildTabs() {
        ittcTabsStale = false
        val keep = currentKind()
        val hadPages = binding.viewPager.adapter != null
        if (binding.mainMenu.isSearchOpen) binding.mainMenu.closeSearch()
        getAllFragments().forEach { it?.finishActMode() }
        ittcLastTab = -1
        setupTabs()
        setupTabColors()
        if (hadPages) {
            val target = positionOf(keep ?: TAB_CALL_HISTORY).takeIf { it >= 0 }
                ?: positionOf(TAB_CALL_HISTORY).takeIf { it >= 0 } ?: 0
            binding.viewPager.adapter = ViewPagerAdapter(this, tabKinds)
            binding.viewPager.currentItem = target
            binding.mainTabsHolder.getTabAt(target)?.select()
            binding.viewPager.onGlobalLayout {
                if (!isFinishing && !isDestroyed) refreshFragments()
            }
        }
        refreshMenuItems()
        ittcShowBadges()
    }

    private fun showIttcBadge(unread: Int) {
        val badge = ittcBellBadge ?: return
        badge.text = if (unread > 99) "99+" else unread.toString()
        badge.beVisibleIf(unread > 0)
        ittcBellItem?.actionView?.contentDescription = if (unread > 0) {
            getString(R.string.ittc_a_notifications_unread, unread)
        } else {
            getString(R.string.ittc_a_notifications)
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    // 🎓 ITTC Phone's own About (version, updates, licence and source, credits)
    private fun launchAbout() {
        startActivity(Intent(applicationContext, IttcAboutActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getRecentsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    fun cacheContacts() {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(this).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
                cachedContactsVersion++
            } catch (ignored: Exception) {
            }
            // 🎓 ITTC 3.6: for the dial pad's search (its own copy, never changed after)
            ittcDialContacts = ArrayList(contacts)
            runOnUiThread { if (!isDestroyed && ::ittcDialpad.isInitialized) ittcDialpad.contactsChanged() }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        if (IttcDisclosure.agreed(this)) {
            getRecentsFragment()?.refreshItems()
            ittcRefreshBadges()
            // (the system writes a missed call to the log a moment after the call ends: count again)
            binding.mainTabsHolder.postDelayed({ if (!isFinishing && !isDestroyed) ittcRefreshBadges() }, 1500L)
        }
    }
}
