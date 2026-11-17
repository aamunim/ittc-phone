package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isConference
import org.fossify.phone.models.CallContact

fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    ensureBackgroundThread {
        val callContact = CallContact("", "", "", "")
        val handle = try {
            call?.details?.handle?.toString()
        } catch (e: NullPointerException) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (uri.startsWith("tel:")) {
            val number = uri.substringAfter("tel:")
            val shownNumber = if (context.config.formatPhoneNumbers) number.formatPhoneNumber() else number

            // 🎓 ITTC: the name at once. Android's own number lookup answers in milliseconds;
            // loading every contact first (below) took 5-10 s on a phone with many contacts.
            val quick = quickLookup(context, number)
            if (quick != null) {
                callContact.number = shownNumber
                callContact.name = quick.name
                callContact.photoUri = quick.photoUri
                callContact.numberLabel = quick.label
                callback(callContact)
                return@ensureBackgroundThread
            }
            // (not a phone contact: show the number now; the full search below still finds a
            // contact kept only in Fossify's private list)
            callback(CallContact(shownNumber, "", shownNumber, ""))

            ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
                val numbersToContactIDMap = HashMap<String, Int>()
                contactsWithMultipleNumbers.forEach { contact ->
                    contact.phoneNumbers.forEach { phoneNumber ->
                        numbersToContactIDMap[phoneNumber.value] = contact.contactId
                        numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
                    }
                }

                callContact.number = if (context.config.formatPhoneNumbers) {
                    number.formatPhoneNumber()
                } else {
                    number
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                if (contact != null) {
                    callContact.name = contact.getNameToDisplay()
                    callContact.photoUri = contact.photoUri

                    if (contact.phoneNumbers.size > 1) {
                        val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                        if (specificPhoneNumber != null) {
                            callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                        }
                    }
                } else {
                    callContact.name = callContact.number
                }

                callback(callContact)
            }
        }
    }
}

private class QuickContact(val name: String, val photoUri: String, val label: String)

/** The phone contact with this number, by Android's own indexed lookup (null: none / not allowed) */
private fun quickLookup(context: Context, number: String): QuickContact? {
    if (number.isBlank()) return null
    return try {
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(
            lookup,
            arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.TYPE,
                ContactsContract.PhoneLookup.LABEL,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return null
            val type = if (c.isNull(2)) -1 else c.getInt(2)
            val label = if (type < 0) "" else try {
                context.getPhoneNumberTypeText(type, c.getString(3).orEmpty())
            } catch (_: Throwable) {
                ""
            }
            QuickContact(name, c.getString(1).orEmpty(), label)
        }
    } catch (_: Throwable) {
        null // (no contacts permission, …: the full search below)
    }
}
