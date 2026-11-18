package org.fossify.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val ITTC_OPEN_WITH_DIALPAD = "ittc_open_with_dialpad" // 🎓 ITTC 3.6
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val ITTC_LAST_TAB_KIND = "ittc_last_tab_kind" // 🎓 ITTC 3.8
const val ITTC_RECENTS_SEEN_AT = "ittc_recents_seen_at" // 🎓 ITTC 3.8

const val ALL_TABS_MASK = TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY

// (the tabs that can be hidden; their order on screen is [ittcTabOrder]'s)
val tabsList = arrayListOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY)

/** 🎓 ITTC: the Leads tab's kind (not a commons TAB_* bit: it is never hidden, never in the show-tabs mask) */
const val ITTC_TAB_LEADS = 1 shl 20

/** 🎓 ITTC 3.8: the tabs' order — Call History, Leads, Contacts, Favorites */
val ittcTabOrder = listOf(TAB_CALL_HISTORY, ITTC_TAB_LEADS, TAB_CONTACTS, TAB_FAVORITES)

/**
 * 🎓 ITTC 3.8: the kinds of the tabs shown, in their order on screen (Leads always). The one source
 * of every tab position: the pager's pages, the tab bar, the default tab, the last used tab.
 */
fun ittcVisibleTabs(showTabs: Int): List<Int> = ittcTabOrder.filter { it == ITTC_TAB_LEADS || showTabs and it != 0 }

private const val PATH = "org.fossify.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds
