package org.fossify.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact

/**
 * 🎓 ITTC 3.13: the phone's contacts, read once and shared.
 *
 * The call history, the main screen and the dial pad all asked for every contact (every field) on
 * each refresh, sometimes two at the same moment. On a 2 GB phone that read is the slowest part of
 * opening Call History. Now:
 *  - one read for everyone: a read already running is joined, not started again;
 *  - kept until the phone's contacts change (a content observer marks it old), then read again.
 *
 * The list is exactly ContactsHelper.getContacts(getAll = true, showOnlyContactsWithNumbers = true),
 * the same call every user made before; each caller gets its own copy of the list (the contacts in
 * it are shared and never changed). The callback runs on the main thread, as ContactsHelper's does.
 */
object IttcContactsCache {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private var contacts: ArrayList<Contact>? = null
    private var stale = true
    private var loading = false
    private var loadingAt = 0L
    private val waiting = ArrayList<(ArrayList<Contact>) -> Unit>()
    private var observer: ContentObserver? = null
    /** The name order and sorting the kept list was read with (a contact's name is made when it is read) */
    private var readWith: Pair<Int, Boolean>? = null

    private const val STUCK_MS = 20_000L

    /** Goes up each time a new read is kept */
    @Volatile
    var version = 0
        private set

    /** The contacts (a copy); fresh = read again even if nothing is known to have changed */
    fun get(context: Context, fresh: Boolean = false, callback: (ArrayList<Contact>) -> Unit) {
        val app = context.applicationContext
        watch(app)
        val settings = app.baseConfig.let { it.sorting to it.startNameWithSurname }
        synchronized(lock) {
            val kept = contacts
            if (kept != null && !stale && !fresh && readWith == settings) {
                val copy = ArrayList(kept)
                main.post { callback(copy) }
                return
            }
            waiting += callback
            // (a read already running: wait for it — unless it never answered: a read that died
            // on its own thread must not keep everyone waiting, so after 20 s a new one starts)
            val now = System.currentTimeMillis()
            if (loading && now - loadingAt < STUCK_MS) return
            loading = true
            loadingAt = now
            readWith = settings
            // (a change during the read marks it old again: read once more next time)
            stale = false
        }
        try {
            ContactsHelper(app).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { list ->
                val waiters: List<(ArrayList<Contact>) -> Unit>
                synchronized(lock) {
                    contacts = ArrayList(list)
                    version++
                    // (none: maybe no contacts permission yet — read again next time, it is quick then)
                    if (list.isEmpty()) stale = true
                    loading = false
                    waiters = ArrayList(waiting)
                    waiting.clear()
                }
                waiters.forEach { it(ArrayList(list)) }
            }
        } catch (e: Exception) {
            // (no permission or a broken provider: nobody keeps waiting)
            val waiters: List<(ArrayList<Contact>) -> Unit>
            synchronized(lock) {
                loading = false
                stale = true
                waiters = ArrayList(waiting)
                waiting.clear()
            }
            main.post { waiters.forEach { it(ArrayList()) } }
        }
    }

    /** The kept contacts are old (a contact was added, changed or removed) */
    fun markStale() {
        synchronized(lock) { stale = true }
    }

    private fun watch(app: Context) {
        synchronized(lock) {
            if (observer != null) return
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = markStale()
            }
            try {
                app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, o)
                observer = o
            } catch (ignored: Exception) {
                // (no contacts permission yet: the list is read afresh each time, as before)
            }
        }
    }
}
