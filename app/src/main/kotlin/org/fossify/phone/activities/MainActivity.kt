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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager
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
import org.fossify.phone.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.tabsList
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

    // 🎓 ITTC 3.6: the dial pad on the main screen; the contacts it searches (a list never changed
    // after it is set, safe to read off the main thread); a tab change counts only after a touch
    // (the first tab is chosen by code at start)
    private lateinit var ittcDialpad: IttcMainDialpad

    @Volatile
    private var ittcDialContacts: List<Contact> = emptyList()
    private var ittcUserTouched = false
    private var ittcLastTab = -1
    private var ittcStoredOpenWithDialpad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        org.fossify.phone.helpers.IttcCrash.install(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        // 🎓 ITTC: the reason the app stopped last time, once (to report it)
        if (savedInstanceState == null) org.fossify.phone.helpers.IttcCrash.showIfAny(this)
        setupOptionsMenu()
        setupIttcTopBar()
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
        Contact.sorting = config.sorting
        setupIttcDialpad(savedInstanceState)
        storedFontSize = config.fontSize
    }

    override fun onResume() {
        super.onResume()
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateMenuColors()
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
            if (IttcDisclosure.agreed(this)) getRecentsFragment()?.refreshItems()
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
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
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
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment()
            val contactsTab = currentFragment != getRecentsFragment() && currentFragment != getLeadsFragment()
            findItem(R.id.sort).isVisible = contactsTab
            findItem(R.id.filter).isVisible = contactsTab
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment()
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment() && config.viewType == VIEW_TYPE_GRID
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
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
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        ittcStyleTab(activeView, true, getSelectedTabDrawableIds()[binding.viewPager.currentItem])

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            ittcStyleTab(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        // 🎓 ITTC 3.6: a flat surface bar with a thin line on top (no shadow)
        val p = org.fossify.phone.helpers.IttcTheme.of(this)
        binding.mainTabsHolder.setBackgroundColor(p.surface)
        binding.ittcTabsDivider.setBackgroundColor(if (p.dark) p.divider else p.outline)
        binding.ittcTabsDivider.beVisibleIf(binding.mainTabsHolder.isVisible())
    }

    /**
     * 🎓 ITTC 3.6: a tab as in Material 3 — chosen: the filled icon on a soft pill and a bold label,
     * in the app's accent; the others: the outlined icon and label, muted
     */
    private fun ittcStyleTab(view: View?, active: Boolean, drawableId: Int) {
        view ?: return
        val p = org.fossify.phone.helpers.IttcTheme.of(this)
        val accent = getProperPrimaryColor()
        val color = if (active) accent else p.muted
        view.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
            setImageResource(drawableId)
            setColorFilter(color)
        }
        view.findViewById<TextView>(R.id.tab_item_label)?.apply {
            setTextColor(color)
            setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        view.findViewById<View>(R.id.ittc_tab_pill)?.background = if (active) ittcTabPill(accent.adjustAlpha(0.16f)) else null
    }

    // 🎓 ITTC: the chosen tab's pill, made once per colour (only one tab shows it at a time)
    private var ittcTabPillDrawable: android.graphics.drawable.GradientDrawable? = null
    private var ittcTabPillColor = 0

    private fun ittcTabPill(color: Int): android.graphics.drawable.GradientDrawable {
        val cached = ittcTabPillDrawable
        if (cached != null && ittcTabPillColor == color) return cached
        return org.fossify.phone.helpers.IttcTheme.rounded(this, color, 999f).also {
            ittcTabPillDrawable = it
            ittcTabPillColor = color
        }
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): List<Int> {
        val showTabs = config.showTabs
        val icons = mutableListOf<Int>()

        if (showTabs and TAB_CONTACTS != 0) {
            icons.add(R.drawable.ic_person_vector)
        }

        if (showTabs and TAB_FAVORITES != 0) {
            icons.add(R.drawable.ic_star_vector)
        }

        if (showTabs and TAB_CALL_HISTORY != 0) {
            icons.add(R.drawable.ic_clock_filled_vector)
        }

        icons.add(R.drawable.ittc_ic_leads)
        return icons
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {
        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

        if (showTabs and TAB_CONTACTS != 0) {
            icons.add(R.drawable.ic_person_outline_vector)
        }

        if (showTabs and TAB_FAVORITES != 0) {
            icons.add(R.drawable.ic_star_outline_vector)
        }

        if (showTabs and TAB_CALL_HISTORY != 0) {
            icons.add(R.drawable.ic_clock_vector)
        }

        icons.add(R.drawable.ittc_ic_leads_outline)
        return icons
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
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    // (the Leads tab is the last one: Recents is just before it)
                    wantedTab = binding.mainTabsHolder.tabCount - 2
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
        binding.mainTabsHolder.removeAllTabs()
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value != 0) {
                binding.mainTabsHolder.newTab().setCustomView(R.layout.ittc_tab_item).apply {
                    customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(index))
                    customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(index)
                    AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                    binding.mainTabsHolder.addTab(this)
                }
            }
        }

        // 🎓 ITTC: the "Leads" tab, always last
        binding.mainTabsHolder.newTab().setCustomView(R.layout.ittc_tab_item).apply {
            customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(
                resources.getColoredDrawableWithColor(R.drawable.ittc_ic_leads, getProperTextColor())
            )
            customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getString(R.string.ittc_leads_tab)
            AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
            binding.mainTabsHolder.addTab(this)
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                ittcStyleTab(it.customView, false, getDeselectedTabDrawableIds()[it.position])
            },
            tabSelectedAction = {
                // 🎓 ITTC 3.6: another tab (chosen by hand, not the one picked at start) hides the dial pad
                if (ittcLastTab != -1 && ittcLastTab != it.position && ittcUserTouched && ::ittcDialpad.isInitialized) {
                    ittcDialpad.hideAll()
                }
                ittcLastTab = it.position
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                binding.viewPager.currentItem = it.position
                ittcStyleTab(it.customView, true, getSelectedTabDrawableIds()[it.position])

                // (Recents is just before the Leads tab)
                val recentsPosition = binding.mainTabsHolder.tabCount - 2
                if (it.position == recentsPosition && config.showTabs and TAB_CALL_HISTORY > 0) {
                    clearMissedCalls()
                }
                if (it.position == binding.mainTabsHolder.tabCount - 1) {
                    getLeadsFragment()?.refreshItems()
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount <= 1)
        binding.ittcTabsDivider.beGoneIf(binding.mainTabsHolder.tabCount <= 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    private fun getTabIcon(position: Int): Drawable {
        val drawableId = when (position) {
            0 -> R.drawable.ic_person_vector
            1 -> R.drawable.ic_star_vector
            else -> R.drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {
        val stringId = when (position) {
            0 -> R.string.contacts_tab
            1 -> R.string.favorites_tab
            else -> R.string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
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

    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>?>()

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(getContactsFragment())
        }

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(getFavoritesFragment())
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(getRecentsFragment())
        }

        fragments.add(getLeadsFragment())
        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getLeadsFragment(): IttcLeadsFragment? = findViewById(R.id.ittc_leads_fragment)

    private fun getDefaultTab(): Int {
        val showTabsMask = config.showTabs
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < binding.mainTabsHolder.tabCount) config.lastUsedViewPagerPage else 0
            TAB_CONTACTS -> 0
            TAB_FAVORITES -> if (showTabsMask and TAB_CONTACTS > 0) 1 else 0
            else -> {
                if (showTabsMask and TAB_CALL_HISTORY > 0) {
                    if (showTabsMask and TAB_CONTACTS > 0) {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
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
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showIttcTopBar(true, unread)
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
                    runOnUiThread { if (!isFinishing && !isDestroyed) showIttcBadge(n) }
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
    private val ittcUnreadListener: (Int) -> Unit = { n -> if (!isFinishing && !isDestroyed) showIttcBadge(n) }

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

    private fun showIttcTopBar(signedIn: Boolean, unread: Int) {
        ittcSearchItem?.isVisible = signedIn
        ittcBellItem?.isVisible = signedIn
        showIttcBadge(if (signedIn) unread else 0)
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
        ContactsHelper(this).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                // 🎓 ITTC: opened only when it is read (as Favorites does)
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
            } catch (ignored: Exception) {
            }
            // 🎓 ITTC 3.6: for the dial pad's search (its own copy, never changed after)
            ittcDialContacts = ArrayList(contacts)
            runOnUiThread { if (!isDestroyed && ::ittcDialpad.isInitialized) ittcDialpad.contactsChanged() }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        if (IttcDisclosure.agreed(this)) getRecentsFragment()?.refreshItems()
    }
}
