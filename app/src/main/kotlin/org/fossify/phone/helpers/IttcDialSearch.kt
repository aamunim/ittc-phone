package org.fossify.phone.helpers

import android.content.Intent
import android.provider.Telephony.Sms.Intents.SECRET_CODE_ACTION
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.KeypadHelper
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.models.RecentCall
import java.util.IdentityHashMap
import java.util.Locale

/**
 * 🎓 ITTC 3.6: what the keys typed match — the dial pad screen's and the main screen's dial pad
 * share it. Contacts by number or by name as keys ("Rahim" → "72446"), your own leads (the Leads
 * tab's) and numbers from Recents that are not saved. Everything but [secretCode] runs off the main
 * thread.
 */
class IttcDialSearch {
    private val russian = Locale.getDefault().language == "ru"

    // each contact's name as keys, worked out once per list (not on every key press); by the object
    // itself: a private contact can share an id with a phone contact
    @Volatile
    private var namesOf: List<Contact>? = null

    @Volatile
    private var names: Map<Contact, String> = emptyMap()

    /** The names as keys for this list (nothing to do when it is the list of last time) */
    fun prepare(contacts: List<Contact>) {
        if (namesOf === contacts) return
        names = IdentityHashMap<Contact, String>().apply { contacts.forEach { put(it, t9Name(it)) } }
        namesOf = contacts
    }

    /** The contacts with the typed number in a number or in the name (number matches first) */
    fun contacts(contacts: List<Contact>, text: String, check: () -> Unit = {}): ArrayList<Contact> {
        val known = if (namesOf === contacts) names else emptyMap()
        return contacts.filter { contact ->
            check()
            val convertedName = known[contact] ?: t9Name(contact)
            contact.doesContainPhoneNumber(text) || convertedName.contains(text, true)
        }.sortedWith(compareBy { !it.doesContainPhoneNumber(text) }).toCollection(ArrayList())
    }

    /** A contact's name as dial-pad digits ("Rahim" → "72446") */
    fun t9Name(contact: Contact): String {
        val convertedName = t9(contact.name)
        if (!russian) return convertedName
        val out = StringBuilder()
        convertedName.lowercase(Locale.getDefault()).forEach { char -> out.append(RUSSIAN[char] ?: char) }
        return out.toString()
    }

    companion object {
        private val RUSSIAN by lazy {
            hashMapOf(
                'а' to '2', 'б' to '2', 'в' to '2', 'г' to '2',
                'д' to '3', 'е' to '3', 'ё' to '3', 'ж' to '3', 'з' to '3',
                'и' to '4', 'й' to '4', 'к' to '4', 'л' to '4',
                'м' to '5', 'н' to '5', 'о' to '5', 'п' to '5',
                'р' to '6', 'с' to '6', 'т' to '6', 'у' to '6',
                'ф' to '7', 'х' to '7', 'ц' to '7', 'ч' to '7',
                'ш' to '8', 'щ' to '8', 'ъ' to '8', 'ы' to '8',
                'ь' to '9', 'э' to '9', 'ю' to '9', 'я' to '9'
            )
        }

        /** A name as dial-pad digits, spaces left out */
        fun t9(name: String): String =
            KeypadHelper.convertKeypadLettersToDigits(name.normalizeString()).filterNot { it.isWhitespace() }

        /**
         * Your leads whose name (by the keys) or number matches: two keys or more; a number
         * from four digits (the last list read: [IttcBridge.myLeads] fills it — 3.11, direct mode:
         * every lead you may see, your own first, as the Tracker's search)
         */
        fun leads(text: String, max: Int = 3): List<IttcBridge.PhoneLead> {
            val digits = text.filter { it.isDigit() }
            // (🎓 3.12: Personal mode — no lead matches on the dial pads)
            if (digits.length < 2 || IttcMode.personalNow()) return emptyList()
            val leads = IttcBridge.myLeadsCache
            if (leads.isEmpty()) return emptyList()
            return leads.asSequence().filter { l ->
                l.callNumber != null && (
                    t9(l.name).contains(digits) ||
                        (digits.length >= 4 && listOfNotNull(l.phone, l.altPhone).any { it.filter(Char::isDigit).contains(digits) })
                    )
            }.take(max).toList()
        }

        /**
         * Numbers from Recents with the typed digits that are not one of [contacts] (a saved
         * contact is in that list already) nor one of [skip] (numbers as [IttcCallHistory.key]),
         * newest first, each number once
         */
        fun recents(calls: List<RecentCall>, text: String, contacts: List<Contact>, skip: Set<String>, max: Int = 3): List<RecentCall> {
            val digits = text.filter { it.isDigit() }
            if (digits.length < 3) return emptyList()
            val seen = HashSet<String>(skip)
            val firstContacts = contacts.take(30)
            val out = ArrayList<RecentCall>()
            for (call in calls) {
                if (out.size >= max) break
                if (call.isUnknownNumber) continue
                val key = IttcCallHistory.key(call.phoneNumber)
                if (key.isEmpty() || !call.phoneNumber.filter(Char::isDigit).contains(digits)) continue
                if (!seen.add(key)) continue
                if (firstContacts.any { it.doesHavePhoneNumber(call.phoneNumber) }) continue
                out += call
            }
            return out
        }

        /** "*#*#code#*#*": handed to Android as the phone app does; true when it was one */
        fun secretCode(activity: SimpleActivity, text: String): Boolean {
            if (!(text.length > 8 && text.startsWith("*#*#") && text.endsWith("#*#*"))) return false
            val secretCode = text.substring(4, text.length - 4)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (activity.isDefaultDialer()) {
                    activity.getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
                } else {
                    // 🎓 ITTC: the disclosure first (Play)
                    IttcDisclosure.showOnce(activity) { activity.launchSetDefaultDialerIntent() }
                }
            } else {
                activity.sendBroadcast(Intent(SECRET_CODE_ACTION, "android_secret_code://$secretCode".toUri()))
            }
            return true
        }
    }
}
