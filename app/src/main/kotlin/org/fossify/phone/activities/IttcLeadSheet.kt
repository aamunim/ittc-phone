package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.direct.IttcLeadApi
import org.fossify.phone.extensions.startCallIntent
import org.fossify.phone.helpers.IttcBridge
import org.fossify.phone.helpers.IttcTheme

/**
 * 🎓 ITTC 3.8: a lead's actions in one bottom sheet (a long press on a lead in the Leads tab):
 * who it is (name, stage, number), then call / the other number / WhatsApp / SMS, the lead page's
 * own actions (direct mode: they open [IttcLeadActivity], which runs the chosen one once the lead is
 * up) and "open the lead". Also WhatsApp for a number, shared by the Leads tab's rows.
 */
object IttcLeadSheet {
    /** WhatsApp, then WhatsApp Business */
    private val WA_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** WhatsApp's light / dark greens: (circle, icon) */
    fun whatsAppColors(p: IttcTheme.Palette): Pair<Int, Int> =
        if (p.dark) 0xFF123A2A.toInt() to 0xFF4ADE80.toInt() else 0xFFE5F7EC.toInt() to 0xFF128C4B.toInt()

    /** The number WhatsApp is for (its own, else the phone, else the other one) */
    fun whatsAppNumber(l: IttcBridge.PhoneLead): String? =
        (l.whatsapp ?: l.phone ?: l.altPhone)?.takeIf { it.isNotBlank() }

