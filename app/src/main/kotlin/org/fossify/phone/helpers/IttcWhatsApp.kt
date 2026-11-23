package org.fossify.phone.helpers

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.activities.IttcCUi
import org.fossify.phone.activities.IttcLeadSheet
import org.fossify.phone.direct.IttcLeadApi

/**
 * 🎓 ITTC 3.15: every "WhatsApp" in the app comes here — the user picks which WhatsApp: WhatsApp,
 * WhatsApp Business, a clone (GB / YO / FM…, or any other app that takes `whatsapp://`), or a dual
 * copy in another user profile (Samsung Dual Messenger, MIUI Dual apps, a work profile).
 *
 * - none: the "no WhatsApp" toast (or the caller's own message);
 * - one: it opens at once (as before);
 * - more: a bottom sheet in the ITTC sheet style — "কোন WhatsApp দিয়ে খুলবেন?", the number, a row
 *   per app (its icon, its name and a tag), and "📋 নম্বর কপি".
 *
 * The chat opens with `https://wa.me/<number>?text=…` (else `whatsapp://send?phone=…&text=…`), with
 * the chosen package set; the number is made international with [IttcLeadApi.waNumber]. A dual copy
 * can't take a link into its profile: it opens on its own home screen (LauncherApps) after the number
 * (and the message) is copied, with a toast to paste it.
 */
