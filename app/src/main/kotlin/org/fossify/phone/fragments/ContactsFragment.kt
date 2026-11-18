package org.fossify.phone.fragments

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_CREATED
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.getProperText
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.FragmentContactsBinding
import org.fossify.phone.databinding.FragmentLettersLayoutBinding
import org.fossify.phone.extensions.handleGenericContactClick
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.extensions.setupWithContacts
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.IttcContactRows
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import org.fossify.phone.interfaces.RefreshItemsListener

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private lateinit var contactsBinding: FragmentContactsBinding
    private var allContacts = ArrayList<Contact>()

    // 🎓 ITTC 3.8: group chips, the search text, the card rows
    /** A chip: every group with this title (one per account) and how many contacts are in any of them */
    private class GroupChip(val key: Long, val title: String, val ids: Set<Long>, val count: Int)

    private var groupChips: List<GroupChip> = emptyList()

    /** The chosen chip's key (its smallest group id); null: All */
    private var chosenGroup: Long? = null
    private var searchQuery = ""
    private var loadGen = 0
    private var loaded = false
    private var chipsSignature: List<Any?>? = null
    private var headersAdded = false

    /** The "nothing here" line is about the chip / search (its button then shows all contacts) */
    private var placeholderForFilter = false
    private val rows by lazy { IttcContactRows(context) }

    override fun onFinishInflate() {
        super.onFinishInflate()
        contactsBinding = FragmentContactsBinding.bind(this)
        binding = FragmentLettersLayoutBinding.bind(contactsBinding.ittcContactsBody)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (placeholderForFilter) {
                    // 🎓 ITTC: nothing in this group (or nothing found in it): back to All
                    chooseGroup(null)
                } else if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }

        // 🎓 ITTC: the letter above each letter's card
        if (!headersAdded) {
            headersAdded = true
            binding.fragmentList.addItemDecoration(rows.Headers { list -> (list.adapter as? ContactsAdapter)?.contacts })
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        // 🎓 ITTC 3.8: the soft background the letter cards sit on (as Recents), the app's light / dark
        val p = IttcTheme.of(context)
        setBackgroundColor(p.bg)
        rows.refreshPalette()
        renderGroupChips()
        binding.apply {
            (fragmentList.adapter as? ContactsAdapter)?.updateTextColor(textColor)
            fragmentPlaceholder.setTextColor(p.text2)
            fragmentPlaceholder2.setTextColor(p.primary)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = properPrimaryColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        val privateCursor = context?.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val gen = ++loadGen
        val app = context.applicationContext
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            // 🎓 ITTC: the private contacts, the groups and the chosen chip off the main thread
            ensureBackgroundThread {
                if (SMT_PRIVATE !in app.baseConfig.ignoredContactSources) {
                    val privateContacts = MyContactsContentProvider.getContacts(app, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        contacts.addAll(privateContacts)
                        contacts.sort()
                    }
                }
                val chips = groupChipsOf(contacts)
                val saved = savedGroup(app)

                activity?.runOnUiThread {
                    if (gen == loadGen) {
                        allContacts = contacts
                        loaded = true
                        groupChips = chips
                        chosenGroup = saved?.takeIf { key -> chips.any { key in it.ids } }?.let { key -> chips.first { key in it.ids }.key }
                        (activity as? MainActivity)?.cacheContacts()
                        gotContacts()
                        lookUpLeads(contacts, gen)
                    }
                    callback?.invoke()
                }
            }
        }
    }

    private fun gotContacts() {
        renderGroupChips()
        showContacts()
    }

    /**
     * 🎓 ITTC: the list for the chosen chip and the search text (both filter the list in memory),
     * the fast scroller for what is shown, and a friendly line when nothing is
     */
    private fun showContacts() {
        val all = allContacts
        val chip = chosenChip()
        val base: List<Contact> = if (chip == null) all else all.filter { inChip(it, chip) }
        val query = searchQuery
        val shown: ArrayList<Contact> = if (query.isEmpty()) ArrayList(base) else searchIn(base, query)

        val sorting = context.baseConfig.sorting
        val sections = query.isEmpty() && (sorting and (SORT_BY_CUSTOM or SORT_BY_DATE_CREATED)) == 0
        if (rows.sections != sections) {
            rows.sections = sections
            binding.fragmentList.invalidateItemDecorations()
        }

        setupLetterFastScroller(shown)
        if (all.isEmpty()) {
            showDefaultPlaceholder()
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
            (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(shown, query)
            return
        }

        // (an empty list would take the taps meant for the "Show all contacts" under it)
        binding.fragmentList.beVisibleIf(shown.isNotEmpty())
        if (shown.isEmpty()) {
            placeholderForFilter = true
            binding.fragmentPlaceholder.text = when {
                query.isNotEmpty() && chip != null -> context.getString(R.string.ittc_ct_no_match_in, chip.title, query)
                query.isNotEmpty() -> context.getString(R.string.ittc_ct_no_match, query)
                chip != null -> context.getString(R.string.ittc_ct_group_empty, chip.title)
                else -> context.getString(R.string.no_contacts_found)
            }
            binding.fragmentPlaceholder2.text = context.getString(R.string.ittc_ct_show_all)
            binding.fragmentPlaceholder2.underlineText()
            IttcUi.fadeIn(binding.fragmentPlaceholder, 0.8f)
            binding.fragmentPlaceholder2.beVisibleIf(chip != null)
        } else {
            binding.fragmentPlaceholder.beGone()
            binding.fragmentPlaceholder2.beGone()
            showDefaultPlaceholder()
        }

        val adapter = binding.fragmentList.adapter as? ContactsAdapter
        if (adapter == null) {
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = shown,
                recyclerView = binding.fragmentList,
                highlightText = query,
                refreshItemsListener = this,
                itemClick = {
                    activity?.handleGenericContactClick(it as Contact)
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                },
                ittcRows = rows,
            ).apply {
                binding.fragmentList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(shown, query)
        }
    }

    /** The placeholder's own texts again (no contacts / no access) */
    private fun showDefaultPlaceholder() {
        if (!placeholderForFilter) return
        placeholderForFilter = false
        val hasPermission = context.hasPermission(PERMISSION_READ_CONTACTS)
        binding.fragmentPlaceholder.text = context.getString(if (hasPermission) R.string.no_contacts_found else R.string.could_not_access_contacts)
        binding.fragmentPlaceholder2.text = context.getString(if (hasPermission) R.string.create_new_contact else R.string.request_access)
        binding.fragmentPlaceholder2.underlineText()
    }

    private fun setupLetterFastScroller(contacts: List<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        searchQuery = ""
        if (loaded) showContacts()
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text.trim().replace("\\s+".toRegex(), " ")
        // (not read yet: the list comes with this text applied)
        if (loaded) showContacts()
    }

    /** The toolbar search, as before (now within the chosen group) */
    private fun searchIn(contacts: List<Contact>, fixedText: String): ArrayList<Contact> {
        val shouldNormalize = fixedText.normalizeString() == fixedText
        val filtered = contacts.filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                (fixedText.toLongOrNull() != null && contact.doesContainPhoneNumber(fixedText, true)) ||
                contact.emails.any { it.value.contains(fixedText, true) } ||
                contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                contact.IMs.any { it.value.contains(fixedText, true) } ||
                getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                contact.websites.any { it.contains(fixedText, true) }
        } as ArrayList

        filtered.sortBy {
            val nameToDisplay = it.getNameToDisplay()
            !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
        }
        return filtered
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                refreshItems()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // 🎓 ITTC 3.8: group chips
    // -------------------------------------------------------------------------------------------
    private fun chosenChip(): GroupChip? = chosenGroup?.let { key -> groupChips.firstOrNull { it.key == key } }

    private fun inChip(contact: Contact, chip: GroupChip): Boolean = contact.groups.any { g -> g.id?.let { it in chip.ids } == true }

    /** Every group with at least one of these contacts, by title (A–Z); runs off the main thread */
    private fun groupChipsOf(contacts: List<Contact>): List<GroupChip> {
        class Acc(val title: String) {
            val ids = HashSet<Long>()
            var count = 0
        }

        val byTitle = HashMap<String, Acc>()
        val counted = HashSet<String>()
        contacts.forEach { contact ->
            if (contact.groups.isEmpty()) return@forEach
            counted.clear()
            contact.groups.forEach { group ->
                val id = group.id
                val title = group.title.trim()
                if (id != null && title.isNotEmpty()) {
                    val key = title.lowercase()
                    val acc = byTitle.getOrPut(key) { Acc(title) }
                    acc.ids.add(id)
                    // (in two groups of the same name: counted once)
                    if (counted.add(key)) acc.count++
                }
            }
        }
        return byTitle.values
            .filter { it.count > 0 }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .map { GroupChip(it.ids.minOrNull() ?: 0L, it.title, it.ids, it.count) }
    }

    private fun chooseGroup(key: Long?) {
        if (key == chosenGroup) return
        chosenGroup = key
        saveGroup(context, key)
        renderGroupChips()
        showContacts()
        binding.fragmentList.scrollToPosition(0)
    }

    /** All · Family 12 · Work 40 …: the chosen one filled, the others outlined (hidden without groups) */
    private fun renderGroupChips() {
        if (!::contactsBinding.isInitialized) return
        val scroll = contactsBinding.ittcContactsGroupsScroll
        val row = contactsBinding.ittcContactsGroups
        val chips = groupChips
        if (chips.isEmpty()) {
            scroll.beGone()
            if (row.childCount > 0) row.removeAllViews()
            chipsSignature = null
            return
        }
        val p = rows.palette
        val signature = listOf<Any?>(chosenGroup, p.dark, allContacts.size) + chips.flatMap { listOf(it.key, it.title, it.count) }
        if (signature == chipsSignature && row.childCount > 0) {
            scroll.beVisible()
            return
        }
        chipsSignature = signature
        row.removeAllViews()
        addChip(row, chipView(context.getString(R.string.ittc_ct_all, allContacts.size), chosenGroup == null) { chooseGroup(null) })
        chips.forEach { chip ->
            addChip(row, chipView(context.getString(R.string.ittc_ct_group, chip.title, chip.count), chip.key == chosenGroup) { chooseGroup(chip.key) })
        }
        scroll.beVisible()
    }

    private fun chipView(label: String, on: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            gravity = Gravity.CENTER
            minHeight = IttcTheme.dp(context, 40f)
            val pad = IttcTheme.dp(context, 14f)
            setPadding(pad, 0, pad, 0)
            val p = rows.palette
            if (on) {
                IttcTheme.pill(this, p.primary, p.onPrimary)
            } else {
                IttcTheme.pill(this, p.surface, p.text, p.outline)
            }
            isSelected = on
            IttcUi.ripple(this)
            setOnClickListener { onClick() }
        }

    private fun addChip(row: ViewGroup, chip: TextView) {
        row.addView(
            chip,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = IttcTheme.dp(context, 8f)
            },
        )
    }

    // -------------------------------------------------------------------------------------------
    // 🎓 ITTC 3.8: which contacts are leads (IttcBridge's batched, cached lookup; off the main thread)
    // -------------------------------------------------------------------------------------------
    private fun lookUpLeads(contacts: List<Contact>, gen: Int) {
        if (contacts.isEmpty()) return
        val app = context.applicationContext
        ensureBackgroundThread {
            val leads = try {
                IttcContactRows.leadsOf(app, contacts)
            } catch (_: Throwable) {
                emptyMap()
            }
            activity?.runOnUiThread {
                if (gen != loadGen || leads == rows.leads) return@runOnUiThread
                rows.leads = leads
                (binding.fragmentList.adapter as? ContactsAdapter)?.apply { notifyItemRangeChanged(0, itemCount) }
            }
        }
    }

    private companion object {
        const val PREFS = "ittc_contacts"
        const val KEY_GROUP = "group"

        fun savedGroup(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (prefs.contains(KEY_GROUP)) prefs.getLong(KEY_GROUP, 0L) else null
        }

        fun saveGroup(context: Context, key: Long?) {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (key == null) edit.remove(KEY_GROUP) else edit.putLong(KEY_GROUP, key)
            edit.apply()
        }
    }
}
