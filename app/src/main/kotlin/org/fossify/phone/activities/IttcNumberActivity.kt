package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.commons.extensions.formatTime
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemRecentsDateBinding
import org.fossify.phone.databinding.IttcActivityNumberBinding
import org.fossify.phone.databinding.IttcItemNumberCallBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getDayCode
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCallHistory
import org.fossify.phone.helpers.IttcContactsCache
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import org.fossify.phone.helpers.RecentsHelper
import org.joda.time.DateTime
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 🎓 ITTC: one number's call history (a tap on a Recents row): who it is, call / SMS / WhatsApp /
 * contact / block, a summary, and every call with the number by day, newest first.
 * 3.5: the card look — a summary card on top, action tiles (call in teal), a small card per call.
 * Contact mode ([openContact], the Contacts tab's photo or ›): the same page for a whole contact — a
 * "Contact info" card (numbers, emails, addresses, work, dates, websites, groups, notes), the calls with
 * any of its numbers, the lead of any of them, and "Edit" to change it in the contacts app.
 */
class IttcNumberActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityNumberBinding::inflate)

    private var number = ""
    private var givenName: String? = null
    private var contactName: String? = null
    private var contactUri: Uri? = null
    /** the lead's name when no contact has the number (for the initials) */
    private var leadName: String? = null
    private var shownPhoto: String? = null
    private var entries = listOf<IttcCallHistory.Entry>()
    /** 🎨 the 3.3 palette (the app's light / dark theme) */
    private lateinit var palette: IttcTheme.Palette

    /** Contact mode: the contact's id (0: one number, as from Recents) and the raw contact tapped */
    private var contactId = 0
    private var contactRawId = 0
    /** Every number the page covers, the main one first (one number: just [number]) */
    private var numbers = listOf<String>()
    /** Contact mode: the contact as the phone has it now (null: not read yet, or no longer there) */
    private var contact: Contact? = null
    /** The contacts app was opened to edit: read the contacts afresh on the way back */
    private var editOpened = false

    private val contactMode get() = contactId > 0

    private data class ContactInfo(val name: String?, val photoUri: String?, val uri: Uri?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        givenName = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() && it != number }
        if (number.isBlank()) {
            finish()
            return
        }
        contactId = intent.getIntExtra(EXTRA_CONTACT_ID, 0)
        contactRawId = intent.getIntExtra(EXTRA_RAW_ID, 0)
        numbers = distinctNumbers(listOf(number) + intent.getStringArrayListExtra(EXTRA_NUMBERS).orEmpty())

        setContentView(binding.root)
        palette = IttcTheme.of(this)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcNumberScrollview))
            setupMaterialScrollListener(ittcNumberScrollview, ittcNumberAppbar)

            if (contactMode) ittcNumberToolbar.title = getString(R.string.ittc_cd_title)
            ittcNumberCall.setOnClickListener { callTile() }
            ittcNumberSms.setOnClickListener { launchSendSMSIntent(number) }
            ittcNumberWhatsapp.setOnClickListener { openWhatsApp() }
            ittcNumberContact.setOnClickListener { contactTile() }
            ittcNumberBlock.setOnClickListener { tryBlocking() }
            ittcNumberDelete.setOnClickListener { askConfirmDelete() }
        }

        updateTextColors(binding.ittcNumberScrollview)
        applyPalette()
        showHeader()
    }

    /** 🎨 the soft background, the summary card, the tiles (call: teal) */
    private fun applyPalette() {
        val p = palette
        binding.apply {
            // (the screen's background only: the list on it has none of its own, no second paint)
            ittcNumberCoordinator.setBackgroundColor(p.bg)
            IttcTheme.card(ittcNumberCard, p)
            ittcNumberDivider.setBackgroundColor(p.divider)
            ittcNumberName.setTextColor(p.text)
            ittcNumberNumber.setTextColor(p.muted)
            ittcNumberSummary.setTextColor(p.text2)
            ittcNumberPlaceholder.setTextColor(p.muted)
            ittcNumberCall.background = IttcTheme.rounded(this@IttcNumberActivity, p.call, 16f)
            ittcNumberCall.setTextColor(p.onPrimary)
            listOf(ittcNumberSms, ittcNumberWhatsapp, ittcNumberContact, ittcNumberBlock).forEach {
                it.background = IttcTheme.rounded(this@IttcNumberActivity, p.surface, 16f)
                it.setTextColor(p.text)
            }
            ittcNumberDelete.setTextColor(p.danger)
            IttcTheme.card(ittcNumberInfo, p)
            ittcNumberHistoryTitle.setTextColor(p.muted)
            // (the tiles' pressed ripple keeps to their rounded corners)
            listOf(ittcNumberCall, ittcNumberSms, ittcNumberWhatsapp, ittcNumberContact, ittcNumberBlock).forEach { it.clipToOutline = true }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcNumberAppbar, NavigationIcon.Arrow)
        // (back from a call or the contacts app: the calls and the name may have changed)
        loadContact()
        loadLead()
        loadCalls()
    }

    private fun displayName() = contactName ?: givenName ?: number

    private fun shownNumber() = if (config.formatPhoneNumbers) number.formatPhoneNumber() else number

    private fun formatted(n: String) = if (config.formatPhoneNumbers) n.formatPhoneNumber() else n

    /** The numbers once each (the same number saved as "01…" and "+8801…" is one), in order */
    private fun distinctNumbers(list: List<String>): List<String> {
        val seen = HashSet<String>()
        return list.filter { it.isNotBlank() && seen.add(IttcCallHistory.key(it).ifEmpty { it }) }
    }

    private fun keysOf(list: List<String>): Set<String> = list.map { IttcCallHistory.key(it).ifEmpty { it } }.toSet()

    /** Contact mode with the contact found: the Contact tile edits it */
    private fun editsContact() = contactMode && (contact != null || contactUri != null)

    /** Call: the number; a contact with 2+ numbers → the contact (its default number, else a choice) */
    private fun callTile() {
        val c = contact
        if (contactMode && c != null && numbers.size > 1) {
            // (the same number saved twice is offered once)
            val seen = HashSet<String>()
            val phones = c.phoneNumbers.filter { it.value.isNotBlank() && seen.add(IttcCallHistory.key(it.value).ifEmpty { it.value }) }
            startCallWithConfirmationCheck(c.copy(phoneNumbers = ArrayList(phones)))
        } else {
            startCallWithConfirmationCheck(number, displayName())
        }
    }

    /** Contact: edit it (contact mode), open it (a saved number), or save the number */
    private fun contactTile() {
        val uri = contactUri
        if (editsContact()) {
            editOpened = true
            if (uri != null) {
                val edit = Intent(Intent.ACTION_EDIT, uri).apply { putExtra("finishActivityOnSaveCompleted", true) }
                try {
                    startActivity(edit)
                    return
                } catch (_: Throwable) {
                }
                launchViewContactIntent(uri)
                return
            }
            // (a contact kept by Fossify Contacts only, with no lookup uri: its own way, as before)
            contact?.let { startContactDetailsIntent(it) }
            return
        }
        if (uri != null) launchViewContactIntent(uri) else startAddContactIntent(number)
    }

    /**
     * Contact mode: the contact from the kept contacts list (read off the main thread by
     * IttcContactsCache) — its name, photo, numbers and the info card; then its lookup uri for "Edit".
     * Back from editing it: read afresh, so the change shows.
     */
    private fun loadWholeContact() {
        val fresh = editOpened
        editOpened = false
        IttcContactsCache.get(this, fresh) { list ->
            if (isDestroyed || isFinishing) return@get
            val found = findContact(list)
            if (found == null) {
                // (gone from the phone, or no access: the number's own page, as from Recents)
                contact = null
                showInfo(null)
                loadContactByNumber()
                return@get
            }
            contact = found
            contactName = found.getNameToDisplay().takeIf { it.isNotBlank() }
            shownPhoto = found.photoUri.takeIf { it.isNotEmpty() }
            val now = distinctNumbers(found.phoneNumbers.sortedByDescending { it.isPrimary }.map { it.value })
            if (now.isNotEmpty() && keysOf(now) != keysOf(numbers)) {
                // (a number was added or removed in the contacts app: the lead and the calls again)
                val mainKey = IttcCallHistory.key(number).ifEmpty { number }
                if (now.none { IttcCallHistory.key(it).ifEmpty { it } == mainKey }) number = now.first()
                numbers = distinctNumbers(listOf(number) + now)
                shownKey = null
                loadLead()
                loadCalls()
            }
            showHeader(shownPhoto)
            showInfo(found)
            ensureBackgroundThread {
                val uri = lookupUriOf(found)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    contactUri = uri
                    showHeader(shownPhoto)
                }
            }
        }
    }

    /** The tapped contact; one person saved in several accounts is one page with all of it */
    private fun findContact(list: List<Contact>): Contact? {
        val main = list.firstOrNull { it.rawId == contactRawId && it.contactId == contactId }
            ?: list.firstOrNull { it.contactId == contactId }
            ?: return null
        val same = list.filter { it.contactId == main.contactId && it.isPrivate() == main.isPrivate() }
        if (same.size <= 1) return main
        val others = listOf(main) + same.filter { it !== main }
        return main.copy(
            phoneNumbers = ArrayList(others.flatMap { it.phoneNumbers }),
            emails = ArrayList(others.flatMap { it.emails }.distinctBy { it.value.trim().lowercase() }),
            addresses = ArrayList(others.flatMap { it.addresses }.distinctBy { it.value.trim() }),
            events = ArrayList(others.flatMap { it.events }.distinctBy { it.type to it.value }),
            websites = ArrayList(others.flatMap { it.websites }.distinct()),
            groups = ArrayList(others.flatMap { it.groups }.distinctBy { it.title }),
            notes = others.map { it.notes.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n\n"),
            organization = others.map { it.organization }.firstOrNull { it.isNotEmpty() } ?: main.organization,
            photoUri = main.photoUri.ifEmpty { others.firstOrNull { it.photoUri.isNotEmpty() }?.photoUri.orEmpty() },
        )
    }

    /** The contact's lookup uri (for "Edit"); null for a contact only Fossify Contacts keeps. Off the main thread */
    @SuppressLint("MissingPermission")
    private fun lookupUriOf(c: Contact): Uri? {
        if (c.isPrivate() || !hasPermission(PERMISSION_READ_CONTACTS)) return null
        return try {
            ContactsContract.Contacts.getLookupUri(contentResolver, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.contactId.toLong()))
        } catch (_: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------------------------- the info card
    private class InfoAction(val icon: Int, val color: Int, val label: String, val run: () -> Unit)

    /** Contact mode: every number, email, address, work, date, website, group and note (only those it has) */
    private fun showInfo(c: Contact?) {
        val box = binding.ittcNumberInfo
        box.removeAllViews()
        binding.ittcNumberHistoryTitle.beVisibleIf(c != null)
        if (c == null) {
            box.beGone()
            return
        }
        val p = palette
        box.addView(TextView(this).apply {
            setText(R.string.ittc_cd_info)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(p.muted)
            setPaddingRelative(dp(16f), dp(4f), dp(16f), dp(2f))
        })
        var groupsShown = 0
        fun group(add: () -> Unit) {
            if (groupsShown > 0) {
                box.addView(View(this).apply { setBackgroundColor(p.divider) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                    marginStart = dp(52f)
                    topMargin = dp(2f)
                    bottomMargin = dp(2f)
                })
            }
            groupsShown++
            add()
        }

        // (the same number saved twice is shown once)
        val seen = HashSet<String>()
        val phones = c.phoneNumbers.filter { it.value.isNotBlank() && seen.add(IttcCallHistory.key(it.value).ifEmpty { it.value }) }
        if (phones.isNotEmpty()) group {
            phones.forEach { n ->
                val shown = formatted(n.value)
                var label = getPhoneNumberTypeText(n.type, n.label)
                if (n.isPrimary && phones.size > 1) label = listOf(label, getString(R.string.ittc_cd_default)).filter { it.isNotEmpty() }.joinToString(" · ")
                infoRow(
                    icon = R.drawable.ic_phone_vector,
                    value = shown,
                    label = label,
                    a11y = getString(R.string.ittc_cd_call_number, listOf(shown, label).filter { it.isNotEmpty() }.joinToString(", ")),
                    copy = n.value,
                    actions = listOf(
                        InfoAction(R.drawable.ic_sms_vector, p.primary, getString(R.string.ittc_cd_sms_number, shown)) { launchSendSMSIntent(n.value) },
                        InfoAction(R.drawable.ittc_ic_whatsapp, WHATSAPP_GREEN, getString(R.string.ittc_cd_whatsapp_number, shown)) { openWhatsApp(n.value) },
                    ),
                ) { startCallWithConfirmationCheck(n.value, displayName()) }
            }
        }

        val emails = c.emails.filter { it.value.isNotBlank() }
        if (emails.isNotEmpty()) group {
            emails.forEach { e ->
                val label = typeText(e.type, e.label)
                infoRow(R.drawable.ic_mail_vector, e.value, label, getString(R.string.ittc_cd_email_to, e.value), copy = e.value) { sendEmail(e.value) }
            }
        }

        val addresses = c.addresses.mapNotNull { a ->
            val text = a.value.trim().ifEmpty {
                listOf(a.street, a.neighborhood, a.city, a.region, a.postcode, a.country).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            }
            if (text.isEmpty()) null else text to typeText(a.type, a.label)
        }
        if (addresses.isNotEmpty()) group {
            addresses.forEach { (text, label) ->
                infoRow(R.drawable.ic_place_vector, text, label, getString(R.string.ittc_cd_map, text), copy = text, multiLine = true) { openMap(text) }
            }
        }

        val work = c.organization
        if (work.isNotEmpty()) group {
            val company = work.company.trim()
            val job = work.jobPosition.trim()
            val value = company.ifEmpty { job }
            val label = if (company.isNotEmpty() && job.isNotEmpty()) job else getString(R.string.ittc_cd_work)
            infoRow(R.drawable.ic_business_vector, value, label, null, copy = listOf(job, company).filter { it.isNotEmpty() }.joinToString(", "), onClick = null)
        }

        val events = c.events.filter { it.value.isNotBlank() }
        if (events.isNotEmpty()) group {
            events.forEach { e ->
                val label = when (e.type) {
                    CommonDataKinds.Event.TYPE_BIRTHDAY -> getString(R.string.birthday)
                    CommonDataKinds.Event.TYPE_ANNIVERSARY -> getString(R.string.anniversary)
                    else -> getString(R.string.ittc_cd_other)
                }
                infoRow(R.drawable.ittc_ic_event, eventDate(e.value), label, null, copy = null, onClick = null)
            }
        }

        val sites = c.websites.map { it.trim() }.filter { it.isNotEmpty() }
        if (sites.isNotEmpty()) group {
            sites.forEach { site ->
                infoRow(R.drawable.ic_link_vector, site, getString(R.string.ittc_cd_websites), getString(R.string.ittc_cd_open_site, site), copy = site) { openWebsite(site) }
            }
        }

        val groups = c.groups.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct()
        if (groups.isNotEmpty()) group {
            infoRow(R.drawable.ic_people_outline_vector, groups.joinToString(", "), getString(R.string.ittc_cd_groups), null, copy = null, onClick = null)
        }

        val notes = c.notes.trim()
        if (notes.isNotEmpty()) group {
            infoRow(R.drawable.ic_info_outline_vector, notes, getString(R.string.ittc_cd_notes), null, copy = notes, multiLine = true, onClick = null)
        }

        box.beVisible()
    }

    /**
     * One line of the info card: an icon, the value, its label under it, and small buttons at the end.
     * A tap runs [onClick] (null: not tappable); a long press copies [copy].
     */
    private fun infoRow(
        icon: Int,
        value: String,
        label: String?,
        a11y: String?,
        copy: String?,
        actions: List<InfoAction> = emptyList(),
        multiLine: Boolean = false,
        onClick: (() -> Unit)?,
    ) {
        val p = palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPaddingRelative(dp(16f), dp(6f), dp(4f), dp(6f))
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(resources.getColoredDrawableWithColor(icon, p.primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(p.text)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            if (!multiLine) {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        })
        if (!label.isNullOrEmpty()) {
            texts.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(16f)
            marginEnd = dp(4f)
        })

        actions.forEach { a ->
            row.addView(ImageView(this).apply {
                setImageDrawable(resources.getColoredDrawableWithColor(a.icon, a.color))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = a.label
                borderlessRipple(this)
                setOnClickListener { a.run() }
            }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        }

        if (onClick != null) {
            row.contentDescription = a11y
            row.setOnClickListener { onClick() }
        }
        if (copy != null) {
            row.setOnLongClickListener {
                copyToClipboard(copy)
                true
            }
        }
        if (onClick != null || copy != null) IttcUi.ripple(row)
        binding.ittcNumberInfo.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Float) = IttcTheme.dp(this, v)

    private fun borderlessRipple(view: View) {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true) && value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId)
        }
    }

    /** An email's or an address's type: Home / Work / Mobile / its own label / Other */
    private fun typeText(type: Int, label: String): String = when (type) {
        CommonDataKinds.BaseTypes.TYPE_CUSTOM -> label.ifEmpty { getString(R.string.ittc_cd_other) }
        CommonDataKinds.Email.TYPE_HOME -> getString(R.string.ittc_cd_email_home)
        CommonDataKinds.Email.TYPE_WORK -> getString(R.string.ittc_cd_email_work)
        CommonDataKinds.Email.TYPE_MOBILE -> getString(R.string.ittc_cd_email_mobile)
        else -> getString(R.string.ittc_cd_other)
    }

    /** "2000-05-14" → "14 May 2000", "--05-14" (no year) → "14 May"; anything else as it is */
    private fun eventDate(value: String): String = try {
        val noYear = value.startsWith("--")
        val parsed = SimpleDateFormat(if (noYear) "--MM-dd" else "yyyy-MM-dd", Locale.US).parse(value)
        if (parsed == null) {
            value
        } else {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (noYear) "d MMMM" else "d MMMM yyyy")
            DateFormat.format(pattern, parsed).toString()
        }
    } catch (_: Throwable) {
        value
    }

    private fun sendEmail(address: String) {
        val mail = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
        try {
            startActivity(Intent.createChooser(mail, null))
        } catch (_: Throwable) {
            toast(R.string.ittc_hist_no_app)
        }
    }

    private fun openMap(address: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
        } catch (_: Throwable) {
            toast(R.string.ittc_hist_no_app)
        }
    }

    private fun openWebsite(site: String) {
        val url = if (site.contains("://")) site else "http://$site"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            toast(R.string.ittc_hist_no_app)
        }
    }

    private fun showHeader(photoUri: String? = null) {
        val p = palette
        binding.apply {
            val name = contactName ?: givenName
            ittcNumberName.text = name ?: shownNumber()
            ittcNumberNumber.text = shownNumber()
            ittcNumberNumber.beVisibleIf(name != null)
            // (a contact's photo; else the initials in a soft colour, as in Recents)
            val hasPhoto = !photoUri.isNullOrEmpty()
            ittcNumberPhoto.beVisibleIf(hasPhoto)
            ittcNumberInitials.beVisibleIf(!hasPhoto)
            if (hasPhoto) {
                SimpleContactsHelper(this@IttcNumberActivity).loadContactImage(photoUri.orEmpty(), ittcNumberPhoto, displayName())
            } else {
                val shown = leadName?.takeIf { name == null } ?: displayName()
                val (bg, fg) = IttcTheme.avatarColors(this@IttcNumberActivity, shown)
                ittcNumberInitials.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bg)
                }
                ittcNumberInitials.setTextColor(fg)
                ittcNumberInitials.text = IttcTheme.initials(if (name == null && leadName == null) null else shown)
            }

            val icons = listOf(
                ittcNumberCall to R.drawable.ic_phone_vector,
                ittcNumberSms to R.drawable.ic_sms_vector,
                ittcNumberWhatsapp to R.drawable.ic_sms_vector,
                ittcNumberContact to when {
                    editsContact() -> R.drawable.ic_edit_vector
                    contactUri != null -> R.drawable.ic_person_vector
                    else -> R.drawable.ic_add_person_vector
                },
                ittcNumberBlock to R.drawable.ic_block_vector,
            )
            icons.forEach { (view, icon) ->
                val color = when (view) {
                    ittcNumberCall -> p.onPrimary
                    ittcNumberWhatsapp -> WHATSAPP_GREEN
                    else -> p.primary
                }
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, resources.getColoredDrawableWithColor(icon, color), null, null)
            }
            ittcNumberContact.setText(
                when {
                    editsContact() -> R.string.ittc_cd_edit
                    contactUri != null -> R.string.ittc_hist_contact
                    else -> R.string.ittc_hist_save
                }
            )
            ittcNumberDelete.setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getColoredDrawableWithColor(R.drawable.ic_delete_vector, p.danger), null, null, null
            )
        }
    }

    private fun loadContact() {
        if (contactMode) loadWholeContact() else loadContactByNumber()
    }

    /** One number (as from Recents): the contact that has it, if any */
    private fun loadContactByNumber() {
        ensureBackgroundThread {
            val info = lookupContact(number)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                contactName = info?.name?.takeIf { it.isNotBlank() }
                contactUri = info?.uri
                shownPhoto = info?.photoUri
                showHeader(info?.photoUri)
            }
        }
    }

    @SuppressLint("MissingPermission") // (READ_CONTACTS checked first)
    private fun lookupContact(number: String): ContactInfo? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.LOOKUP_KEY,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
        )
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val lookupKey = cursor.getString(1)
                    return ContactInfo(
                        name = cursor.getString(2),
                        photoUri = cursor.getString(3),
                        uri = ContactsContract.Contacts.getLookupUri(id, lookupKey),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** The ITTC lead line: "🎓 Lead · Course · Stage" (the lead's name when no contact has the number) */
    private fun loadLead() {
        val wanted = numbers
        ensureBackgroundThread {
            // (a contact: the first of its numbers that is a lead)
            val lead = try {
                val found = IttcBridge.leadsFor(this, wanted)
                wanted.firstNotNullOfOrNull { found[it] }
            } catch (_: Throwable) {
                null
            }
            val direct = try {
                IttcDirect.isActive(this)
            } catch (_: Throwable) {
                false
            }
            val canOpen = direct || IttcBridge.trackerInstalled(this)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showLeadBox(lead, direct, canOpen)
                val p = palette
                val unsaved = contactName == null && givenName == null
                binding.ittcNumberLead.apply {
                    beVisibleIf(lead != null)
                    if (lead != null) {
                        val who = if (unsaved && lead.name.isNotEmpty()) "🎓 ${lead.name}" else getString(R.string.ittc_lead_badge)
                        text = listOfNotNull(who, lead.course).joinToString(" · ")
                        setTextColor(p.text2)
                    }
                }
                // (the stage as a pill, coloured per stage)
                val stage = lead?.stage?.takeIf { it.isNotBlank() }
                binding.ittcNumberStage.apply {
                    beVisibleIf(stage != null)
                    if (stage != null) {
                        val (bg, fg) = IttcTheme.stageColors(this@IttcNumberActivity, stage)
                        IttcTheme.pill(this, bg, fg)
                        text = stage
                    }
                }
                // (no contact: the lead's name for the initials)
                val newLeadName = lead?.name?.takeIf { unsaved && it.isNotEmpty() }
                if (newLeadName != leadName) {
                    leadName = newLeadName
                    showHeader(shownPhoto)
                }
            }
        }
    }

    /**
     * The lead box under the header: a lead → its summary, "Open lead" (and "Templates" in direct
     * mode); no lead and signed in to ITTC here → "Add as a lead"
     */
    private fun showLeadBox(lead: IttcBridge.Lead?, direct: Boolean, canOpen: Boolean) {
        val box = binding.ittcNumberLeadBox
        box.removeAllViews()
        val buttons = ArrayList<Pair<Int, () -> Unit>>()
        if (lead != null) {
            if (canOpen) {
                buttons += R.string.ittc_nl_open to {
                    if (!IttcBridge.openLead(this, lead.leadId)) toast(R.string.ittc_tracker_missing)
                }
            }
            if (direct) buttons += R.string.ittc_nl_templates to { IttcLeadActivity.open(this, lead.leadId, showTemplates = true) }
        } else if (direct) {
            buttons += R.string.ittc_nl_add to { IttcAddLeadActivity.open(this, number, contactName ?: givenName) }
        }
        val note = lead?.let { listOfNotNull(it.rating?.let { r -> "★ ${r.replaceFirstChar { c -> c.uppercase() }}" }, it.lastNote?.let { n -> "“${n.take(160)}”" }).joinToString("  ") }
        box.beVisibleIf(buttons.isNotEmpty() || !note.isNullOrEmpty())
        val p = palette
        val m = IttcTheme.dp(this, 4f)
        if (!note.isNullOrEmpty()) {
            box.addView(TextView(this).apply {
                text = note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(p.text2)
                setPadding(0, 0, 0, m)
            })
        }
        if (buttons.isEmpty()) return
        // (pills: the first filled, the others outlined)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, (label, action) ->
            line.addView(
                TextView(this).apply {
                    setText(label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    val pad = IttcTheme.dp(this@IttcNumberActivity, 12f)
                    setPadding(pad, 0, pad, 0)
                    minHeight = IttcTheme.dp(this@IttcNumberActivity, 40f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    if (i == 0) IttcTheme.pill(this, p.primary, p.onPrimary) else IttcTheme.pill(this, p.surface, p.primary, p.outline)
                    IttcUi.ripple(this)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(if (i == 0) 0 else m, m, if (i == buttons.size - 1) 0 else m, 0) },
            )
        }
        box.addView(line)
    }

    private fun loadCalls() {
        if (!hasPermission(PERMISSION_READ_CALL_LOG)) {
            showCalls(emptyList(), noAccess = true)
            return
        }

        val wanted = numbers
        ensureBackgroundThread {
            val calls = IttcCallHistory.loadCalls(this, wanted)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) showCalls(calls, noAccess = false)
            }
        }
    }

    /** What the call list shows now (count · newest id · access · day): the same → no rebuild */
    private var shownKey: String? = null
    /** Rows on screen (the newest first; "Show more" adds [PAGE] each tap) */
    private var shownRows = 0
    private var lastDayCode = ""

    private fun showCalls(calls: List<IttcCallHistory.Entry>, noAccess: Boolean) {
        // (back from somewhere with nothing changed: keep the list as it is, and its "Show more")
        val key = "${calls.size}|${calls.firstOrNull()?.id}|$noAccess|${System.currentTimeMillis().getDayCode()}"
        if (key == shownKey) return
        shownKey = key
        entries = calls

        binding.apply {
            ittcNumberPlaceholder.setText(if (noAccess) R.string.ittc_hist_no_access else R.string.ittc_hist_none)
            ittcNumberPlaceholder.beVisibleIf(calls.isEmpty())
            ittcNumberDelete.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.beVisibleIf(calls.isNotEmpty())
            ittcNumberDivider.beVisibleIf(calls.isNotEmpty())
            ittcNumberSummary.text = summary(calls)
            ittcNumberCalls.removeAllViews()
        }
        shownRows = 0
        lastDayCode = ""
        showMoreCalls()
    }

    /** The next [PAGE] calls (not every call at once: a number may have thousands) */
    private fun showMoreCalls() {
        val calls = entries
        val list = binding.ittcNumberCalls
        // (the old "Show more" goes; a new one at the end if calls are left)
        list.findViewWithTag<android.view.View>(SHOW_MORE_TAG)?.let { list.removeView(it) }

        val p = palette
        val outgoingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, p.call)
        val incomingIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, p.primary)
        val missedIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, p.danger)
        val showSim = areMultipleSIMsAvailable()
        // (a contact with 2+ numbers: which number each call was with)
        val showNumber = numbers.size > 1

        val until = minOf(calls.size, shownRows + PAGE)
        for (i in shownRows until until) {
            val call = calls[i]
            val dayCode = call.startTS.getDayCode()
            if (dayCode != lastDayCode) {
                lastDayCode = dayCode
                val date = ItemRecentsDateBinding.inflate(layoutInflater, list, false)
                date.dateTextView.apply {
                    text = dayLabel(dayCode, call.startTS)
                    setTextColor(p.muted)
                }
                list.addView(date.root)
            }

            val row = IttcItemNumberCallBinding.inflate(layoutInflater, list, false)
            val missed = call.type == Calls.MISSED_TYPE
            // (a small card; the direction in a soft circle: outgoing teal, incoming blue, missed red)
            row.root.background = IttcTheme.rounded(this, p.surface, 14f)
            row.ittcCallType.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when (call.type) {
                        Calls.OUTGOING_TYPE -> p.callSoft
                        Calls.MISSED_TYPE -> p.dangerSoft
                        else -> p.primarySoft
                    }
                )
            }
            row.ittcCallType.setImageDrawable(
                when (call.type) {
                    Calls.OUTGOING_TYPE -> outgoingIcon
                    Calls.MISSED_TYPE -> missedIcon
                    else -> incomingIcon
                }
            )
            row.ittcCallTime.apply {
                val time = call.startTS.formatTime(this@IttcNumberActivity)
                text = if (showNumber) "$time · ${formatted(call.number)}" else time
                setTextColor(p.muted)
            }
            row.ittcCallKind.apply {
                setText(
                    when (call.type) {
                        Calls.OUTGOING_TYPE -> R.string.ittc_hist_outgoing
                        Calls.MISSED_TYPE -> R.string.ittc_hist_missed
                        Calls.REJECTED_TYPE -> R.string.ittc_a_declined
                        Calls.BLOCKED_TYPE -> R.string.ittc_a_blocked
                        else -> R.string.ittc_hist_incoming
                    }
                )
                setTextColor(if (missed) p.danger else p.text)
            }
            row.ittcCallDuration.apply {
                val talked = call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0
                text = if (talked) "· " + formatSecondsToShortTimeString(call.duration) else ""
                setTextColor(p.text2)
            }
            row.ittcCallSim.apply {
                beVisibleIf(showSim && call.simID != -1)
                text = getString(R.string.ittc_hist_sim, call.simID)
                setTextColor(p.muted)
            }
            list.addView(row.root)
        }
        shownRows = until

        val left = calls.size - shownRows
        if (left > 0) {
            val density = resources.displayMetrics.density
            val pad = (density * 12).toInt()
            list.addView(
                TextView(this).apply {
                    tag = SHOW_MORE_TAG
                    text = getString(R.string.ittc_hist_show_more, left)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(palette.primary)
                    gravity = Gravity.CENTER
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    IttcUi.ripple(this)
                    setOnClickListener { showMoreCalls() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun dayLabel(dayCode: String, timestamp: Long): String {
        val now = DateTime.now()
        return when (dayCode) {
            now.millis.getDayCode() -> getString(R.string.today)
            now.minusDays(1).millis.getDayCode() -> getString(R.string.yesterday)
            else -> timestamp.formatDateOrTime(this, hideTimeOnOtherDays = true, showCurrentYear = false)
        }
    }

    /** "12 calls · 1h 5m talk · 3 missed · 2 Jan 2025 – 18 Nov 2026" */
    private fun summary(calls: List<IttcCallHistory.Entry>): String {
        if (calls.isEmpty()) return ""
        val talkSeconds = calls
            .filter { it.type != Calls.MISSED_TYPE && it.type != Calls.REJECTED_TYPE }
            .sumOf { it.duration.toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val missed = calls.count { it.type == Calls.MISSED_TYPE }
        val newest = calls.maxOf { it.startTS }
        val oldest = calls.minOf { it.startTS }
        fun date(ts: Long) = ts.formatDateOrTime(this, hideTimeOnOtherDays = true, showCurrentYear = true, hideTodaysDate = false)

        val parts = arrayListOf(
            if (calls.size == 1) getString(R.string.ittc_hist_one_call) else getString(R.string.ittc_hist_calls, calls.size)
        )
        if (talkSeconds > 0) parts += getString(R.string.ittc_hist_talk, formatSecondsToShortTimeString(talkSeconds))
        if (missed > 0) parts += getString(R.string.ittc_hist_missed_count, missed)
        val first = date(oldest)
        val last = date(newest)
        parts += if (first == last) first else getString(R.string.ittc_hist_range, first, last)
        return parts.joinToString(" · ")
    }

    /** wa.me wants the number with its country code: a Bangladeshi "01…" is "8801…" */
    private fun openWhatsApp(target: String = number) {
        val key = IttcCallHistory.key(target)
        val waNumber = when {
            key.length == 11 && key.startsWith("01") -> "88$key"
            key.startsWith("00") -> key.substring(2)
            else -> key
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$waNumber")))
        } catch (_: Throwable) {
            toast(R.string.ittc_hist_no_app)
        }
    }

    // (as the Recents list's "Block number")
    private fun tryBlocking() {
        if (!isOrWasThankYouInstalled()) {
            FeatureLockedDialog(this) { }
            return
        }

        val question = String.format(getString(R.string.block_confirmation), number)
        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                if (addBlockedNumber(number)) {
                    IttcCallHistory.recentsDirty = true
                    runOnUiThread { toast(R.string.ittc_hist_blocked) }
                }
            }
        }
    }

    // (as the Recents list's "Remove": the call log, with WRITE_CALL_LOG)
    private fun askConfirmDelete() {
        val count = entries.size
        if (count == 0) return
        ConfirmationDialog(this, getString(R.string.ittc_hist_delete_confirm, count, displayName())) {
            handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) deleteHistory()
            }
        }
    }

    private fun deleteHistory() {
        val shownIds = entries.map { it.id }
        ensureBackgroundThread {
            val ids = (shownIds + IttcCallHistory.idsFor(this, numbers)).distinct()
            RecentsHelper(this).removeRecentCalls(ids) {
                IttcCallHistory.recentsDirty = true
                runOnUiThread {
                    toast(R.string.ittc_hist_deleted)
                    loadCalls()
                }
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "ittc_number"
        const val EXTRA_NAME = "ittc_name"
        const val EXTRA_CONTACT_ID = "ittc_contact_id"
        const val EXTRA_RAW_ID = "ittc_contact_raw_id"
        const val EXTRA_NUMBERS = "ittc_numbers"
        private const val WHATSAPP_GREEN = 0xFF25D366.toInt()
        private const val PAGE = 40
        private const val SHOW_MORE_TAG = "ittc_show_more"

        fun open(context: Context, number: String, name: String? = null) {
            if (number.isBlank()) return
            Intent(context, IttcNumberActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                if (!name.isNullOrBlank()) putExtra(EXTRA_NAME, name)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        }

        /**
         * 🎓 A contact's details page (the Contacts tab's photo or ›): its main number, name, id and every
         * number. A contact without a number opens in the contacts app, as before.
         */
        fun openContact(context: Context, contact: Contact) {
            val all = contact.phoneNumbers.map { it.value }.filter { it.isNotBlank() }
            if (all.isEmpty() || contact.contactId <= 0) {
                (context as? Activity)?.startContactDetailsIntent(contact)
                return
            }
            // (the default number as saved — getPrimaryNumber() gives it normalized — else the first)
            val primary = contact.phoneNumbers.firstOrNull { it.isPrimary && it.value.isNotBlank() }?.value
                ?: contact.getPrimaryNumber()?.takeIf { it.isNotBlank() }
                ?: all.first()
            Intent(context, IttcNumberActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, primary)
                contact.getNameToDisplay().takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_NAME, it) }
                putExtra(EXTRA_CONTACT_ID, contact.contactId)
                putExtra(EXTRA_RAW_ID, contact.rawId)
                putStringArrayListExtra(EXTRA_NUMBERS, ArrayList(all))
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        }
    }
}
