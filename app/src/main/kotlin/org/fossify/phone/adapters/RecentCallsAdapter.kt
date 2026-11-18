package org.fossify.phone.adapters

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.provider.CallLog.Calls
import android.text.SpannableString
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.adjustForContrast
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.commons.extensions.formatTime
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.highlightTextPart
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.commons.models.RecyclerSelectionPayload
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ItemRecentCallBinding
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCallHistory
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import org.fossify.phone.databinding.ItemRecentsDateBinding
import org.fossify.phone.dialogs.ShowGroupedCallsDialog
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.callContactWithSimWithConfirmationCheck
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getDayCode
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall
import org.joda.time.DateTime
import java.util.Locale

class RecentCallsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    private val refreshItemsListener: RefreshItemsListener?,
    private val showOverflowMenu: Boolean,
    private val itemDelete: (List<RecentCall>) -> Unit = {},
    itemClick: (Any) -> Unit,
    val profileIconClick: ((Any) -> Unit)? = null,
    // 🎓 ITTC 3.5: the row's → button: the number's call history (a tap on the row calls)
    private val historyClick: ((RecentCall) -> Unit)? = null,
) : MyRecyclerViewListAdapter<CallLogItem>(activity, recyclerView, RecentCallsDiffCallback(), itemClick) {

    private lateinit var outgoingCallIcon: Drawable
    private lateinit var incomingCallIcon: Drawable
    private lateinit var incomingMissedCallIcon: Drawable
    var fontSize: Float = activity.getTextSize()
    private val areMultipleSIMsAvailable = activity.areMultipleSIMsAvailable()
    private var missedCallColor = resources.getColor(R.color.color_missed_call)
    private var secondaryTextColor = textColor.adjustAlpha(0.6f)
    private var textToHighlight = ""
    private var phoneNumberUtilInstance: PhoneNumberUtil = PhoneNumberUtil.getInstance()
    private var phoneNumberOfflineGeocoderInstance: PhoneNumberOfflineGeocoder = PhoneNumberOfflineGeocoder.getInstance()
    private val cachedSimColors = HashMap<Pair<Int,Int>, Int>()

    // 🎓 ITTC: Recents shows one row per number (not in "Show call details", which has no listener)
    // (read with the colours in initDrawables, which runs again on every return to the screen)
    private var ittcPerNumber = false
    private var formatPhoneNumbers = false

    // 🎓 ITTC 3.5: the Recents tab's cards (the "Show call details" dialog keeps plain rows)
    private val cardMode = refreshItemsListener != null
    private var palette = IttcTheme.of(activity)
    private var labelOutgoing = ""
    private var labelIncoming = ""
    private var labelMissed = ""
    private var labelDeclined = ""
    private var labelBlocked = ""
    private var labelLead = ""
    /** (looked up once per name / stage, not on every bind) */
    private val avatarCache = HashMap<String, Avatar>()
    private val stageCache = HashMap<String, Pair<Int, Int>>()
    /** before this: "Earlier" — the row shows the date instead of the time */
    private var yesterdayStart = 0L
    private var dayEnd = 0L

    /** (a number's place abroad, by number and whether it is a contact; "" = none — worked out once) */
    private val locationCache = HashMap<String, String>()
    /** (each row's phone contact, by call id, for the current list — looked up once, not on every bind) */
    private val contactCache = HashMap<Int, Contact?>()
    private var contactCacheSize = -1

    private class Avatar(val initials: String, val bg: Int, val fg: Int)

    init {
        initDrawables()
        setupDragListener(true)
        setHasStableIds(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getActionMenuId() = R.menu.cab_recent_calls

    override fun prepareActionMode(menu: Menu) {
        val hasMultipleSIMs = activity.areMultipleSIMsAvailable()
        val selectedItems = getSelectedItems()
        val isOneItemSelected = selectedItems.size == 1
        val selectedNumber = "tel:${getSelectedPhoneNumber()}"

        menu.apply {
            findItem(R.id.cab_call_sim_1).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_call_sim_2).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_remove_default_sim).isVisible = isOneItemSelected && (activity.config.getCustomSIM(selectedNumber) ?: "") != ""

            findItem(R.id.cab_block_number).title = activity.addLockedLabelIfNeeded(R.string.block_number)
            findItem(R.id.cab_block_number).isVisible = isNougatPlus()
            findItem(R.id.cab_add_number).isVisible = isOneItemSelected
            findItem(R.id.cab_copy_number).isVisible = isOneItemSelected
            findItem(R.id.cab_show_call_details).isVisible = isOneItemSelected
            findItem(R.id.cab_view_details).isVisible = isOneItemSelected && findContactByCall(selectedItems.first()) != null
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_call_sim_1 -> callContact(true)
            R.id.cab_call_sim_2 -> callContact(false)
            R.id.cab_remove_default_sim -> removeDefaultSIM()
            R.id.cab_block_number -> tryBlocking()
            R.id.cab_add_number -> addNumberToContact()
            R.id.cab_send_sms -> sendSMS()
            R.id.cab_show_call_details -> showCallDetails()
            R.id.cab_copy_number -> copyNumber()
            R.id.cab_remove -> askConfirmRemove()
            R.id.cab_select_all -> selectAll()
            R.id.cab_view_details -> launchContactDetailsIntent(findContactByCall(getSelectedItems().first()))
        }
    }

    override fun getItemId(position: Int) = currentList[position].getItemId().toLong()

    override fun getSelectableItemCount() = currentList.filterIsInstance<RecentCall>().size

    override fun getIsItemSelectable(position: Int) = currentList.getOrNull(position) is RecentCall

    override fun getItemSelectionKey(position: Int) = currentList.getOrNull(position)?.getItemId()

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst { it.getItemId() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun getItemViewType(position: Int): Int {
        return when (currentList[position]) {
            is CallLogItem.Date -> VIEW_TYPE_DATE
            is RecentCall -> VIEW_TYPE_CALL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewHolder = when (viewType) {
            VIEW_TYPE_DATE -> RecentCallDateViewHolder(
                ItemRecentsDateBinding.inflate(layoutInflater, parent, false)
            )

            VIEW_TYPE_CALL -> RecentCallViewHolder(
                ItemRecentCallBinding.inflate(layoutInflater, parent, false)
            )

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }

        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val callRecord = currentList[position]
        when (holder) {
            is RecentCallDateViewHolder -> holder.bind(callRecord as CallLogItem.Date)
            is RecentCallViewHolder -> holder.bind(callRecord as RecentCall, position)
        }

        bindViewHolder(holder)
    }

    // 🎓 ITTC: after a new list only the corners / dividers of the rows on screen are drawn again (no full rebind, no flicker)
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        // (and a selection change only redraws the row's selected background: the base's payload)
        if (payloads.isNotEmpty() && holder is RecentCallViewHolder && payloads.all { it === PAYLOAD_CORNERS || it is RecyclerSelectionPayload }) {
            if (position in 0 until itemCount) {
                if (payloads.any { it === PAYLOAD_CORNERS }) holder.bindCorners(position)
                (payloads.lastOrNull { it is RecyclerSelectionPayload } as? RecyclerSelectionPayload)?.let { holder.bindSelected(it.selected) }
            }
            return
        }
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_CORNERS }) return
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            if (holder is RecentCallViewHolder) {
                Glide.with(activity).clear(holder.binding.itemRecentsImage)
            }
        }
    }

    override fun submitList(list: List<CallLogItem>?) {
        val layoutManager = recyclerView.layoutManager!!
        val recyclerViewState = layoutManager.onSaveInstanceState()
        contactCache.clear()
        super.submitList(list) {
            layoutManager.onRestoreInstanceState(recyclerViewState)
            // 🎓 ITTC: a row's card corners depend on its neighbours: the rows on screen again
            if (cardMode && !activity.isFinishing && !activity.isDestroyed) notifyItemRangeChanged(0, itemCount, PAYLOAD_CORNERS)
        }
    }

    fun initDrawables() {
        // 🎓 ITTC 3.5: the palette, labels and day limits again (the theme may have changed)
        palette = IttcTheme.of(activity)
        avatarCache.clear()
        stageCache.clear()
        locationCache.clear()
        contactCache.clear()
        ittcPerNumber = refreshItemsListener != null && IttcCallHistory.onePerNumber(activity)
        formatPhoneNumbers = activity.config.formatPhoneNumbers
        labelOutgoing = activity.getString(R.string.ittc_hist_outgoing)
        labelIncoming = activity.getString(R.string.ittc_hist_incoming)
        labelMissed = activity.getString(R.string.ittc_hist_missed)
        labelDeclined = activity.getString(R.string.ittc_a_declined)
        labelBlocked = activity.getString(R.string.ittc_a_blocked)
        labelLead = activity.getString(R.string.ittc_a_lead)
        dayEnd = 0L
        val theme = activity.theme
        missedCallColor = resources.getColor(R.color.color_missed_call, theme)
        secondaryTextColor = textColor.adjustAlpha(0.6f)

        val outgoingCallColor = resources.getColor(R.color.color_outgoing_call, theme)
        val incomingCallColor = resources.getColor(R.color.color_incoming_call, theme)
        outgoingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, outgoingCallColor)
        incomingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, incomingCallColor)
        incomingMissedCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, missedCallColor)
    }

    private fun callContact(useSimOne: Boolean) {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        val name = getSelectedName() ?: return

        activity.callContactWithSimWithConfirmationCheck(phoneNumber, name, useSimOne)
    }

    private fun callContact() {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        val name = getSelectedName() ?: return

        (activity as SimpleActivity).startCallWithConfirmationCheck(phoneNumber, name)
    }

    private fun removeDefaultSIM() {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        activity.config.removeCustomSIM(phoneNumber)
        finishActMode()
    }

    private fun tryBlocking() {
        if (activity.isOrWasThankYouInstalled()) {
            askConfirmBlock()
        } else {
            FeatureLockedDialog(activity) { }
        }
    }

    private fun askConfirmBlock() {
        val numbers = TextUtils.join(", ", getSelectedItems().distinctBy { it.phoneNumber }.map { it.phoneNumber })
        val baseString = R.string.block_confirmation
        val question = String.format(resources.getString(baseString), numbers)

        ConfirmationDialog(activity, question) {
            blockNumbers()
        }
    }

    private fun blockNumbers() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val callsToBlock = getSelectedItems()
        ensureBackgroundThread {
            callsToBlock.map { it.phoneNumber }.forEach { number ->
                activity.addBlockedNumber(number)
            }

            val recentCalls = currentList.toMutableList().also { it.removeAll(callsToBlock) }
            activity.runOnUiThread {
                submitList(recentCalls)
                finishActMode()
            }
        }
    }

    private fun addNumberToContact() {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        activity.startAddContactIntent(phoneNumber)
    }

    private fun sendSMS() {
        val numbers = getSelectedItems().map { it.phoneNumber }
        val recipient = TextUtils.join(";", numbers)
        activity.launchSendSMSIntent(recipient)
    }

    private fun showCallDetails() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        val recentCalls = recentCall.groupedCalls ?: listOf(recentCall)
        ShowGroupedCallsDialog(activity, recentCalls)
    }

    private fun copyNumber() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(recentCall.phoneNumber)
        finishActMode()
    }

    private fun askConfirmRemove() {
        ConfirmationDialog(activity, activity.getString(R.string.remove_confirmation)) {
            activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
                removeRecents()
            }
        }
    }

    private fun removeRecents() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val callsToRemove = getSelectedItems()
        val idsToRemove = ArrayList<Int>()
        callsToRemove.forEach {
            idsToRemove.add(it.id)
            it.groupedCalls?.mapTo(idsToRemove) { call -> call.id }
        }

        // 🎓 ITTC: one row per number: every call of the selected numbers, also those not loaded yet —
        // with a Missed / Incoming / Outgoing chip on, only the calls of that kind (what the row stands for)
        val perNumber = ittcPerNumber
        val kind = if (perNumber && textToHighlight.isEmpty()) IttcCallHistory.kind(activity) else IttcCallHistory.KIND_ALL
        ensureBackgroundThread {
            if (perNumber) {
                val known = callsToRemove.filter { !it.isUnknownNumber }
                if (kind == IttcCallHistory.KIND_ALL) {
                    idsToRemove.addAll(IttcCallHistory.idsFor(activity, known.map { it.phoneNumber }))
                } else {
                    // (a row is of that kind when all its calls are: the filtered list; else, e.g. a
                    // search result, the number's every call as before)
                    val (ofKind, mixed) = known.partition { row ->
                        (row.groupedCalls ?: listOf(row)).all { IttcCallHistory.matchesKind(it.type, kind) }
                    }
                    if (ofKind.isNotEmpty()) {
                        idsToRemove.addAll(IttcCallHistory.idsFor(activity, ofKind.map { it.phoneNumber }, kind))
                    }
                    if (mixed.isNotEmpty()) {
                        idsToRemove.addAll(IttcCallHistory.idsFor(activity, mixed.map { it.phoneNumber }))
                    }
                }
            }

            RecentsHelper(activity).removeRecentCalls(idsToRemove.distinct()) {
                itemDelete(callsToRemove)
                val recentCalls = currentList.toMutableList().also { it.removeAll(callsToRemove) }
                activity.runOnUiThread {
                    refreshItemsListener?.refreshItems()
                    submitList(recentCalls)
                    finishActMode()
                }
            }
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        val contacts = (activity as MainActivity).cachedContacts
        // (the app's contacts were loaded again since: look them up afresh)
        if (contacts.size != contactCacheSize) {
            contactCache.clear()
            contactCacheSize = contacts.size
        }
        if (contactCache.containsKey(recentCall.id)) return contactCache[recentCall.id]
        val contact = contacts.find { it.name == recentCall.name && it.doesHavePhoneNumber(recentCall.phoneNumber) }
        contactCache[recentCall.id] = contact
        return contact
    }

    private fun launchContactDetailsIntent(contact: Contact?) {
        if (contact != null) {
            activity.startContactDetailsIntent(contact)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: List<CallLogItem>, highlightText: String = "") {
        if (textToHighlight != highlightText) {
            textToHighlight = highlightText
            contactCache.clear()
            submitList(newItems) {
                recyclerView.post {
                    notifyDataSetChanged()
                }
            }
            finishActMode()
        } else {
            submitList(newItems)
        }
    }

    private fun getSelectedItems() = currentList.filterIsInstance<RecentCall>()
        .filter { selectedKeys.contains(it.getItemId()) }

    private fun getSelectedPhoneNumber() = getSelectedItems().firstOrNull()?.phoneNumber

    private fun getSelectedName() = getSelectedItems().firstOrNull()?.name

    private fun showPopupMenu(view: View, call: RecentCall) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)
        val contact = findContactByCall(call)
        val selectedNumber = "tel:${call.phoneNumber}"

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(R.menu.menu_recent_item_options)
            menu.apply {
                val areMultipleSIMsAvailable = activity.areMultipleSIMsAvailable()
                findItem(R.id.cab_call).isVisible = !areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_call_sim_1).isVisible = areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_call_sim_2).isVisible = areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_send_sms).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_view_details).isVisible = contact != null && !call.isUnknownNumber
                findItem(R.id.cab_add_number).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_copy_number).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_show_call_details).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_block_number).title = activity.addLockedLabelIfNeeded(R.string.block_number)
                findItem(R.id.cab_block_number).isVisible = isNougatPlus() && !call.isUnknownNumber
                findItem(R.id.cab_remove_default_sim).isVisible = (activity.config.getCustomSIM(selectedNumber) ?: "") != "" && !call.isUnknownNumber
            }

            setOnMenuItemClickListener { item ->
                val callId = call.id
                when (item.itemId) {
                    R.id.cab_call -> {
                        executeItemMenuOperation(callId) {
                            callContact()
                        }
                    }

                    R.id.cab_call_sim_1 -> {
                        executeItemMenuOperation(callId) {
                            callContact(true)
                        }
                    }

                    R.id.cab_call_sim_2 -> {
                        executeItemMenuOperation(callId) {
                            callContact(false)
                        }
                    }

                    R.id.cab_send_sms -> {
                        executeItemMenuOperation(callId) {
                            sendSMS()
                        }
                    }

                    R.id.cab_view_details -> {
                        executeItemMenuOperation(callId) {
                            launchContactDetailsIntent(contact)
                        }
                    }

                    R.id.cab_add_number -> {
                        executeItemMenuOperation(callId) {
                            addNumberToContact()
                        }
                    }

                    R.id.cab_show_call_details -> {
                        executeItemMenuOperation(callId) {
                            showCallDetails()
                        }
                    }

                    R.id.cab_block_number -> {
                        selectedKeys.add(callId)
                        tryBlocking()
                    }

                    R.id.cab_remove -> {
                        selectedKeys.add(callId)
                        askConfirmRemove()
                    }

                    R.id.cab_copy_number -> {
                        executeItemMenuOperation(callId) {
                            copyNumber()
                        }
                    }

                    R.id.cab_remove_default_sim -> {
                        executeItemMenuOperation(callId) {
                            removeDefaultSIM()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(callId: Int, callback: () -> Unit) {
        selectedKeys.add(callId)
        callback()
        selectedKeys.remove(callId)
    }

    // 🎓 ITTC: the leads for the rows came in (once per batch)
    private fun ittcRefresh() {
        if (!activity.isFinishing && !activity.isDestroyed) notifyItemRangeChanged(0, itemCount)
    }

    /** 🎓 ITTC: today's / yesterday's limits, worked out again once a day (not on every bind) */
    private fun checkDay() {
        val now = System.currentTimeMillis()
        if (now < dayEnd) return
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        dayEnd = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, -2)
        yesterdayStart = cal.timeInMillis
    }

    /** 🎓 ITTC: a cheap "Rahim Hasan" → "RH" (no regex: Recents may bind thousands of rows) */
    private fun avatarFor(name: String): Avatar = avatarCache.getOrPut(name) {
        var first = ' '
        var second = ' '
        var atWordStart = true
        for (ch in name) {
            if (ch.isWhitespace()) {
                atWordStart = true
                continue
            }
            if (atWordStart && ch.isLetter()) {
                if (first == ' ') {
                    first = ch
                } else if (second == ' ') {
                    second = ch
                    break
                }
            }
            atWordStart = false
        }
        val initials = when {
            first == ' ' -> "#"
            second == ' ' -> name.trim().takeWhile { !it.isWhitespace() }.take(2).uppercase()
            else -> "$first$second".uppercase()
        }
        val (bg, fg) = IttcTheme.avatarColors(activity, name)
        Avatar(initials, bg, fg)
    }

    private fun stageColorsFor(stage: String): Pair<Int, Int> = stageCache.getOrPut(stage) { IttcTheme.stageColors(activity, stage) }

    private inner class RecentCallViewHolder(val binding: ItemRecentCallBinding) : ViewHolder(binding.root) {
        // 🎓 ITTC 3.5: this row's shapes, made once; a bind only changes their colours and corners
        private val card = GradientDrawable()
        private val avatarBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val pillBg = GradientDrawable().apply { cornerRadius = IttcTheme.dp(activity, 999f).toFloat() }
        private val barBg = GradientDrawable().apply { cornerRadius = IttcTheme.dp(activity, 2f).toFloat() }
        private val historyBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val photoPlaceholder = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private var corners: FloatArray? = null
        private var cardColor = 0
        private var hasPhoto = false

        init {
            binding.itemRecentsAvatar.background = avatarBg
            binding.itemRecentsPill.background = pillBg
            binding.itemRecentsBar.background = barBg
            // (the 36 dp circle inside the 44 dp touch area; the pressed ripple keeps to the circle)
            binding.itemRecentsHistory.background = InsetDrawable(historyBg, IttcTheme.dp(activity, 4f))
            IttcUi.ripple(binding.itemRecentsHistory)
            if (cardMode) {
                binding.root.background = card
            } else {
                // (the dialog: plain rows edge to edge, as before)
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    marginStart = 0
                    marginEnd = 0
                }
                binding.root.foreground = null
            }
        }

        /** The selected look only (a selection change: no full bind) */
        fun bindSelected(selected: Boolean) {
            binding.itemRecentsHolder.isSelected = selected
            if (cardMode) {
                val fill = if (selected) palette.primarySoft else palette.surface
                if (cardColor != fill) {
                    cardColor = fill
                    card.setColor(fill)
                }
            }
        }

        /** (one card per day: the first row rounded on top, the last at the bottom; a divider between) */
        fun bindCorners(position: Int) {
            if (!cardMode) return
            val list = currentList
            val first = list.getOrNull(position - 1) !is RecentCall
            val last = list.getOrNull(position + 1) !is RecentCall
            val want = cardCorners(first, last)
            if (corners !== want) {
                corners = want
                card.cornerRadii = want
            }
            binding.itemRecentsDivider.beVisibleIf(!first)
            if (!first) binding.itemRecentsDivider.setBackgroundColor(palette.divider)
        }

        fun bind(call: RecentCall, position: Int) = bindView(
            item = call,
            allowSingleClick = refreshItemsListener != null && !call.isUnknownNumber,
            allowLongClick = refreshItemsListener != null && !call.isUnknownNumber
        ) { _, _ ->
            binding.apply {
                val p = palette
                val selected = selectedKeys.contains(call.id)
                itemRecentsHolder.isSelected = selected
                if (cardMode) {
                    bindCorners(position)
                    val fill = if (selected) p.primarySoft else p.surface
                    if (cardColor != fill) {
                        cardColor = fill
                        card.setColor(fill)
                    }
                } else {
                    root.setupViewBackground(activity)
                    itemRecentsDivider.beVisibleIf(false)
                }

                val currentFontSize = fontSize
                val matchingContact = findContactByCall(call)
                val name = matchingContact?.getNameToDisplay() ?: call.name
                var nameToShow = if (name == call.phoneNumber && formatPhoneNumbers) {
                    SpannableString(name.formatPhoneNumber())
                } else {
                    SpannableString(name)
                }
                val shouldShowDuration = call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0

                if (refreshItemsListener == null) {
                    // show specific number at "Show call details" dialog too
                    val typePart = call.specificType
                        .takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()

                    val numPart = call.specificNumber
                        .takeIf { it.isNotBlank() }
                        ?.let { if (formatPhoneNumbers) it.formatPhoneNumber() else it }
                        ?.let { ", $it" }
                        .orEmpty()

                    nameToShow = SpannableString("$name$typePart$numPart")
                } else if (call.specificType.isNotBlank()) {
                    nameToShow = SpannableString("$name - ${call.specificType}")
                }

                if (textToHighlight.isNotEmpty() && nameToShow.contains(textToHighlight, true)) {
                    nameToShow = SpannableString(nameToShow.toString().highlightTextPart(textToHighlight, properPrimaryColor))
                }

                // 🎓 ITTC: a lead's number → its stage pill, a coloured bar and "· Rahim · CSE"
                val lead = if (call.isUnknownNumber || !cardMode) null else IttcBridge.recentLead(activity, call.phoneNumber) {
                    ittcRefresh()
                }
                // (not in the phone's contacts: the number stays the title, the lead's name goes
                // on the line below)
                val unsaved = matchingContact == null && name == call.phoneNumber

                // 🎓 ITTC: one row per number: "2 missed" (missed since the last answered / outgoing call)
                val newMissed = if (ittcPerNumber) IttcCallHistory.newMissed(call) else 0
                val missed = call.type == Calls.MISSED_TYPE || newMissed > 0

                itemRecentsName.apply {
                    text = nameToShow
                    setTextColor(if (missed) p.danger else p.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize)
                }

                itemRecentsCount.apply {
                    val n = call.groupedCalls?.size ?: 0
                    beVisibleIf(n > 1)
                    if (n > 1) {
                        text = "($n)"
                        setTextColor(p.muted)
                    }
                }

                val stage = lead?.stage?.takeIf { it.isNotBlank() }
                itemRecentsPill.apply {
                    when {
                        newMissed > 0 -> {
                            text = activity.getString(R.string.ittc_hist_missed_count, newMissed)
                            pillBg.setColor(p.dangerStrong)
                            setTextColor(p.dangerStrong.getContrastColor())
                            beVisibleIf(true)
                        }

                        lead != null -> {
                            val (bg, fg) = stageColorsFor(stage ?: "")
                            text = stage ?: labelLead
                            pillBg.setColor(bg)
                            setTextColor(fg)
                            beVisibleIf(true)
                        }

                        else -> beVisibleIf(false)
                    }
                }

                itemRecentsBar.beVisibleIf(lead != null)
                if (lead != null) barBg.setColor(stageColorsFor(stage ?: "").second)

                // "Outgoing · 4m 12s · Rahim · CSE · Australia"
                itemRecentsInfo.apply {
                    val line = StringBuilder(48)
                    line.append(
                        when (call.type) {
                            Calls.OUTGOING_TYPE -> labelOutgoing
                            Calls.MISSED_TYPE -> labelMissed
                            Calls.REJECTED_TYPE -> labelDeclined
                            Calls.BLOCKED_TYPE -> labelBlocked
                            else -> labelIncoming
                        }
                    )
                    if (shouldShowDuration) line.append(" · ").append(context.formatSecondsToShortTimeString(call.duration))
                    if (lead != null) {
                        if (unsaved && lead.name.isNotEmpty()) line.append(" · ").append(lead.name)
                        if (!lead.course.isNullOrBlank()) line.append(" · ").append(lead.course)
                    }
                    ittcLocation(call, matchingContact != null)?.let { line.append(" · ").append(it) }
                    text = line
                    setTextColor(p.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize * 0.8f)
                }

                itemRecentsDateTime.apply {
                    text = if (refreshItemsListener == null) {
                        call.startTS.formatDateOrTime(context, hideTimeOnOtherDays = false, showCurrentYear = false, hideTodaysDate = false)
                    } else {
                        checkDay()
                        // ("Earlier": the date; today / yesterday: the time)
                        if (call.startTS >= yesterdayStart) {
                            call.startTS.formatTime(activity)
                        } else {
                            call.startTS.formatDateOrTime(context, hideTimeOnOtherDays = true, showCurrentYear = false)
                        }
                    }
                    setTextColor(p.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize * 0.8f)
                }

                itemRecentsSimImage.beVisibleIf(areMultipleSIMsAvailable && call.simID != -1)
                itemRecentsSimId.beVisibleIf(areMultipleSIMsAvailable && call.simID != -1)
                if (areMultipleSIMsAvailable && call.simID != -1) {
                    val simColor = getAdjustedSimColor(call.simColor)
                    itemRecentsSimImage.applyColorFilter(simColor)
                    itemRecentsSimId.setTextColor(simColor.getContrastColor())
                    itemRecentsSimId.text = call.simID.toString()
                }

                // 🎓 ITTC 3.5: a contact's photo, else the initials in a soft colour (no bitmap made)
                val photo = call.photoUri.isNotEmpty()
                if (photo) {
                    photoPlaceholder.setColor(p.surfaceAlt)
                    itemRecentsImage.beVisibleIf(true)
                    itemRecentsAvatar.beVisibleIf(false)
                    SimpleContactsHelper(root.context).loadContactImage(call.photoUri, itemRecentsImage, call.name, photoPlaceholder)
                } else {
                    if (hasPhoto && !activity.isDestroyed && !activity.isFinishing) Glide.with(activity).clear(itemRecentsImage)
                    itemRecentsImage.beVisibleIf(false)
                    itemRecentsAvatar.beVisibleIf(true)
                    val avatar = avatarFor(if (unsaved && lead != null && lead.name.isNotEmpty()) lead.name else name)
                    avatarBg.setColor(avatar.bg)
                    itemRecentsAvatar.setTextColor(avatar.fg)
                    itemRecentsAvatar.text = avatar.initials
                }
                hasPhoto = photo

                if (profileIconClick != null) {
                    val onProfile = View.OnClickListener {
                        if (!actModeCallback.isSelectable) {
                            profileIconClick.invoke(call)
                        } else {
                            viewClicked(call)
                        }
                    }
                    val onProfileLong = View.OnLongClickListener {
                        viewLongClicked()
                        true
                    }
                    itemRecentsImage.setOnClickListener(onProfile)
                    itemRecentsImage.setOnLongClickListener(onProfileLong)
                    itemRecentsAvatar.setOnClickListener(onProfile)
                    itemRecentsAvatar.setOnLongClickListener(onProfileLong)
                }

                val drawable = when (call.type) {
                    Calls.OUTGOING_TYPE -> outgoingCallIcon
                    Calls.MISSED_TYPE -> incomingMissedCallIcon
                    else -> incomingCallIcon
                }

                itemRecentsType.setImageDrawable(drawable)

                // 🎓 ITTC 3.5: → the number's call history (a tap on the row calls it)
                val showHistory = cardMode && historyClick != null && !call.isUnknownNumber
                itemRecentsHistory.beVisibleIf(showHistory)
                if (showHistory) {
                    historyBg.setColor(p.surfaceAlt)
                    itemRecentsHistory.setColorFilter(p.text2)
                    itemRecentsHistory.setOnClickListener {
                        if (actModeCallback.isSelectable) viewClicked(call) else historyClick?.invoke(call)
                    }
                }

                overflowMenuIcon.beVisibleIf(showOverflowMenu)
                overflowMenuIcon.drawable.apply {
                    mutate()
                    setTint(if (cardMode) p.muted else activity.getProperTextColor())
                }

                overflowMenuIcon.setOnClickListener {
                    showPopupMenu(overflowMenuAnchor, call)
                }
            }
        }
    }

    /** 🎓 ITTC: the place of a number from abroad (as the old row showed it), else null */
    private fun ittcLocation(call: RecentCall, isContact: Boolean): String? {
        val key = (if (isContact) "c" else "n") + call.phoneNumber
        locationCache[key]?.let { return it.ifEmpty { null } }
        val found = ittcLocationOf(call, isContact)
        locationCache[key] = found.orEmpty()
        return found
    }

    private fun ittcLocationOf(call: RecentCall, isContact: Boolean): String? {
        val locale = Locale.getDefault()
        val defaultCountryCode = locale.country
        val phoneNumber = try {
            phoneNumberUtilInstance.parse(call.phoneNumber, defaultCountryCode)
        } catch (_: NumberParseException) {
            null
        } ?: return null
        if (phoneNumber.countryCodeSource == Phonenumber.PhoneNumber.CountryCodeSource.FROM_DEFAULT_COUNTRY) return null
        val location = phoneNumberOfflineGeocoderInstance.getDescriptionForNumber(phoneNumber, locale, defaultCountryCode)
        return location?.takeIf { it.isNotBlank() && (it != locale.displayCountry || !isContact) }
    }

    /** 🎓 ITTC: the four card shapes, shared by every row (never changed once made) */
    private val radius = IttcTheme.dp(activity, 16f).toFloat()
    private val cornersAll = FloatArray(8) { radius }
    private val cornersTop = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    private val cornersBottom = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
    private val cornersNone = FloatArray(8)

    private fun cardCorners(first: Boolean, last: Boolean): FloatArray = when {
        first && last -> cornersAll
        first -> cornersTop
        last -> cornersBottom
        else -> cornersNone
    }

    private fun getAdjustedSimColor(simColor: Int): Int {
        return cachedSimColors.getOrPut(simColor to backgroundColor) {
            simColor.adjustForContrast(backgroundColor)
        }
    }

    private inner class RecentCallDateViewHolder(val binding: ItemRecentsDateBinding) : ViewHolder(binding.root) {
        fun bind(date: CallLogItem.Date) {
            binding.dateTextView.apply {
                setTextColor(if (cardMode) palette.muted else secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.76f)

                val now = DateTime.now()
                text = when (date.dayCode) {
                    // 🎓 ITTC 3.5: every older day in one "Earlier" card
                    EARLIER_DAY_CODE -> activity.getString(R.string.ittc_a_earlier)
                    now.millis.getDayCode() -> activity.getString(R.string.today)
                    now.minusDays(1).millis.getDayCode() -> activity.getString(R.string.yesterday)
                    else -> date.timestamp.formatDateOrTime(
                        context = activity,
                        hideTimeOnOtherDays = true,
                        showCurrentYear = false
                    )
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_DATE = 0
        private const val VIEW_TYPE_CALL = 1

        /** 🎓 ITTC: "only the corners and dividers" (a bind payload) */
        private val PAYLOAD_CORNERS = Any()

        /** 🎓 ITTC 3.5: the day code of the one "Earlier" group (before yesterday) */
        const val EARLIER_DAY_CODE = "0000-00-00"
    }
}

class RecentCallsDiffCallback : DiffUtil.ItemCallback<CallLogItem>() {

    override fun areItemsTheSame(oldItem: CallLogItem, newItem: CallLogItem) = oldItem.getItemId() == newItem.getItemId()

    override fun areContentsTheSame(oldItem: CallLogItem, newItem: CallLogItem): Boolean {
        return when {
            oldItem is CallLogItem.Date && newItem is CallLogItem.Date -> oldItem.timestamp == newItem.timestamp && oldItem.dayCode == newItem.dayCode
            oldItem is RecentCall && newItem is RecentCall -> {
                oldItem.phoneNumber == newItem.phoneNumber &&
                        oldItem.name == newItem.name &&
                        oldItem.photoUri == newItem.photoUri &&
                        oldItem.startTS == newItem.startTS &&
                        oldItem.duration == newItem.duration &&
                        oldItem.type == newItem.type &&
                        oldItem.simID == newItem.simID &&
                        oldItem.specificNumber == newItem.specificNumber &&
                        oldItem.specificType == newItem.specificType &&
                        oldItem.isUnknownNumber == newItem.isUnknownNumber &&
                        oldItem.groupedCalls?.size == newItem.groupedCalls?.size
            }

            else -> false
        }
    }
}