    /** A chat with [number] in WhatsApp (BD numbers made +880…); a toast when WhatsApp is not here */
    fun openWhatsApp(activity: Activity, number: String?): Boolean {
        if (number.isNullOrBlank()) {
            activity.toast(R.string.ittc_lp_no_number)
            return false
        }
        val digits = IttcLeadApi.waNumber(number)
        val link = Uri.parse("https://wa.me/$digits")
        for (pkg in WA_PACKAGES) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(pkg))
                return true
            } catch (_: Throwable) {
            }
        }
        // (a WhatsApp that does not take wa.me links: its own scheme)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$digits")))
            return true
        } catch (_: Throwable) {
        }
        activity.toast(R.string.ittc_l3_no_whatsapp)
        return false
    }

    private class Action(val icon: Int, val label: String, val bg: Int, val fg: Int, val run: () -> Unit)

    /**
     * The sheet for [l]. [direct]: signed in to ITTC here (the lead page's actions are offered);
     * [canHandOver]: a super admin (hand over is offered).
     */
    fun show(activity: SimpleActivity, l: IttcBridge.PhoneLead, direct: Boolean, canHandOver: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = IttcTheme.of(activity)
        val sheet = IttcCUi.sheet(activity)
        val body = sheet.body
        val c = activity

        // 🟦 who: name + stage pill, the number under it
        val title = l.name.ifEmpty { l.callNumber.orEmpty() }
        val head = IttcCUi.row(c)
        head.addView(
            IttcCUi.text(c, title, 18f, p.text, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        l.stage?.takeIf { it.isNotBlank() }?.let { stage ->
            val (sBg, sFg) = IttcTheme.stageColors(c, stage)
            head.addView(
                IttcCUi.pill(c, stage, sBg, sFg).apply { maxLines = 1 },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = IttcCUi.dp(c, 8)
                },
            )
        }
        body.addView(head)
        val sub = listOfNotNull(l.callNumber, l.course).joinToString(" · ")
        if (sub.isNotEmpty()) body.addView(IttcCUi.text(c, sub, 13f, p.muted), IttcCUi.topMargin(c, 2))

        // 📞 💬 ✉️ reaching them
        val quick = ArrayList<Action>()
        val callNumber = l.callNumber?.takeIf { it.isNotBlank() }
        val (waBg, waFg) = whatsAppColors(p)
        if (callNumber != null) {
            quick += Action(R.drawable.ic_phone_vector, "${c.getString(R.string.ittc_lp_call)}  ·  $callNumber", p.callSoft, p.call) {
                c.startCallIntent(callNumber)
            }
        }
        l.altPhone?.takeIf { it.isNotBlank() && it != callNumber }?.let { alt ->
            quick += Action(R.drawable.ic_phone_vector, "${c.getString(R.string.ittc_l3_call_alt)}  ·  $alt", p.callSoft, p.call) {
                c.startCallIntent(alt)
            }
        }
        whatsAppNumber(l)?.let { wa ->
            quick += Action(R.drawable.ittc_ic_whatsapp, c.getString(R.string.ittc_lp_whatsapp), waBg, waFg) { openWhatsApp(c, wa) }
        }
        if (callNumber != null) {
            quick += Action(R.drawable.ic_sms_vector, c.getString(R.string.ittc_lp_sms), p.primarySoft, p.primary) {
                try {
                    c.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(callNumber))))
                } catch (_: Throwable) {
                    c.toast(R.string.ittc_lp_no_app)
                }
            }
        }
        if (quick.isNotEmpty()) {
            body.addView(View(c), LinearLayout.LayoutParams(1, IttcCUi.dp(c, 8)))
            quick.forEach { body.addView(row(c, p, it, sheet)) }
        }

        // 🧭 the lead page's own actions (direct mode): the page opens and runs it
        if (direct) {
            body.addView(IttcCUi.divider(c))
            body.addView(
                IttcCUi.section(c, c.getString(R.string.ittc_l3_lead_actions)),
                IttcCUi.topMargin(c, 4).apply { bottomMargin = IttcCUi.dp(c, 4) },
            )
            IttcLeadActivity.pageActions(canHandOver).forEach { (key, label) ->
                body.addView(row(c, p, Action(iconFor(key), c.getString(label), p.surfaceAlt, p.text2) {
                    IttcLeadActivity.open(c, l.leadId, action = key)
                }, sheet))
            }
        } else {
            body.addView(IttcCUi.divider(c))
        }
        body.addView(
            row(
                c, p,
                Action(
                    R.drawable.ic_person_vector,
                    c.getString(if (direct) R.string.ittc_l3_open_lead else R.string.ittc_l3_open_tracker),
                    p.primarySoft, p.primary,
                ) {
                    if (!IttcBridge.openLead(c, l.leadId)) c.toast(R.string.ittc_lp_no_app)
                },
                sheet,
            ),
        )
        IttcCUi.showSafely(activity, sheet.dialog)
    }

    private fun iconFor(key: String): Int = when (key) {
        IttcLeadActivity.ACTION_CHANGE_STAGE -> R.drawable.ic_arrow_right_vector
        IttcLeadActivity.ACTION_ADD_NOTE -> R.drawable.ic_edit_vector
        "visit" -> R.drawable.ic_place_vector
        "fee", "payment" -> R.drawable.ic_dollar_vector
        "papers" -> R.drawable.ic_attach_file_vector
        "admit" -> R.drawable.ic_check_vector
        "templates" -> R.drawable.ic_send_vector
        "next_call" -> R.drawable.ic_clock_vector
        "rating" -> R.drawable.ic_star_vector
        "edit" -> R.drawable.ic_edit_vector
        "hand_over" -> R.drawable.ic_people_vector
        "web" -> R.drawable.ic_link_vector
        else -> R.drawable.ic_chevron_right_vector
    }

    /** One action: a round tinted icon and its label (52 dp, a ripple inside its corners) */
    private fun row(c: Activity, p: IttcTheme.Palette, a: Action, sheet: IttcCUi.Sheet): View {
        val line = IttcCUi.row(c).apply {
            minimumHeight = IttcCUi.dp(c, 52)
            setPadding(IttcCUi.dp(c, 4), 0, IttcCUi.dp(c, 4), 0)
            background = IttcTheme.pressable(c, IttcTheme.rounded(c, p.surface, 12f), p.text)
            isClickable = true
            isFocusable = true
            contentDescription = a.label
            setOnClickListener {
                try {
                    sheet.dialog.dismiss()
                } catch (_: Throwable) {
                }
                a.run()
            }
        }
        val size = IttcCUi.dp(c, 36)
        line.addView(
            ImageView(c).apply {
                setImageResource(a.icon)
                setColorFilter(a.fg)
                val pad = IttcCUi.dp(c, 8)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(a.bg)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(size, size),
        )
        line.addView(
            IttcCUi.text(c, a.label, 15f, p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = IttcCUi.dp(c, 14) },
        )
        return line
    }
}
