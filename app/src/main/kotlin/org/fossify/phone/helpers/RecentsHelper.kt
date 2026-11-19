package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import android.telephony.PhoneNumberUtils
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.BlockedNumber
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.RecentCall
import org.fossify.phone.models.SIMAccount

class RecentsHelper(private val context: Context) {
    companion object {
        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        const val QUERY_LIMIT = 100
    }

    private val contentUri = Calls.CONTENT_URI
    private var queryLimit = QUERY_LIMIT

    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }

        // 🎓 ITTC 3.13: the contacts read once and shared (not every contact read again on each refresh)
        IttcContactsCache.get(context) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                this.queryLimit = queryLimit
                // 🎓 ITTC 3.13: the look-up tables, the SIM cards and the blocked numbers once for this
                // read (both halves below use them)
                val index = MatchIndex(contacts)
                val sims = HashMap<String, SIMAccount>().also { map ->
                    context.getAvailableSIMCardLabels().forEach { map[it.handle.id] = it }
                }
                val blockedNumbers = context.getBlockedNumbers()
                val recentCalls = if (previousRecents.isNotEmpty()) {
                    val previousRecentCalls = previousRecents
                        .flatMap { it.groupedCalls ?: listOf(it) }
                        .map { it.copy(groupedCalls = null) }

                    val newerRecents = getRecents(
                        contacts = contacts,
                        index = index,
                        accountIdToSimAccountMap = sims,
                        blockedNumbers = blockedNumbers,
                        selection = "${Calls.DATE} > ?",
                        selectionParams = arrayOf("${previousRecentCalls.first().startTS}")
                    )

                    val olderRecents = getRecents(
                        contacts = contacts,
                        index = index,
                        accountIdToSimAccountMap = sims,
                        blockedNumbers = blockedNumbers,
                        selection = "${Calls.DATE} < ?",
                        selectionParams = arrayOf("${previousRecentCalls.last().startTS}")
                    )

                    newerRecents + previousRecentCalls + olderRecents
                } else {
                    getRecents(contacts, index, sims, blockedNumbers)
                }

                callback(
                    recentCalls
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }
                )
            }
        }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        getRecentCalls(previousRecents, queryLimit) { recentCalls ->
            callback(
                groupSubsequentCalls(calls = recentCalls)
            )
        }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        val differentSim = callA.simID != callB.simID
        val differentDay = callA.dayCode != callB.dayCode
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                    callA.name != callA.phoneNumber &&
                    callB.name != callB.phoneNumber

        if (differentSim || differentDay || namesAreBothRealAndDifferent) return false

        @Suppress("DEPRECATION")
        return PhoneNumberUtils.compare(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        val result = mutableListOf<RecentCall>()
        if (calls.isEmpty()) return result

        var currentCall = calls[0]
        for (i in 1 until calls.size) {
            val nextCall = calls[i]
            if (shouldGroupCalls(currentCall, nextCall)) {
                if (currentCall.groupedCalls.isNullOrEmpty()) {
                    currentCall = currentCall.copy(groupedCalls = mutableListOf(currentCall))
                }

                currentCall.groupedCalls?.add(nextCall)
            } else {
                result += currentCall
                currentCall = nextCall
            }
        }

        result.add(currentCall)
        return result
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: List<Contact>,
        index: MatchIndex,
        accountIdToSimAccountMap: Map<String, SIMAccount>,
        blockedNumbers: ArrayList<BlockedNumber>,
        selection: String? = null,
        selectionParams: Array<String>? = null,
    ): List<RecentCall> {
        val recentCalls = mutableListOf<RecentCall>()
        var previousStartTS = 0L
        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()

        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.CACHED_PHOTO_URI,
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NUMBER_PRESENTATION
        )

        val cursor = if (isNougatPlus()) {
            // https://issuetracker.google.com/issues/175198972?pli=1#comment6
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, queryLimit.toString())
                .build()
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)
        } else {
            val sortOrder = "${Calls.DATE} DESC LIMIT $queryLimit"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        }

        // 🎓 ITTC 3.13: `index` — look-up tables instead of walking every contact for every call
        //   (a 2 GB phone spent seconds here: calls × contacts number comparisons). Each gives the
        //   same contact the old walk found: the first one in the list that matches.
        val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
        val numbersToContactIDMap = HashMap<String, Int>()
        contactsWithMultipleNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                numbersToContactIDMap[phoneNumber.value] = contact.contactId
                numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
            }
        }

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                if (name == number && !isUnknownNumber) {
                    if (contactsNumbersMap.containsKey(number)) {
                        name = contactsNumbersMap[number]!!
                    } else {
                        val normalizedNumber = number.normalizePhoneNumber()
                        if (normalizedNumber!!.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                            val contact = index.byFirstNumberEnd(normalizedNumber)
                            if (contact != null) contactsNumbersMap[number] = contact.getNameToDisplay()
                            name = contact?.name ?: number
                        }
                    }
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI) ?: ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    if (contactPhotosMap.containsKey(number)) {
                        photoUri = contactPhotosMap[number]!!
                    } else {
                        val contact = index.havingNumber(number)
                        if (contact != null) {
                            photoUri = contact.photoUri
                            contactPhotosMap[number] = contact.photoUri
                        }
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)
                if (previousStartTS == startTS) {
                    continue
                } else {
                    previousStartTS = startTS
                }

                val duration = cursor.getIntValue(Calls.DURATION)
                val type = cursor.getIntValue(Calls.TYPE)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]
                var specificNumber = ""
                var specificType = ""

                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber =
                        index.byContactId(contactIdWithMultipleNumbers)?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simAccount?.id ?: -1,
                        simColor = simAccount?.color ?: -1,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber
                    )
                )
            } while (cursor.moveToNext() && recentCalls.size < queryLimit)
        }

        return recentCalls
            .filter { !context.isNumberBlocked(it.phoneNumber, blockedNumbers) }
    }

    fun removeRecentCalls(ids: List<Int>, callback: () -> Unit) {
        ensureBackgroundThread {
            ids.chunked(30).forEach { chunk ->
                val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.delete(contentUri, selection, selectionArgs)
            }
            callback()
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
            if (it) {
                ensureBackgroundThread {
                    context.contentResolver.delete(contentUri, null, null)
                    callback()
                }
            }
        }
    }

    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (granted) {
                ensureBackgroundThread {
                    val values = objects
                        .sortedBy { it.startTS }
                        .map {
                            ContentValues().apply {
                                put(Calls.NUMBER, it.phoneNumber)
                                put(Calls.TYPE, it.type)
                                put(Calls.DATE, it.startTS)
                                put(Calls.DURATION, it.duration)
                                put(Calls.CACHED_NAME, it.name)
                            }
                        }.toTypedArray()

                    context.contentResolver.bulkInsert(contentUri, values)
                    callback()
                }
            }
        }
    }

    /**
     * 🎓 ITTC 3.13: the old walks through the contact list, as look-ups. Each answers exactly what
     * its walk answered: the first contact in the list that matches.
     */
    private class MatchIndex(private val contacts: List<Contact>) {
        /** The last 9 characters of each contact's first number (the call log name walk) */
        private val firstNumberEnds = HashMap<String, Contact>().also { map ->
            contacts.forEach { contact ->
                val cur = contact.phoneNumbers.firstOrNull()?.normalizedNumber ?: return@forEach
                if (cur.length >= COMPARABLE_PHONE_NUMBER_LENGTH) map.putIfAbsent(cur.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH), contact)
            }
        }

        fun byFirstNumberEnd(normalizedNumber: String): Contact? =
            firstNumberEnds[normalizedNumber.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)]

        /** Each contact's place in the list, by contact id (the first one, as firstOrNull found it) */
        private val byId = HashMap<Int, Contact>().also { map -> contacts.forEach { map.putIfAbsent(it.contactId, it) } }

        fun byContactId(id: Int): Contact? = byId[id]

        /**
         * Contact.doesHavePhoneNumber, without trying every contact: two numbers it calls the same
         * always end in the same 7 digits (PhoneNumberUtils.compare needs at least 7 matching from
         * the end), so only the contacts with a number ending in those 7 digits are tried — in their
         * list order. Numbers with fewer than 7 digits are tried the old way.
         */
        private val byLastSeven = HashMap<String, MutableList<Int>>()
        private val short = ArrayList<Int>()

        init {
            contacts.forEachIndexed { i, contact ->
                val keys = HashSet<String>()
                var hasShort = false
                contact.phoneNumbers.forEach { phone ->
                    // (what doesHavePhoneNumber compares: the stored normalized number, and it normalized again)
                    for (n in listOf(phone.normalizedNumber, phone.normalizedNumber.normalizePhoneNumber())) {
                        val key = lastSeven(n)
                        if (key == null) hasShort = true else keys.add(key)
                    }
                }
                keys.forEach { byLastSeven.getOrPut(it) { ArrayList() }.add(i) }
                if (hasShort) short.add(i)
            }
        }

        fun havingNumber(number: String): Contact? {
            val key = lastSeven(number.normalizePhoneNumber())
            if (key == null) return contacts.firstOrNull { it.doesHavePhoneNumber(number) }
            val tries = ((byLastSeven[key] ?: emptyList<Int>()) + short).distinct().sorted()
            for (i in tries) {
                if (contacts[i].doesHavePhoneNumber(number)) return contacts[i]
            }
            return null
        }

        /** The last 7 digits, or null when there are fewer */
        private fun lastSeven(n: String): String? {
            val digits = StringBuilder(7)
            var i = n.length - 1
            while (i >= 0 && digits.length < 7) {
                val c = n[i]
                if (c in '0'..'9') digits.append(c)
                i--
            }
            return if (digits.length == 7) digits.reverse().toString() else null
        }
    }
}
