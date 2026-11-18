package org.fossify.phone.fragments

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.TextView
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.IttcNumberActivity
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getDayCode
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.IttcRecentsCache
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCallHistory
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)

    // 🎓 ITTC: the last list shown at once on opening; "Leads only"
    private var cacheShown = false
    private val ittcPrefs by lazy { context.getSharedPreferences("ittc", Context.MODE_PRIVATE) }
    private var leadsOnly: Boolean
        get() = ittcPrefs.getBoolean("recents_leads_only", false)
        set(value) = ittcPrefs.edit().putBoolean("recents_leads_only", value).apply()

    // 🎓 ITTC: All / Missed / Incoming / Outgoing (with "Leads only"), and one row per number
    private var recentsKind: Int
        get() = ittcPrefs.getInt(IttcCallHistory.KEY_KIND, IttcCallHistory.KIND_ALL)
        set(value) = ittcPrefs.edit().putInt(IttcCallHistory.KEY_KIND, value).apply()
    private val onePerNumber: Boolean
        get() = ittcPrefs.getBoolean(IttcCallHistory.KEY_ONE_PER_NUMBER, true)
    private var chipTextColor = 0
    private var chipPrimaryColor = 0
    private var palette: IttcTheme.Palette? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        binding.ittcRecentsLeadsOnly.apply {
            setOnClickListener {
                leadsOnly = !leadsOnly
                renderKindChips()
                val all = allRecentCalls
                ensureBackgroundThread {
                    val shown = ittcFilter(all)
                    activity?.runOnUiThread { if (searchQuery.isNullOrEmpty()) gotRecents(shown) }
                }
            }
        }

        // 🎓 ITTC: the call-kind chips
        chipTextColor = context.getProperTextColor()
        chipPrimaryColor = context.getProperPrimaryColor()
        kindChips().forEach { (kind, chip) ->
            chip.setOnClickListener {
                if (recentsKind == kind) return@setOnClickListener
                recentsKind = kind
                renderKindChips()
                val all = allRecentCalls
                ensureBackgroundThread {
                    val shown = ittcFilter(all)
                    activity?.runOnUiThread { if (searchQuery.isNullOrEmpty()) gotRecents(shown) }
                }
            }
        }
        renderKindChips()
        // 🎓 ITTC: the system's pressed ripple over the pills
        kindChips().forEach { (_, chip) -> IttcUi.ripple(chip) }
        IttcUi.ripple(binding.ittcRecentsLeadsOnly)
        // (the list fills the page whatever it holds: a change in the rows never re-measures the page)
        binding.recentsList.setHasFixedSize(true)

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        // 🎓 ITTC 3.5: the soft background the day cards sit on, pill chips
        val p = IttcTheme.of(context)
        palette = p
        setBackgroundColor(p.bg)
        chipTextColor = textColor
        chipPrimaryColor = properPrimaryColor
        renderKindChips()
        binding.recentsPlaceholder.setTextColor(p.text2)
        binding.recentsPlaceholder2.setTextColor(p.primary)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
            // rows on screen again with the new colours / settings / lead stages (no change animation here)
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        // 🎓 ITTC: the call history page deleted / blocked calls: read the log again in full
        if (IttcCallHistory.takeDirty() || invalidate) {
            allRecentCalls = emptyList()
        }

        // 🎓 ITTC: the list from last time, at once (the real one replaces it in a moment)
        if (!cacheShown && allRecentCalls.isEmpty() && context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            cacheShown = true
            ensureBackgroundThread {
                val cached = IttcRecentsCache.load(context)
                if (cached.isNotEmpty()) {
                    val shown = ittcFilter(groupCallsByDate(cached))
                    activity?.runOnUiThread {
                        if (allRecentCalls.isEmpty() && searchQuery.isNullOrEmpty()) gotRecents(shown)
                    }
                }
            }
        }

        refreshCallLog(loadAll = false) {
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true)
            }
        }
    }

    /** 🎓 ITTC 3.6: the calls read (for the main screen dial pad's matches; filter off the main thread) */
    fun ittcCallLog(): List<CallLogItem> = allRecentCalls

    override fun onSearchClosed() {
        searchQuery = null
        // (🎓 "Leads only" still applies)
        val all = allRecentCalls
        ensureBackgroundThread {
            val shown = ittcFilter(all)
            activity?.runOnUiThread {
                showOrHidePlaceholder(shown.isEmpty())
                recentsAdapter?.updateItems(shown)
            }
        }
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            prepareCallLog(recentCalls) {
                // 🎓 ITTC: one row per number in the results too (already off the main thread)
                val shown = if (onePerNumber) {
                    groupCallsByDate(IttcCallHistory.groupByNumber(it.filterIsInstance<RecentCall>()))
                } else {
                    it
                }
                activity?.runOnUiThread {
                    showOrHidePlaceholder(recentCalls.isEmpty())
                    recentsAdapter?.updateItems(shown, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        // 🎓 ITTC: the call log is asked for only as the phone app (Google Play's rule): not yet →
        // the disclosure and Android's "make it the phone app" question instead
        val act = activity ?: return
        if (!act.isDefaultDialer()) {
            org.fossify.phone.helpers.IttcDisclosure.showOnce(act) {
                val roles = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    act.getSystemService(android.app.role.RoleManager::class.java)
                } else {
                    null
                }
                if (roles != null && !roles.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                    act.toast(R.string.ittc_no_phone_role)
                } else {
                    act.launchSetDefaultDialerIntent()
                }
            }
            return
        }
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        // 🎓 ITTC: fades in (to the layout's 0.8) instead of popping up
        IttcUi.fadeIf(binding.recentsPlaceholder, show && !binding.progressIndicator.isVisible(), 0.8f)
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        binding.progressIndicator.hide()
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    itemDelete = { deleted ->
                        // 🎓 ITTC: one row per number: every call of those numbers went
                        val keys = if (onePerNumber) {
                            deleted.map { IttcCallHistory.key(it.phoneNumber) }.filter { it.isNotEmpty() }.toHashSet()
                        } else {
                            emptySet<String>()
                        }
                        allRecentCalls = allRecentCalls.filter {
                            it !in deleted && !(keys.isNotEmpty() && it is RecentCall && IttcCallHistory.key(it.phoneNumber) in keys)
                        }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        // 🎓 ITTC 3.5: a tap calls back at once (as Fossify does); → opens the history
                        activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                    },
                    historyClick = { recentCall ->
                        IttcNumberActivity.open(
                            context = context,
                            number = recentCall.phoneNumber,
                            name = recentCall.name.takeIf { name -> name != recentCall.phoneNumber }
                        )
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startContactDetailsIntent(contact)
                        } else {
                            activity?.startAddContactIntent(recentCall.phoneNumber)
                        }
                    }
                )

                binding.recentsList.adapter = recentsAdapter
                // 🎓 ITTC: a row drawn again (a lead came in, the corners) changes in place, no cross-fade flicker
                (binding.recentsList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            // 🎓 ITTC: kept for the next opening
            if (!loadAll) IttcRecentsCache.save(context, it.filterIsInstance<RecentCall>())
            if (searchQuery.isNullOrEmpty()) {
                val shown = ittcFilter(it)
                activity?.runOnUiThread { gotRecents(shown) }
            } else {
                updateSearchResult()
            }

            callback?.invoke()
        }
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<CallLogItem>) -> Unit) {
        val queryCount = if (loadAll) Int.MAX_VALUE else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<CallLogItem>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        // 🎓 ITTC: the contacts the app already loaded, not a second full load
        val loaded = (activity as? MainActivity)?.cachedContacts?.takeIf { it.isNotEmpty() }?.let { ArrayList(it) }
        val withContacts: ((ArrayList<Contact>) -> Unit) -> Unit = { use ->
            if (loaded != null) ensureBackgroundThread { use(loaded) }
            else ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { use(it) }
        }
        withContacts { contacts ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                val updatedCalls = updateNamesIfEmpty(
                    calls = maybeFilterPrivateCalls(calls, privateContacts),
                    contacts = contacts,
                    privateContacts = privateContacts
                )

                callback(
                    groupCallsByDate(updatedCalls)
                )
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            // (a set: one look-up per call, not a walk through every number)
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.mapTo(HashSet()) { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>, privateContacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        // 🎓 ITTC: each contact by its first number (the first contact wins, as the walk through the
        // list found it), made once and only when a call needs a name
        val byFirstNumber by lazy(LazyThreadSafetyMode.NONE) {
            val map = HashMap<String, Contact>()
            contacts.forEach { if (it.phoneNumbers.isNotEmpty()) map.putIfAbsent(it.phoneNumbers.first().normalizedNumber, it) }
            map
        }
        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = byFirstNumber[call.phoneNumber]

                when {
                    privateContact != null -> withUpdatedName(call = call, name = privateContact.getNameToDisplay())
                    contact != null -> withUpdatedName(call = call, name = contact.getNameToDisplay())
                    else -> call
                }
            } else {
                call
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    /**
     * 🎓 ITTC: "Leads only" on: just the calls with a lead's number; the call-kind chip; one row per
     * number (off the main thread)
     */
    private fun ittcFilter(items: List<CallLogItem>): List<CallLogItem> {
        val kind = recentsKind
        val perNumber = onePerNumber
        if (!leadsOnly && kind == IttcCallHistory.KIND_ALL && !perNumber) return items
        var calls = items.filterIsInstance<RecentCall>()
        if (leadsOnly) {
            val leads = IttcBridge.leadsFor(context, calls.map { it.phoneNumber })
            calls = calls.filter { it.phoneNumber in leads }
        }

        return if (perNumber) {
            groupCallsByDate(IttcCallHistory.groupByNumber(calls, kind))
        } else {
            // (a "subsequent calls" group goes by its newest call, as its icon does)
            groupCallsByDate(calls.filter { IttcCallHistory.matchesKind(it.type, kind) })
        }
    }

    /** 🎓 ITTC */
    private fun kindChips(): List<Pair<Int, TextView>> = listOf(
        IttcCallHistory.KIND_ALL to binding.ittcRecentsKindAll,
        IttcCallHistory.KIND_MISSED to binding.ittcRecentsKindMissed,
        IttcCallHistory.KIND_INCOMING to binding.ittcRecentsKindIncoming,
        IttcCallHistory.KIND_OUTGOING to binding.ittcRecentsKindOutgoing,
    )

    /**
     * 🎓 ITTC 3.5: pill chips as in the Leads tab — the chosen one dark, the others outlined;
     * "Missed" red, "Leads" (only the calls with a lead) at the end
     */
    private fun renderKindChips() {
        val p = palette ?: IttcTheme.of(context).also { palette = it }
        val kind = recentsKind
        kindChips().forEach { (k, chip) ->
            styleChip(chip, k == kind, k == IttcCallHistory.KIND_MISSED, p)
        }
        styleChip(binding.ittcRecentsLeadsOnly, leadsOnly, false, p)
    }

    private fun styleChip(chip: TextView, on: Boolean, danger: Boolean, p: IttcTheme.Palette) {
        when {
            on && danger -> IttcTheme.pill(chip, p.danger, p.surface)
            on -> IttcTheme.pill(chip, p.text, p.surface)
            danger -> IttcTheme.pill(chip, p.dangerSoft, p.danger, p.danger.adjustAlpha(0.3f))
            else -> IttcTheme.pill(chip, p.surface, p.text, p.outline)
        }
        chip.setTypeface(null, if (on || danger) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        // (one date row per day, even if the list is ever out of order: two rows of one day
        // would have the same id)
        val seenDays = HashSet<String>()
        // 🎓 ITTC 3.5: Today · Yesterday · Earlier (every older day in one group)
        val now = System.currentTimeMillis()
        val todayCode = now.getDayCode()
        val yesterdayCode = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis.getDayCode()
        for (call in recentCalls) {
            val realDayCode = call.dayCode
            val currentDayCode = if (realDayCode == todayCode || realDayCode == yesterdayCode) realDayCode else RecentCallsAdapter.EARLIER_DAY_CODE
            if (currentDayCode != lastDayCode && seenDays.add(currentDayCode)) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return (activity as MainActivity).cachedContacts.find { it.name == recentCall.name && it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
