package org.fossify.phone.helpers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.activities.IttcCUi
import org.fossify.phone.activities.IttcLeadSheet
import org.fossify.phone.direct.IttcLeadApi

/**
 * 🎓 ITTC 3.15: every "WhatsApp" in the app comes here — the user picks which WhatsApp: WhatsApp,
 * WhatsApp Business, a clone / other WhatsApp app that opens WhatsApp links (`whatsapp://`), or a copy
 * in another user profile (a dual / clone profile, a work profile).
 *
 * - none: the "no WhatsApp" toast (or the caller's own message);
 * - one: it opens at once (as before);
 * - more: a bottom sheet in the ITTC sheet style — "কোন WhatsApp দিয়ে খুলবেন?", the number, a row
 *   per app (its icon, its name and a tag), and "📋 নম্বর কপি".
 *
 * The chat opens with `https://wa.me/<number>?text=…` (else `whatsapp://send?phone=…&text=…`), with
 * the chosen package set; the number is made international with [IttcLeadApi.waNumber]. A copy in
 * another profile can't take a link into its profile: it opens on its own home screen (LauncherApps),
 * then the number (and the message) are put in the clipboard, with a toast to paste it.
 *
 * The list is kept for [TTL] ms (dropped when an app is installed / removed) and built off the main
 * thread when it is not kept.
 */
object IttcWhatsApp {
    /** What came of it: opened; failed (none here / it would not open — the toast said so); cancelled (nothing to say) */
    enum class Outcome { OPENED, FAILED, CANCELLED }

    private const val OFFICIAL = "com.whatsapp"
    private const val BUSINESS = "com.whatsapp.w4b"

    /** WhatsApp, then Business (by name, also when they don't answer the whatsapp:// question) */
    private val KNOWN = listOf(OFFICIAL, BUSINESS)

    private const val TTL = 30_000L

    /** One WhatsApp to open: in this profile ([user] null) or a copy in another ([component] its launcher activity) */
    class Choice(
        val pkg: String,
        val label: CharSequence,
        val icon: Drawable?,
        val tag: String,
        val user: UserHandle? = null,
        val component: ComponentName? = null,
    ) {
        /** WhatsApp or Business (a green tag); a clone: a blue one */
        val official get() = pkg == OFFICIAL || pkg == BUSINESS
    }

    private class Kept(val at: Long, val list: List<Choice>)

    @Volatile
    private var kept: Kept? = null

    @Volatile
    private var watching = false

    /** A choice is being worked out (the list being read, or the sheet up): a second tap waits */
    private var busy = false

    /**
     * WhatsApp with [number] (and [text] typed in, when given). [noApp]: the toast when there is no
     * WhatsApp or it would not open (null: none — the caller says it itself). [onResult] runs once,
     * on the main thread.
     */
    fun open(
        activity: Activity,
        number: String?,
        text: String? = null,
        noApp: Int? = R.string.ittc_l3_no_whatsapp,
        onResult: ((Outcome) -> Unit)? = null,
    ) {
        choose(
            activity, number, noApp,
            onChosen = { choice ->
                val outcome = launch(activity, choice, number.orEmpty(), text, noApp)
                onResult?.invoke(outcome)
            },
            onNone = { outcome -> onResult?.invoke(outcome) },
        )
    }