object IttcWhatsApp {
    /** WhatsApp, Business, then the clones / mods people use (also kept when they only take wa.me) */
    private val KNOWN = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.gbwhatsapp",
        "com.yowhatsapp",
        "com.fmwhatsapp",
        "com.whatsapp.clone",
        "com.obwhatsapp",
        "com.whatsapp.plus",
    )
    private const val OFFICIAL = "com.whatsapp"
    private const val BUSINESS = "com.whatsapp.w4b"

    /** One WhatsApp to open: in this profile ([user] null) or a dual copy in another ([component] its launcher activity) */
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

    /**
     * WhatsApp with [number] (and [text] typed in, when given). [noApp]: the toast when there is no
     * WhatsApp (null: none — the caller says it itself). [onResult] runs once: true when a WhatsApp
     * opened, false when none could or the chooser was closed without a choice.
     * Returns false when nothing could happen at all (no number / no WhatsApp / it failed to open);
     * true when a WhatsApp opened or the chooser is up.
     */
    fun open(
        activity: Activity,
        number: String?,
        text: String? = null,
        noApp: Int? = R.string.ittc_l3_no_whatsapp,
        onResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (number.isNullOrBlank()) {
            activity.toast(R.string.ittc_lp_no_number)
            onResult?.invoke(false)
            return false
        }
        val message = text?.takeIf { it.isNotEmpty() }
        val choices = try {
            choices(activity)
        } catch (_: Throwable) {
            emptyList()
        }
        when (choices.size) {
            0 -> {
                // (nothing found — e.g. the list could not be read: the old way, by the known packages)
                val opened = launchBlind(activity, number, message)
                if (!opened) noApp?.let { activity.toast(it) }
                onResult?.invoke(opened)
                return opened
            }

            1 -> {
                val opened = launch(activity, choices[0], number, message)
                if (!opened) noApp?.let { activity.toast(it) }
                onResult?.invoke(opened)
                return opened
            }
        }
        if (activity.isFinishing || activity.isDestroyed) {
            onResult?.invoke(false)
            return false
        }
        showChooser(activity, number, message, choices, noApp, onResult)
        return true
    }

    // ------------------------------------------------------------------ the links
    private fun waMe(digits: String, text: String?): Uri =
        Uri.parse("https://wa.me/$digits" + (text?.let { "?text=" + Uri.encode(it) } ?: ""))

    private fun scheme(digits: String, text: String?): Uri =
        Uri.parse("whatsapp://send?phone=$digits" + (text?.let { "&text=" + Uri.encode(it) } ?: ""))

    /** Opens [c]; false when it could not */
    private fun launch(activity: Activity, c: Choice, number: String, text: String?): Boolean {
        val digits = IttcLeadApi.waNumber(number)
        val user = c.user
        val component = c.component
        if (user != null && component != null) return launchOtherProfile(activity, component, user, digits, text)
        for (uri in listOf(waMe(digits, text), scheme(digits, text))) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(c.pkg))
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** No list (it could not be read): WhatsApp, then Business by wa.me, then any app for whatsapp:// (as before 3.15) */
    private fun launchBlind(activity: Activity, number: String, text: String?): Boolean {
        val digits = IttcLeadApi.waNumber(number)
        for (pkg in listOf(OFFICIAL, BUSINESS)) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, waMe(digits, text)).setPackage(pkg))
                return true
            } catch (_: Throwable) {
            }
        }
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, scheme(digits, text)))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * A dual copy: no link reaches another profile, so the number (and the message) go to the
     * clipboard and that WhatsApp opens on its home screen.
     */
    private fun launchOtherProfile(activity: Activity, component: ComponentName, user: UserHandle, digits: String, text: String?): Boolean {
        val la = launcherApps(activity) ?: return false
        val clip = if (text == null) "+$digits" else "+$digits\n\n$text"
        val copied = IttcCopy.put(activity, clip)
        return try {
            la.startMainActivity(component, user, null, null)
            if (copied) {
                activity.toast(if (text == null) R.string.ittc_wa_dual_number_copied else R.string.ittc_wa_dual_message_copied)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ which WhatsApps are here
    /** Every WhatsApp: this profile's (known ones first), then the dual copies in other profiles */
    fun choices(context: Context): List<Choice> {
        val pm = context.packageManager
        val own = context.packageName
        val probe = "8801700000000"
        val schemeApps = handlers(pm, Intent(Intent.ACTION_VIEW, scheme(probe, null)))
        val waMeApps = handlers(pm, Intent(Intent.ACTION_VIEW, waMe(probe, null)).addCategory(Intent.CATEGORY_BROWSABLE))
        val browsers = handlers(pm, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE))

        // known ones (installed and on, even when they take neither link the usual way), then any other
        // app for whatsapp:// that is not a browser
        val pkgs = LinkedHashSet<String>()
        KNOWN.forEach { if (it in schemeApps || it in waMeApps || isEnabled(pm, it)) pkgs += it }
        schemeApps.forEach { if (it !in browsers && it != own) pkgs += it }

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
        return out
    }

    /** The packages with an activity for [intent] (browsers included: every match, not only the default) */
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

    /** WhatsApps installed in the other profiles (dual apps, a work profile): "WhatsApp (Dual)", "Business (Dual)" */
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
            pkgs.forEach { pkg ->
                try {
                    val act = la.getActivityList(pkg, user).firstOrNull() ?: return@forEach
                    out += Choice(
                        pkg = pkg,
                        label = act.label ?: pkg,
                        icon = safeIcon { act.getBadgedIcon(0) },
                        tag = context.getString(R.string.ittc_wa_tag_dual, tagOf(context, pkg)),
                        user = user,
                        component = act.componentName,
                    )
                } catch (_: Throwable) {
                }
            }
        }
        return out
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
        text: String?,
        choices: List<Choice>,
        noApp: Int?,
        onResult: ((Boolean) -> Unit)?,
    ) {
        val p = IttcTheme.of(activity)
        val sheet = IttcCUi.sheet(activity)
        val body = sheet.body
        val c = activity
        // (onResult once: a choice, the copy row, or closed without one)
        var answered = false
        fun answer(opened: Boolean) {
            if (answered) return
            answered = true
            onResult?.invoke(opened)
        }
        sheet.dialog.setOnDismissListener { answer(false) }

        body.addView(IttcCUi.text(c, c.getString(R.string.ittc_wa_choose), 18f, p.text, bold = true))
        body.addView(IttcCUi.text(c, number, 13f, p.muted).apply { textDirection = View.TEXT_DIRECTION_LTR }, IttcCUi.topMargin(c, 2))
        body.addView(View(c), LinearLayout.LayoutParams(1, IttcCUi.dp(c, 8)))

        choices.forEach { choice ->
            body.addView(row(c, p, choice.icon, null, choice.label.toString(), choice.tag, choice.official) {
                answered = true
                dismiss(sheet)
                val opened = launch(activity, choice, number, text)
                if (!opened) noApp?.let { activity.toast(it) }
                onResult?.invoke(opened)
            })
        }

        body.addView(IttcCUi.divider(c))
        body.addView(row(c, p, null, R.drawable.ic_copy_vector, c.getString(R.string.ittc_copy_number_row), null, false) {
            dismiss(sheet)
            IttcCopy.number(activity, number)
        })
        IttcCUi.showSafely(activity, sheet.dialog)
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
        run: () -> Unit,
    ): View {
        val line = IttcCUi.row(c).apply {
            minimumHeight = IttcCUi.dp(c, 52)
            setPadding(IttcCUi.dp(c, 4), 0, IttcCUi.dp(c, 4), 0)
            background = IttcTheme.pressable(c, IttcTheme.rounded(c, p.surface, 12f), p.text)
            isClickable = true
            isFocusable = true
            contentDescription = if (tag == null) label else "$label, $tag"
            setOnClickListener { run() }
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
