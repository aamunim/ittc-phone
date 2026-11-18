package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.LAST_USED_VIEW_PAGER_PAGE
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.putPhoneAccountHandle
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(getKeyForCustomSIM(number)).apply()
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    /**
     * 🎓 ITTC 3.8: the tab last used, by kind (TAB_CALL_HISTORY, ITTC_TAB_LEADS, …), so a change of
     * the tabs' order or of the tabs shown never lands on another tab. Before 3.8 a page number was
     * kept (Contacts, Favorites, Call History, Leads order): read once into its kind.
     */
    var ittcLastTabKind: Int
        get() {
            if (prefs.contains(ITTC_LAST_TAB_KIND)) return prefs.getInt(ITTC_LAST_TAB_KIND, ittcTabOrder.first())
            val kind = if (prefs.contains(LAST_USED_VIEW_PAGER_PAGE)) {
                val oldOrder = tabsList.filter { showTabs and it != 0 } + ITTC_TAB_LEADS
                oldOrder.getOrNull(lastUsedViewPagerPage) ?: ittcTabOrder.first()
            } else {
                ittcTabOrder.first()
            }
            prefs.edit().putInt(ITTC_LAST_TAB_KIND, kind).apply()
            return kind
        }
        set(kind) = prefs.edit().putInt(ITTC_LAST_TAB_KIND, kind).apply()

    /** 🎓 ITTC 3.8: when Call History was last looked at (its tab's missed-calls badge counts the ones after; 0: never) */
    var ittcRecentsSeenAt: Long
        get() = prefs.getLong(ITTC_RECENTS_SEEN_AT, 0L)
        set(at) = prefs.edit().putLong(ITTC_RECENTS_SEEN_AT, at).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    // 🎓 ITTC 3.6: the dial pad open on the main screen when the app starts (as on MIUI); off: the
    // round button opens it, as before
    var ittcOpenWithDialpad: Boolean
        get() = prefs.getBoolean(ITTC_OPEN_WITH_DIALPAD, true)
        set(open) = prefs.edit().putBoolean(ITTC_OPEN_WITH_DIALPAD, open).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()
}