    /**
     * Which WhatsApp, without opening it (the papers sheet makes its link after the choice).
     * Exactly one of [onChosen] / [onNone] runs, once, on the main thread: [onNone] with FAILED
     * (no number / no WhatsApp: [noApp] toasted) or CANCELLED (the sheet closed, "copy" used,
     * the screen gone, or a choice already in progress).
     */
    fun choose(
        activity: Activity,
        number: String?,
        noApp: Int? = R.string.ittc_l3_no_whatsapp,
        onChosen: (Choice) -> Unit,
        onNone: (Outcome) -> Unit,
    ) {
        if (number.isNullOrBlank()) {
            activity.toast(R.string.ittc_lp_no_number)
            onNone(Outcome.FAILED)
            return
        }
        if (busy) {
            onNone(Outcome.CANCELLED)
            return
        }
        val fresh = kept?.takeIf { SystemClock.elapsedRealtime() - it.at < TTL }?.list
        if (fresh != null) {
            decide(activity, number, fresh, noApp, onChosen, onNone)
            return
        }
        // (not kept: read off the main thread — a few ms; a second tap meanwhile waits)
        busy = true
        val app = activity.applicationContext
        ensureBackgroundThread {
            val list = try {
                choices(app)
            } catch (_: Throwable) {
                emptyList()
            }
            activity.runOnUiThread {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) {
                    onNone(Outcome.CANCELLED)
                } else {
                    decide(activity, number, list, noApp, onChosen, onNone)
                }
            }
        }
    }

    private fun decide(
        activity: Activity,
        number: String,
        list: List<Choice>,
        noApp: Int?,
        onChosen: (Choice) -> Unit,
        onNone: (Outcome) -> Unit,
    ) {
        when (list.size) {
            0 -> {
                noApp?.let { activity.toast(it) }
                onNone(Outcome.FAILED)
            }

            1 -> onChosen(list[0])
            else -> showChooser(activity, number, list, onChosen, onNone)
        }
    }

    // ------------------------------------------------------------------ opening
    private fun waMe(digits: String, text: String?): Uri =
        Uri.parse("https://wa.me/$digits" + (text?.let { "?text=" + Uri.encode(it) } ?: ""))

    private fun scheme(digits: String, text: String?): Uri =
        Uri.parse("whatsapp://send?phone=$digits" + (text?.let { "&text=" + Uri.encode(it) } ?: ""))

    /**
     * Opens [c] with [number] (and [text]). FAILED: [noApp] toasted (a copy in another profile: its own
     * toast, the clipboard left alone).
     */
    fun launch(activity: Activity, c: Choice, number: String, text: String?, noApp: Int? = R.string.ittc_l3_no_whatsapp): Outcome {
        val digits = IttcLeadApi.waNumber(number)
        val message = text?.takeIf { it.isNotEmpty() }
        val user = c.user
        val component = c.component
        if (user != null && component != null) return launchOtherProfile(activity, component, user, digits, message)
        for (uri in listOf(waMe(digits, message), scheme(digits, message))) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(c.pkg))
                return Outcome.OPENED
            } catch (_: Throwable) {
            }
        }
        // (it would not open — e.g. removed since the list was read)
        kept = null
        noApp?.let { activity.toast(it) }
        return Outcome.FAILED
    }

    /**
     * A copy in another profile: no link reaches it, so that WhatsApp opens on its home screen; once it
     * has, the number (and the message) go to the clipboard, with a toast to paste it (and send).
     */
    private fun launchOtherProfile(activity: Activity, component: ComponentName, user: UserHandle, digits: String, text: String?): Outcome {
        val started = try {
            val la = launcherApps(activity) ?: throw IllegalStateException("no LauncherApps")
            la.startMainActivity(component, user, null, null)
            true
        } catch (_: Throwable) {
            false
        }
        if (!started) {
            kept = null
            activity.toast(R.string.ittc_wa_other_failed)
            return Outcome.FAILED
        }
        val clip = if (text == null) "+$digits" else "+$digits\n\n$text"
        if (IttcCopy.put(activity, clip)) {
            activity.toast(if (text == null) R.string.ittc_wa_dual_number_copied else R.string.ittc_wa_dual_message_copied)
        }
        return Outcome.OPENED
    }

    // ------------------------------------------------------------------ which WhatsApps are here
    /** Every WhatsApp: this profile's (WhatsApp, Business, then other apps for whatsapp://), then other profiles' (off the main thread) */
    fun choices(context: Context): List<Choice> {
        watchPackages(context)
        val pm = context.packageManager
        val own = context.packageName
        val schemeApps = handlers(pm, Intent(Intent.ACTION_VIEW, scheme("8801700000000", null)))

        val pkgs = LinkedHashSet<String>()
        KNOWN.forEach { if (it in schemeApps || isEnabled(pm, it)) pkgs += it }
        schemeApps.forEach { if (it != own) pkgs += it }

        val out = ArrayList<Choice>()
        pkgs.forEach { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) return@forEach
                out += Choice(pkg, pm.getApplicationLabel(info), safeIcon { pm.getApplicationIcon(info) }, tagOf(context, pkg))
            } catch (_: Throwable) {
            }
        }
        out += otherProfiles(context, (KNOWN + pkgs).distinct())
        kept = Kept(SystemClock.elapsedRealtime(), out)
        return out
    }

    /** An app installed / removed / changed: the kept list is read again next time */
    private fun watchPackages(context: Context) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addDataScheme("package")
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        kept = null
                    }
                }
                val app = context.applicationContext
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(receiver, filter)
                }
                watching = true
            } catch (_: Throwable) {
            }
        }
    }

    /** The packages with an activity for [intent] (every match, not only the default) */
    private fun handlers(pm: PackageManager, intent: Intent): Set<String> = try {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        list.mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun isEnabled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).enabled
    } catch (_: Throwable) {
        false
    }

    private fun tagOf(context: Context, pkg: String): String = context.getString(
        when (pkg) {
            OFFICIAL -> R.string.ittc_wa_tag_whatsapp
            BUSINESS -> R.string.ittc_wa_tag_business
            else -> R.string.ittc_wa_tag_clone
        }
    )

    private fun launcherApps(context: Context): LauncherApps? = try {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    } catch (_: Throwable) {
        null
    }

    /** WhatsApps installed in the other profiles: "WhatsApp (Dual)", "Business (Work)", "Clone (Other profile)" */
    private fun otherProfiles(context: Context, pkgs: List<String>): List<Choice> {
        val la = launcherApps(context) ?: return emptyList()
        val me = Process.myUserHandle()
        val profiles = try {
            la.profiles.filter { it != me }
        } catch (_: Throwable) {
            emptyList()
        }
        val out = ArrayList<Choice>()
        profiles.forEach { user ->
            val kind = profileKind(la, user)
            pkgs.forEach { pkg ->
                try {
                    val act = la.getActivityList(pkg, user).firstOrNull() ?: return@forEach
                    out += Choice(
                        pkg = pkg,
                        label = act.label ?: pkg,
                        icon = safeIcon { act.getBadgedIcon(0) },
                        tag = context.getString(kind, tagOf(context, pkg)),
                        user = user,
                        component = act.componentName,
                    )
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    /**
     * Dual, Work or "Other profile" (the tag's string): Android 15+ says the profile's type
     * (LauncherApps.getLauncherUserInfo); else the dual-app profiles' known ids (Samsung Dual
     * Messenger 95–99, MIUI / Android clone 999); anything else is "Other profile".
     */
    private fun profileKind(la: LauncherApps, user: UserHandle): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                when (la.getLauncherUserInfo(user)?.userType) {
                    "android.os.usertype.profile.MANAGED" -> return R.string.ittc_wa_tag_work
                    "android.os.usertype.profile.CLONE" -> return R.string.ittc_wa_tag_dual
                }
            } catch (_: Throwable) {
            }
        }
        // (a UserHandle's hash code is its user id)
        val id = user.hashCode()
        return if (id in 95..99 || id == 999) R.string.ittc_wa_tag_dual else R.string.ittc_wa_tag_other
    }

    private fun safeIcon(get: () -> Drawable?): Drawable? = try {
        get()
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ the chooser
    private fun showChooser(
        activity: Activity,
        number: String,
        choices: List<Choice>,
        onChosen: (Choice) -> Unit,
        onNone: (Outcome) -> Unit,
    ) {
        val p = IttcTheme.of(activity)
        val sheet = IttcCUi.sheet(activity)
        val body = sheet.body
        val c = activity
        busy = true
        // (one answer only: a choice, the copy row, closed, or the screen gone)
        var answered = false
        var watcher: LifecycleEventObserver? = null
        fun finish() {
            busy = false
            watcher?.let { w -> (activity as? LifecycleOwner)?.lifecycle?.removeObserver(w) }
            watcher = null
        }
        fun cancel() {
            if (answered) return
            answered = true
            finish()
            onNone(Outcome.CANCELLED)
        }
        sheet.dialog.setOnDismissListener { cancel() }

        // (the screen closes with the sheet up: the sheet goes with it, the caller hears "cancelled")
        (activity as? LifecycleOwner)?.lifecycle?.let { lifecycle ->
            val w = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    cancel()
                    dismiss(sheet)
                }
            }
            watcher = w
            lifecycle.addObserver(w)
        }

        body.addView(IttcCUi.text(c, c.getString(R.string.ittc_wa_choose), 18f, p.text, bold = true))
        body.addView(IttcCUi.text(c, number, 13f, p.muted).apply { textDirection = View.TEXT_DIRECTION_LTR }, IttcCUi.topMargin(c, 2))
        body.addView(View(c), LinearLayout.LayoutParams(1, IttcCUi.dp(c, 8)))

        choices.forEach { choice ->
            // (a kept icon is shared between sheets: each sheet draws its own copy)
            val icon = choice.icon?.let { it.constantState?.newDrawable()?.mutate() ?: it }
            body.addView(row(c, p, icon, null, choice.label.toString(), choice.tag, choice.official) {
                if (answered) return@row
                answered = true
                finish()
                dismiss(sheet)
                onChosen(choice)
            })
        }

        body.addView(IttcCUi.divider(c))
        body.addView(row(c, p, null, R.drawable.ic_copy_vector, c.getString(R.string.ittc_copy_number_row), null, false) {
            if (answered) return@row
            answered = true
            finish()
            dismiss(sheet)
            IttcCopy.number(activity, number)
            onNone(Outcome.CANCELLED)
        })

        val shown = !activity.isFinishing && !activity.isDestroyed && try {
            sheet.dialog.show()
            true
        } catch (_: Throwable) {
            false
        }
        if (!shown && !answered) {
            answered = true
            finish()
            onNone(Outcome.FAILED)
        }
    }

    private fun dismiss(sheet: IttcCUi.Sheet) {
        try {
            sheet.dialog.dismiss()
        } catch (_: Throwable) {
        }
    }

    /** One row, as the lead sheet's: a 36 dp round icon, the label, the tag pill; 52 dp, a ripple inside its corners */
    private fun row(
        c: Activity,
        p: IttcTheme.Palette,
        icon: Drawable?,
        iconRes: Int?,
        label: String,
        tag: String?,
        greenTag: Boolean,
        onClick: () -> Unit,
    ): View {
        val line = IttcCUi.row(c).apply {
            minimumHeight = IttcCUi.dp(c, 52)
            setPadding(IttcCUi.dp(c, 4), 0, IttcCUi.dp(c, 4), 0)
            background = IttcTheme.pressable(c, IttcTheme.rounded(c, p.surface, 12f), p.text)
            isClickable = true
            isFocusable = true
            contentDescription = if (tag == null) label else "$label, $tag"
            setOnClickListener { onClick() }
        }
        val size = IttcCUi.dp(c, 36)
        line.addView(
            ImageView(c).apply {
                if (icon != null) {
                    setImageDrawable(icon)
                } else {
                    setImageResource(iconRes ?: R.drawable.ittc_ic_whatsapp)
                    val (bg, fg) = if (iconRes == null) IttcLeadSheet.whatsAppColors(p) else p.surfaceAlt to p.text2
                    setColorFilter(fg)
                    val pad = IttcCUi.dp(c, 8)
                    setPadding(pad, pad, pad, pad)
                    background = IttcTheme.rounded(c, bg, 999f)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(size, size),
        )
        line.addView(
            IttcCUi.text(c, label, 15f, p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcCUi.dp(c, 14) },
        )
        if (tag != null) {
            val (bg, fg) = if (greenTag) IttcLeadSheet.whatsAppColors(p) else p.primarySoft to p.primary
            line.addView(
                IttcCUi.pill(c, tag, bg, fg).apply {
                    maxLines = 1
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = IttcCUi.dp(c, 8)
                },
            )
        }
        return line
    }
}
