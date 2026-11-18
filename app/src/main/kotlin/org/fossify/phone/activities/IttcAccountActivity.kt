package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAccountBinding
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcReminders
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcDisclosure
import org.json.JSONObject

/**
 * 🎓 ITTC Phone 3.2: the ITTC account. Signed out: email + password (then ITTC Phone works without
 * the Tracker). Signed in: who, how it works now, last update, calls waiting to be sent, the
 * switches, sign out.
 */
class IttcAccountActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAccountBinding::inflate)

    private var working = false
    private var updating = false
    private var observer: ContentObserver? = null
    private val main = Handler(Looper.getMainLooper())

    /** What the screen shows (read off the main thread: files, prefs, the Tracker) */
    private class State(
        val configured: Boolean,
        val signedIn: Boolean,
        val me: JSONObject?,
        val email: String?,
        val active: Boolean,
        val lastRefresh: Long,
        val refreshError: String?,
        val pending: Int,
        val trackerInstalled: Boolean,
        val preferDirect: Boolean,
        val reminders: Boolean,
        val unknownAfterCall: Boolean,
        val updateNeeded: Boolean,
        val message: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomImeAndSystem = listOf(ittcAccountScrollview))
            setupMaterialScrollListener(ittcAccountScrollview, ittcAccountAppbar)

            ittcAccountSignIn.setOnClickListener { trySignIn() }
            ittcAccountPassword.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    trySignIn()
                    true
                } else {
                    false
                }
            }
            ittcAccountOpenPlay.setOnClickListener { openPlay(this@IttcAccountActivity) }
            ittcAccountSendNow.setOnClickListener {
                IttcDirect.kick(applicationContext)
                // (the list tells when something was sent; this is for "nothing to send / no internet")
                main.postDelayed({ refreshUi() }, 3000)
            }
            ittcAccountUpdateNow.setOnClickListener {
                updating = true
                ittcAccountUpdateNow.isEnabled = false
                ittcAccountUpdateNow.setText(R.string.ittc_account_updating)
                IttcDirect.refreshAsync(applicationContext, force = true)
                // (a refresh already running sends no new "changed")
                main.postDelayed({ doneUpdating() }, 20_000)
            }

            ittcAccountPreferDirectHolder.setOnClickListener {
                ittcAccountPreferDirect.toggle()
                val on = ittcAccountPreferDirect.isChecked
                val app = applicationContext
                ensureBackgroundThread { IttcDirect.setPreferDirect(app, on) }
            }
            ittcAccountRemindersHolder.setOnClickListener {
                ittcAccountReminders.toggle()
                setReminders(ittcAccountReminders.isChecked)
            }
            ittcAccountUnknownAfterCallHolder.setOnClickListener {
                ittcAccountUnknownAfterCall.toggle()
                IttcDirect.setUnknownAfterCall(applicationContext, ittcAccountUnknownAfterCall.isChecked)
            }
            ittcAccountSignOut.setOnClickListener { askSignOut() }
        }
        updateTextColors(binding.ittcAccountScrollview)

        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                doneUpdating()
                refreshUi()
            }
        }.also { IttcDirect.addObserver(it) }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcAccountAppbar, NavigationIcon.Arrow)
        refreshUi()
    }

    override fun onDestroy() {
        IttcDirect.removeObserver(observer)
        observer = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================== showing
    private fun refreshUi() {
        val app = applicationContext
        ensureBackgroundThread {
            val configured = IttcDirect.configured
            val signedIn = IttcDirect.isSignedIn(app)
            val message = IttcDirect.lastMessage(app)
            // (shown once)
            if (message != null) IttcDirect.clearMessage(app)
            val state = State(
                configured = configured,
                signedIn = signedIn,
                me = if (signedIn) IttcDirect.me(app) else null,
                email = if (signedIn) IttcDirect.email(app) else null,
                active = signedIn && IttcDirect.isActive(app),
                lastRefresh = IttcDirect.lastRefresh(app),
                refreshError = IttcDirect.refreshError(app),
                pending = if (signedIn) IttcDirect.pendingCount(app) else 0,
                trackerInstalled = IttcBridge.trackerInstalled(app),
                preferDirect = IttcDirect.preferDirect(app),
                reminders = IttcReminders.enabled(app),
                unknownAfterCall = IttcDirect.unknownAfterCall(app),
                updateNeeded = signedIn && IttcDirect.updateNeeded(app),
                message = message,
            )
            runOnUiThread {
                if (!isFinishing && !isDestroyed) show(state)
            }
        }
    }

    private fun show(s: State) {
        binding.apply {
            ittcAccountNotConfigured.beVisibleIf(!s.configured)
            if (s.message != null) {
                ittcAccountMessage.text = s.message
                ittcAccountMessage.beVisible()
            } else if (s.signedIn) {
                ittcAccountMessage.beGone()
            }
            ittcAccountUpdateBox.beVisibleIf(s.updateNeeded)

            ittcAccountSignedOutBox.beVisibleIf(s.configured && !s.signedIn)
            ittcAccountSignedInBox.beVisibleIf(s.signedIn)
            ittcAccountSignOut.beVisibleIf(s.signedIn)
            ittcAccountUnknownAfterCallHolder.beVisibleIf(s.signedIn)
            ittcAccountPreferDirectHolder.beVisibleIf(s.signedIn && s.trackerInstalled)

            ittcAccountPreferDirect.isChecked = s.preferDirect
            ittcAccountReminders.isChecked = s.reminders
            ittcAccountUnknownAfterCall.isChecked = s.unknownAfterCall

            if (s.signedIn) {
                val me = s.me
                val name = me?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                ittcAccountName.text = name ?: s.email ?: getString(R.string.ittc_account_signed_in)
                ittcAccountEmailText.text = s.email.orEmpty()
                ittcAccountEmailText.beVisibleIf(!s.email.isNullOrBlank() && name != null)
                val role = roleText(me)
                ittcAccountRole.text = role
                ittcAccountRole.beVisibleIf(role.isNotEmpty())

                ittcAccountStatus.setText(if (s.active) R.string.ittc_account_status_direct else R.string.ittc_account_status_tracker)
                ittcAccountLastUpdated.text = if (s.lastRefresh > 0) {
                    getString(
                        R.string.ittc_account_last_updated,
                        DateUtils.getRelativeTimeSpanString(s.lastRefresh, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    )
                } else {
                    getString(R.string.ittc_account_never_updated)
                }
                val err = s.refreshError?.takeIf { it.isNotBlank() }
                ittcAccountRefreshError.text = if (err != null) getString(R.string.ittc_account_refresh_error, err) else ""
                ittcAccountRefreshError.beVisibleIf(err != null)

                ittcAccountPendingRow.beVisibleIf(s.pending > 0)
                ittcAccountPending.text = getString(R.string.ittc_account_waiting, s.pending)
            }
        }
        updateTextColors(binding.ittcAccountScrollview)
    }

    /** "Admin · Team lead" from {role, super, team_lead} */
    private fun roleText(me: JSONObject?): String {
        if (me == null) return ""
        val parts = ArrayList<String>()
        if (me.optBoolean("super", false)) {
            parts += "Super admin"
        } else {
            me.optString("role").takeIf { it.isNotBlank() && it != "null" }?.let { r ->
                parts += r.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }
        if (me.optBoolean("team_lead", false)) parts += "Team lead"
        return parts.joinToString(" · ")
    }

    private fun doneUpdating() {
        if (!updating) return
        updating = false
        if (isFinishing || isDestroyed) return
        binding.ittcAccountUpdateNow.isEnabled = true
        binding.ittcAccountUpdateNow.setText(R.string.ittc_account_update_now)
    }

    // ================================================================== sign in
    private fun trySignIn() {
        if (working) return
        val email = binding.ittcAccountEmail.text?.toString()?.trim().orEmpty()
        val password = binding.ittcAccountPassword.text?.toString().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.ittc_account_fill_in))
            return
        }
        // (nothing is sent before the person agreed)
        if (!IttcDisclosure.agreed(this)) {
            IttcDisclosure.showOnce(this) { trySignIn() }
            return
        }
        hideKeyboard()
        setWorking(true)
        val app = applicationContext
        ensureBackgroundThread {
            val error = try {
                IttcDirect.signIn(app, email, password)
                null
            } catch (e: IllegalStateException) {
                e.message ?: getString(R.string.ittc_account_sign_in_failed)
            } catch (_: Throwable) {
                getString(R.string.ittc_account_sign_in_failed)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setWorking(false)
                if (error != null) {
                    showError(error)
                } else {
                    binding.ittcAccountPassword.setText("")
                    binding.ittcAccountMessage.beGone()
                    // (signIn fetched the lists already; this one is throttled and only fills gaps)
                    IttcDirect.refreshAsync(app)
                    refreshUi()
                    if (IttcReminders.enabled(app)) handleNotificationPermission { }
                }
            }
        }
    }

    private fun setWorking(on: Boolean) {
        working = on
        binding.apply {
            ittcAccountSignIn.isEnabled = !on
            ittcAccountEmail.isEnabled = !on
            ittcAccountPassword.isEnabled = !on
            ittcAccountProgress.beVisibleIf(on)
            if (on) ittcAccountError.beGone()
        }
    }

    private fun showError(text: String) {
        binding.ittcAccountError.text = text
        binding.ittcAccountError.beVisible()
    }

    // ================================================================== switches
    private fun setReminders(on: Boolean) {
        if (on) {
            handleNotificationPermission { granted ->
                if (!granted) toast(R.string.ittc_account_notifications_off)
            }
        }
        val app = applicationContext
        ensureBackgroundThread {
            // (off → every alarm goes; on → set from the kept list)
            IttcReminders.setEnabled(app, on)
            if (on) {
                val leads = if (IttcDirect.isSignedIn(app)) IttcDirect.leads(app) else IttcBridge.myLeadsCache
                if (leads.isNotEmpty()) IttcReminders.schedule(app, leads)
            }
        }
    }

    // ================================================================== sign out
    private fun askSignOut() {
        val app = applicationContext
        // (signing out drops what is not sent yet: say so, and offer to send it first)
        ensureBackgroundThread {
            val pending = IttcDirect.pendingCount(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val text = if (pending > 0) {
                    getString(R.string.ittc_account_sign_out_confirm) + "\n\n" + getString(R.string.ittc_account_sign_out_unsent, pending)
                } else {
                    getString(R.string.ittc_account_sign_out_confirm)
                }
                val builder = AlertDialog.Builder(this)
                    .setMessage(text)
                    .setPositiveButton(R.string.ittc_account_sign_out) { _, _ -> signOut() }
                    .setNegativeButton(R.string.cancel, null)
                if (pending > 0) {
                    builder.setNeutralButton(R.string.ittc_account_send_now) { _, _ ->
                        IttcDirect.kick(app)
                        main.postDelayed({ refreshUi() }, 3000)
                    }
                }
                builder.show()
            }
        }
    }

    private fun signOut() {
        val app = applicationContext
        ensureBackgroundThread {
            IttcDirect.signOut(app)
            runOnUiThread { if (!isFinishing && !isDestroyed) refreshUi() }
        }
    }

    companion object {
        private val PLAY_ID get() = BuildConfig.APPLICATION_ID.removeSuffix(".debug")

        /** ITTC Phone on Google Play (the Play app, else the web page) */
        fun openPlay(activity: Activity) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_ID")))
            } catch (_: Throwable) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PLAY_ID")))
                } catch (_: Throwable) {
                    activity.toast(R.string.ittc_about_no_browser)
                }
            }
        }
    }
}
