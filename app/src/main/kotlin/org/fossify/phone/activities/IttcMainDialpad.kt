package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.boundingBox
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.DIALPAD_TONE_LENGTH_MS
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcCallHistory
import org.fossify.phone.helpers.IttcDialSearch
import org.fossify.phone.helpers.IttcDisclosure
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.ToneGeneratorHelper
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall
import kotlin.math.roundToInt

/**
 * 🎓 ITTC 3.6: the dial pad on the main screen, as on MIUI. Open when the app starts (Settings →
 * "Open with the dial pad"), above the tab bar with the list above it; typing shows the number and
 * what it matches (leads, unsaved numbers from Recents, contacts — [IttcDialSearch], as on the dial
 * pad screen) over the list. Dragging a list, the hide key or another tab folds it into the round
 * dial pad button; Back folds it first. Keys sound and vibrate as the dial pad screen's do (its
 * settings). DialpadActivity stays for other apps' "dial" links and when the setting is off.
 */
class IttcMainDialpad(
    private val activity: MainActivity,
    private val main: ActivityMainBinding,
    private val callLog: () -> List<CallLogItem>,
    private val contacts: () -> List<Contact>,
) {
    private val pad = main.ittcMainDialpad
    private val search = IttcDialSearch()
    private var number = ""

    // the matches over the list while a number is typed: on when the pad opens, off after another
    // tab or the search (the number stays for the next opening)
    private var resultsWanted = false
    private var searchJob: Job? = null
    private var clearJob: Job? = null
    private var leadsAsked = false
    private var tones: ToneGeneratorHelper? = null
    private val pressedKeys = mutableSetOf<Char>()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()


    /** On in Settings (off: the round button opens the dial pad screen, as before) */
    val enabled: Boolean get() = activity.config.ittcOpenWithDialpad

    val isOpen: Boolean get() = pad.root.visibility == View.VISIBLE

    private val resultsShown: Boolean get() = main.ittcDialResults.visibility == View.VISIBLE

    private val keys: List<Pair<TextView, Char>> by lazy {
        listOf(
            pad.ittcDpKey1 to '1', pad.ittcDpKey2 to '2', pad.ittcDpKey3 to '3',
            pad.ittcDpKey4 to '4', pad.ittcDpKey5 to '5', pad.ittcDpKey6 to '6',
            pad.ittcDpKey7 to '7', pad.ittcDpKey8 to '8', pad.ittcDpKey9 to '9',
            pad.ittcDpKeyStar to '*', pad.ittcDpKey0 to '0', pad.ittcDpKeyHash to '#',
        )
    }

    // the key size for this window's height (dp): the pad, the tab bar, the top bar and at least
    // 120 dp of matches have to fit; null: not even with 44 dp keys
    private var keyDp: Int? = null
    private val fits: Boolean get() = keyDp != null

    private fun sizeKeys() {
        var heightDp = activity.resources.configuration.screenHeightDp
        // (Android 15+: the height includes the status and navigation bars)
        if (android.os.Build.VERSION.SDK_INT >= 35) heightDp -= 72
        // top bar 56 + tab bar 67 + matches 120 + the pad: number row and paddings 92 + 5 rows of keys
        // (the ≡ / call row is a key tall too)
        val size = ((heightDp - 56 - 67 - 120 - 92) / 5).coerceAtMost(KEY_MAX_DP)
        keyDp = size.takeIf { it >= KEY_MIN_DP }
        val px = IttcTheme.dp(activity, (keyDp ?: KEY_MAX_DP).toFloat())
        // (smaller keys, smaller digits: nothing clipped at large font sizes)
        val scale = (keyDp ?: KEY_MAX_DP).toFloat() / KEY_MAX_DP
        keys.forEach { (v, _) ->
            val base = (v.getTag(R.id.ittc_dp_base_size) as? Float) ?: v.textSize.also { v.setTag(R.id.ittc_dp_base_size, it) }
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
        (keys.map { it.first } + listOf(pad.ittcDpMenu, pad.ittcDpCall, pad.ittcDpHide)).forEach { v ->
            val lp = v.layoutParams ?: return@forEach
            if (lp.width != px || lp.height != px) {
                lp.width = px
                lp.height = px
                v.layoutParams = lp
            }
        }
    }

    init {
        sizeKeys()
        setupKeys()
        setupActions()
        main.ittcDialList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) collapse()
            }
        })
        applyColors()
    }

    // ------------------------------------------------------------------------- open / fold

    /** The round button: the pad (or, with the setting off, the dial pad screen) */
    fun open() {
        // (with the setting off, or a window too short for it — landscape — the dial pad screen)
        if (!enabled || !fits) {
            activity.startActivity(Intent(activity.applicationContext, DialpadActivity::class.java))
            return
        }
        if (main.mainMenu.isSearchOpen) main.mainMenu.closeSearch()
        pad.root.beVisible()
        main.mainDialpadButton.hide()
        resultsWanted = true
        showResults(number.isNotEmpty())
    }

    /** Folded into the round button (what it matched stays over the list) */
    fun collapse() {
        if (!isOpen) return
        pad.root.beGone()
        main.mainDialpadButton.show()
    }

    /** Another tab, the search: the pad and its matches both go (the number stays) */
    fun hideAll() {
        collapse()
        resultsWanted = false
        showResults(false)
    }

    /** Back: the pad folds first, then the matches go with the number; false: nothing was open */
    fun onBack(): Boolean = when {
        isOpen -> {
            collapse()
            true
        }

        resultsShown -> {
            resultsWanted = false
            setNumber("")
            true
        }

        else -> false
    }

    /** The setting changed in Settings: off → back to the round button only */
    fun settingChanged() {
        if (!enabled) {
            setNumber("")
            hideAll()
        }
    }

    fun save(outState: Bundle) {
        outState.putString(STATE_NUMBER, number)
        outState.putBoolean(STATE_OPEN, isOpen)
        outState.putBoolean(STATE_RESULTS, resultsWanted)
    }

    /** At start: open on a fresh start (with the setting on), as it was after a restart */
    fun restore(savedInstanceState: Bundle?) {
        if (!enabled) return
        if (savedInstanceState == null) {
            // (not when a missed call's notification opened the app: Recents first)
            if (activity.intent?.action != Intent.ACTION_VIEW && fits) open()
            return
        }
        number = savedInstanceState.getString(STATE_NUMBER).orEmpty()
        resultsWanted = savedInstanceState.getBoolean(STATE_RESULTS)
        if (savedInstanceState.getBoolean(STATE_OPEN) && fits) open()
        numberChanged()
    }

    /** Turned / resized (the activity is kept): keys sized again; too short now → folded */
    fun configurationChanged() {
        sizeKeys()
        if (!fits) hideAll()
    }

    /** The contacts were read again: what the number matches again */
    fun contactsChanged() {
        if (number.isNotEmpty()) numberChanged()
    }

    private fun showResults(show: Boolean) {
        main.ittcDialResults.beVisibleIf(show && number.isNotEmpty())
    }

    // ------------------------------------------------------------------------- colours

    /** The palette (light / dark as the app) and the accent; again on every return to the screen */
    fun applyColors() {
        val p = IttcTheme.of(activity)
        val radius = IttcTheme.dp(activity, 24f).toFloat()
        pad.root.background = GradientDrawable().apply {
            setColor(p.surface)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        pad.ittcDpNumber.setTextColor(p.text)
        pad.ittcDpBackspace.setColorFilter(p.text2)
        pad.ittcDpMenu.setColorFilter(p.text2)
        pad.ittcDpHide.setColorFilter(p.text2)
        pad.ittcDpCall.background = RippleDrawable(
            ColorStateList.valueOf(p.call.getContrastColor().adjustAlpha(0.3f)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.call)
            },
            null
        )
        pad.ittcDpCall.setColorFilter(p.call.getContrastColor())

        val hideNumbers = activity.config.hideDialpadNumbers
        keys.forEach { (key, char) ->
            key.background = RippleDrawable(
                ColorStateList.valueOf(p.outline),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(p.surfaceAlt)
                },
                null
            )
            key.text = keyText(char, hideNumbers, p)
            // (as the dial pad screen: "hide the numbers" leaves *, + and #)
            key.visibility = if (hideNumbers && char in '1'..'9') View.INVISIBLE else View.VISIBLE
        }

        main.ittcDialResults.setBackgroundColor(activity.getProperBackgroundColor())
    }

    private fun keyText(char: Char, hideNumbers: Boolean, p: IttcTheme.Palette): CharSequence {
        val digit = if (char == '0' && hideNumbers) "+" else char.toString()
        val letters = when (char) {
            '1' -> " " // (a line as the others have: the digits stay in one row)
            '2' -> "ABC"
            '3' -> "DEF"
            '4' -> "GHI"
            '5' -> "JKL"
            '6' -> "MNO"
            '7' -> "PQRS"
            '8' -> "TUV"
            '9' -> "WXYZ"
            '0' -> if (hideNumbers) "" else "+"
            else -> ""
        }
        val out = SpannableStringBuilder(digit)
        out.setSpan(ForegroundColorSpan(p.text), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (letters.isNotEmpty()) {
            val start = out.length
            out.append('\n').append(letters)
            out.setSpan(AbsoluteSizeSpan(10, true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(p.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // ------------------------------------------------------------------------- keys

    private fun setupKeys() {
        keys.forEach { (key, char) -> setupKey(key, char) }

        pad.ittcDpBackspace.setOnClickListener {
            if (number.isNotEmpty()) setNumber(number.dropLast(1))
            haptic(it)
        }
        pad.ittcDpBackspace.setOnLongClickListener {
            setNumber("")
            true
        }
        // (hold the number: paste one)
        pad.ittcDpNumber.setOnLongClickListener {
            paste()
            true
        }
    }

    /** The key's character now ("hide the numbers" makes 0 a +) */
    private fun charOf(char: Char): Char = if (char == '0' && activity.config.hideDialpadNumbers) '+' else char

    // (as the dial pad screen's keys: typed on touch down, the tone while held, hold 0 for +, hold
    // 1–9 for its speed dial)
    @SuppressLint("ClickableViewAccessibility")
    private fun setupKey(view: View, char: Char) {
        // (typed on touch down only, so no click listener; TalkBack's "double tap" — an
        // accessibility click, not a touch — types it here)
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    type(charOf(char), host)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        view.setOnTouchListener { _, event ->
            val typed = charOf(char)
            val longClickable = typed in '0'..'9'
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    type(typed, view)
                    startTone(typed)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({ longPress(view, typed) }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTone(typed)
                    longPressHandler.removeCallbacksAndMessages(null)
                }

                MotionEvent.ACTION_MOVE -> {
                    val inside = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }
                    if (!inside) {
                        stopTone(typed)
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }
            }
            false
        }
    }

    private fun longPress(view: View, char: Char) {
        if (char == '0') {
            // (the 0 typed on touch down becomes +; a full number had no room for it)
            if (number.endsWith('0')) number = number.dropLast(1)
            type('+', view)
        } else if (number.length == 1) {
            val speedDial = activity.config.getSpeedDialValues().firstOrNull { it.id == char.digitToInt() }
            if (speedDial?.isValid() == true) {
                stopTone(char)
                setNumber("")
                call(speedDial.number, speedDial.getName(activity))
            }
        }
    }

    private fun type(char: Char, view: View?) {
        if (number.length >= MAX_LENGTH) return
        clearJob?.cancel()
        number += char
        haptic(view)
        numberChanged(byUser = true)
    }

    private fun setNumber(value: String) {
        clearJob?.cancel()
        number = value.take(MAX_LENGTH)
        numberChanged(byUser = true)
    }

    private fun paste() {
        val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
        // (any script's digits as 0–9, e.g. Bengali ০–৯; of the rest only + * # , ;)
        val cleaned = buildString {
            text.forEach { c ->
                val d = Character.digit(c, 10)
                if (d >= 0) append('0' + d) else if (c in "+*#,;") append(c)
            }
        }
        if (cleaned.isNotEmpty()) setNumber(cleaned)
    }

    private fun haptic(view: View?) {
        if (activity.config.dialpadVibration) view?.performHapticFeedback()
    }

    private fun startTone(char: Char) {
        if (activity.config.dialpadBeeps) {
            pressedKeys.add(char)
            val helper = tones ?: ToneGeneratorHelper(activity, DIALPAD_TONE_LENGTH_MS).also { tones = it }
            helper.startTone(char)
        }
    }

    private fun stopTone(char: Char) {
        if (activity.config.dialpadBeeps) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                tones?.stopTone()
            } else {
                startTone(pressedKeys.last())
            }
        }
    }

    // ------------------------------------------------------------------------- ≡, call, hide

    private fun setupActions() {
        pad.ittcDpCall.setOnClickListener { callTyped() }
        pad.ittcDpCall.setOnLongClickListener { callWithSimChoice() }
        pad.ittcDpHide.setOnClickListener { collapse() }
        pad.ittcDpMenu.setOnClickListener { showMenu(it) }
    }

    /** The green key: the number typed; nothing typed → the last number called, to call it (as the dial pad screen) */
    private fun callTyped() {
        val typed = number
        if (typed.isNotEmpty()) {
            call(typed, typed)
            return
        }
        RecentsHelper(activity).getRecentCalls(queryLimit = 1) {
            val last = it.firstOrNull()?.phoneNumber
            if (!last.isNullOrEmpty()) activity.runOnUiThread { if (number.isEmpty()) setNumber(last) }
        }
    }

    private fun callWithSimChoice(): Boolean {
        val typed = number
        return if (activity.areMultipleSIMsAvailable() && typed.isNotEmpty()) {
            activity.startCallWithConfirmationCheck(recipient = typed, name = typed, forceSimSelector = true)
            clearLater()
            true
        } else {
            false
        }
    }

    private fun call(to: String, name: String) {
        activity.startCallWithConfirmationCheck(to, name)
        clearLater()
    }

    // (as the dial pad screen: the number goes a moment after the call starts)
    private fun clearLater() {
        clearJob?.cancel()
        clearJob = activity.lifecycleScope.launch {
            delay(1000)
            number = ""
            numberChanged()
        }
    }

    private fun showMenu(anchor: View) {
        val typed = number
        PopupMenu(activity, anchor).apply {
            menu.add(Menu.NONE, MENU_HIDE, Menu.NONE, R.string.ittc_dp_hide)
            menu.add(Menu.NONE, MENU_PASTE, Menu.NONE, R.string.ittc_dp_paste)
            if (typed.isNotEmpty()) {
                menu.add(Menu.NONE, MENU_ADD_CONTACT, Menu.NONE, R.string.ittc_dp_add_contact)
                if (activity.areMultipleSIMsAvailable()) menu.add(Menu.NONE, MENU_SIM, Menu.NONE, R.string.ittc_dp_call_with_sim)
            }
            menu.add(Menu.NONE, MENU_SPEED_DIAL, Menu.NONE, R.string.manage_speed_dial)
            menu.add(Menu.NONE, MENU_FULL_SCREEN, Menu.NONE, R.string.ittc_dp_full_screen)
            menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_HIDE -> collapse()
                    MENU_PASTE -> paste()
                    MENU_ADD_CONTACT -> activity.startAddContactIntent(typed)
                    MENU_SIM -> callWithSimChoice()
                    MENU_SPEED_DIAL -> activity.startActivity(Intent(activity, ManageSpeedDialActivity::class.java))
                    MENU_FULL_SCREEN -> {
                        // (the dial pad screen takes the number as another app's "dial" link)
                        val intent = Intent(activity, DialpadActivity::class.java)
                        if (typed.isNotEmpty()) {
                            intent.action = Intent.ACTION_DIAL
                            intent.data = "tel:${android.net.Uri.encode(typed)}".toUri()
                        }
                        activity.startActivity(intent)
                    }

                    MENU_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------------- what it matches

    private class Found(val contacts: ArrayList<Contact>, val leads: List<IttcBridge.PhoneLead>, val recents: List<RecentCall>)

    /** [byUser]: typed / pasted now (only then is a secret code sent — not again on a refresh) */
    private fun numberChanged(byUser: Boolean = false) {
        val text = number
        pad.ittcDpNumberRow.beVisibleIf(text.isNotEmpty())
        pad.ittcDpNumber.text = text
        searchJob?.cancel()
        if (text.isEmpty()) {
            showResults(false)
            main.ittcDialMatches.removeAllViews()
            main.ittcDialLead.beGone()
            (main.ittcDialList.adapter as? ContactsAdapter)?.finishActMode()
            main.ittcDialList.adapter = null
            return
        }
        if (byUser && IttcDialSearch.secretCode(activity, text)) {
            // (sent once: the code goes, so a refresh or a restart cannot send it again)
            number = ""
            numberChanged()
            return
        }
        showResults(resultsWanted)
        askLeadsOnce()

        val contacts = contacts()
        val calls = callLog()
        searchJob = activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.Default) {
                search.prepare(contacts)
                val matched = search.contacts(contacts, text) { ensureActive() }
                val leads = IttcDialSearch.leads(text, 3)
                val skip = leads.mapNotNull { l -> IttcCallHistory.key(l.callNumber).ifEmpty { null } }.toHashSet()
                ensureActive()
                val recents = IttcDialSearch.recents(calls.filterIsInstance<RecentCall>(), text, matched, skip, 2)
                Found(matched, leads, recents)
            }
            if (number == text && !activity.isFinishing && !activity.isDestroyed) show(found, text)
        }
        showFullNumberLead(text)
    }

    // (the leads for the name search, read once in the background — not behind the disclosure)
    private fun askLeadsOnce() {
        if (leadsAsked || !IttcDisclosure.agreed(activity)) return
        leadsAsked = true
        val app = activity.applicationContext
        ensureBackgroundThread {
            try {
                IttcBridge.myLeads(app)
            } catch (_: Throwable) {
            }
        }
    }

    private fun show(found: Found, text: String) {
        val box = main.ittcDialMatches
        box.removeAllViews()
        val textColor = activity.getProperTextColor()
        val primary = activity.getProperPrimaryColor()
        found.leads.forEach { l ->
            val to = l.callNumber ?: return@forEach
            box.addView(
                matchRow(
                    title = "🎓 ${l.name.ifEmpty { to }}",
                    subtitle = activity.getString(R.string.ittc_dp_lead_hint, listOfNotNull(l.course, to).joinToString(" · ")),
                    titleColor = primary,
                    onClick = { call(to, l.name.ifEmpty { to }) },
                    onLongClick = { IttcBridge.openLead(activity, l.leadId) },
                )
            )
        }
        found.recents.forEach { r ->
            val title = r.name.takeIf { it.isNotBlank() && it != r.phoneNumber } ?: r.phoneNumber
            box.addView(
                matchRow(
                    title = title,
                    subtitle = activity.getString(R.string.ittc_dp_recent_hint, r.phoneNumber),
                    titleColor = textColor,
                    onClick = { call(r.phoneNumber, title) },
                )
            )
        }
        if (found.contacts.isEmpty() && found.leads.isEmpty() && found.recents.isEmpty() && text.any { it.isDigit() }) {
            box.addView(
                matchRow(
                    title = activity.getString(R.string.ittc_dp_no_match, text),
                    subtitle = null,
                    titleColor = textColor.adjustAlpha(0.8f),
                    onClick = { activity.startAddContactIntent(text) },
                )
            )
        }

        (main.ittcDialList.adapter as? ContactsAdapter)?.finishActMode()
        ContactsAdapter(
            activity = activity,
            contacts = found.contacts,
            recyclerView = main.ittcDialList,
            highlightText = text,
            allowLongClick = false,
            itemClick = { call(it as Contact) },
            profileIconClick = { activity.startContactDetailsIntent(it as Contact) },
        ).apply {
            main.ittcDialList.adapter = this
        }
        main.ittcDialList.beVisibleIf(found.contacts.isNotEmpty())
    }

    private fun call(contact: Contact) {
        activity.startCallWithConfirmationCheck(contact)
        clearLater()
    }

    // (a full number that is a lead's → who it is, at the top; a tap opens the lead)
    private fun showFullNumberLead(text: String) {
        val view = main.ittcDialLead
        // (no lead lookup behind the first-start disclosure)
        if (text.count { it.isDigit() } < 10 || !IttcDisclosure.agreed(activity)) {
            view.beGone()
            return
        }
        IttcBridge.lookup(activity, text) { lead ->
            if (activity.isFinishing || activity.isDestroyed || number != text) return@lookup
            if (lead == null) {
                view.beGone()
                return@lookup
            }
            val about = listOfNotNull(lead.name.ifEmpty { null }, lead.course, lead.stage).joinToString(" · ")
            view.text = activity.getString(R.string.ittc_dialpad_lead, about)
            view.setTextColor(activity.getProperPrimaryColor())
            view.setOnClickListener { IttcBridge.openLead(activity, lead.leadId) }
            view.beVisible()
        }
    }

    private fun matchRow(title: String, subtitle: String?, titleColor: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): View {
        val ctx = activity
        val textColor = ctx.getProperTextColor()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = IttcTheme.dp(ctx, 56f)
            setPadding(IttcTheme.dp(ctx, 20f), IttcTheme.dp(ctx, 8f), IttcTheme.dp(ctx, 20f), IttcTheme.dp(ctx, 8f))
            val ripple = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) setBackgroundResource(ripple.resourceId)
            addView(TextView(ctx).apply {
                text = title
                setTextColor(titleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (subtitle != null) {
                addView(TextView(ctx).apply {
                    text = subtitle
                    setTextColor(textColor.adjustAlpha(0.7f))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    companion object {
        private const val MAX_LENGTH = 40
        private const val KEY_MAX_DP = 60
        private const val KEY_MIN_DP = 44
        private const val KEY_GAP_DP = 4
        private const val STATE_NUMBER = "ittc_dp_number"
        private const val STATE_OPEN = "ittc_dp_open"
        private const val STATE_RESULTS = "ittc_dp_results"

        private const val MENU_HIDE = 1
        private const val MENU_PASTE = 2
        private const val MENU_ADD_CONTACT = 3
        private const val MENU_SIM = 4
        private const val MENU_SPEED_DIAL = 5
        private const val MENU_FULL_SCREEN = 6
        private const val MENU_SETTINGS = 7
    }
}
