package org.fossify.phone.helpers

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/**
 * 🎨 ITTC Phone 3.3 design system: one place for the colours, shapes and type of every ITTC screen.
 * It follows the app's own light / dark choice (Fossify's theme, not only the system's): the
 * palette is picked from how dark the app background is. res/values/ittc_design.xml has the same
 * light colours for layouts.
 */
object IttcTheme {
    class Palette(
        val dark: Boolean,
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val outline: Int,
        val divider: Int,
        val text: Int,
        val text2: Int,
        val muted: Int,
        val primary: Int,
        val primarySoft: Int,
        val onPrimary: Int,
        val onPrimaryMuted: Int,
        val call: Int,
        val callSoft: Int,
        val danger: Int,
        val dangerStrong: Int,
        val dangerSoft: Int,
        val ring: Int,
        val inCallBg: Int,
    )

    private val LIGHT = Palette(
        dark = false,
        bg = 0xFFF4F7FB.toInt(), surface = Color.WHITE, surfaceAlt = 0xFFEEF2F7.toInt(),
        outline = 0xFFD5DEEA.toInt(), divider = 0xFFEEF2F7.toInt(),
        text = 0xFF12213A.toInt(), text2 = 0xFF3E4D63.toInt(), muted = 0xFF5B6B82.toInt(),
        primary = 0xFF1E5AA8.toInt(), primarySoft = 0xFFE3EDFB.toInt(), onPrimary = Color.WHITE, onPrimaryMuted = 0xFFDCE8F7.toInt(),
        call = 0xFF0E8A7A.toInt(), callSoft = 0xFFE6F4F1.toInt(),
        danger = 0xFFB42318.toInt(), dangerStrong = 0xFFD92D20.toInt(), dangerSoft = 0xFFFFF4F4.toInt(),
        ring = 0xFF7FE0C8.toInt(), inCallBg = 0xFF10203A.toInt(),
    )

    private val DARK = Palette(
        dark = true,
        bg = 0xFF0E1622.toInt(), surface = 0xFF172233.toInt(), surfaceAlt = 0xFF1F2C40.toInt(),
        outline = 0xFF2E3D55.toInt(), divider = 0xFF223047.toInt(),
        text = 0xFFE8EEF7.toInt(), text2 = 0xFFC3CEDF.toInt(), muted = 0xFF9AA9BF.toInt(),
        primary = 0xFF7FB2F0.toInt(), primarySoft = 0xFF1C2E4A.toInt(), onPrimary = 0xFF0B1628.toInt(), onPrimaryMuted = 0xFF1F3350.toInt(),
        call = 0xFF3CC2AE.toInt(), callSoft = 0xFF123A35.toInt(),
        danger = 0xFFFF8A80.toInt(), dangerStrong = 0xFFE5484D.toInt(), dangerSoft = 0xFF3A1C1C.toInt(),
        ring = 0xFF7FE0C8.toInt(), inCallBg = 0xFF0A1424.toInt(),
    )

    /** The palette for the app's current theme (cheap: one colour read) */
    fun of(context: Context): Palette {
        val bg = try {
            context.getProperBackgroundColor()
        } catch (_: Throwable) {
            Color.WHITE
        }
        return if (luminance(bg) < 0.5) DARK else LIGHT
    }

    private fun luminance(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** The app's text colour and accent as Fossify sets them (for screens that keep the user's colours) */
    fun appText(context: Context): Int = context.getProperTextColor()
    fun appPrimary(context: Context): Int = context.getProperPrimaryColor()

    // ----------------------------------------------------------------------------- stages
    /** A stage pill's (background, text) colours: New blue, working amber, visit purple, admitted green, closed grey */
    fun stageColors(context: Context, stage: String?): Pair<Int, Int> {
        val d = of(context).dark
        val s = stage.orEmpty().lowercase()
        return when {
            s.contains("admitted") && !s.contains("cancel") -> if (d) 0xFF173525.toInt() to 0xFF86D9A4.toInt() else 0xFFDDF3E4.toInt() to 0xFF15803D.toInt()
            s.contains("not now") || s.contains("lost") || s.contains("cancel") -> if (d) 0xFF1F2C40.toInt() to 0xFF9AA9BF.toInt() else 0xFFEEF2F7.toInt() to 0xFF5B6B82.toInt()
            s.contains("visit") || s.contains("regist") -> if (d) 0xFF2C2148.toInt() to 0xFFC4B0F5.toInt() else 0xFFEDE7FB.toInt() to 0xFF5B2DB3.toInt()
            s.isEmpty() || s.contains("new") || s.contains("fresh") -> if (d) 0xFF1C2E4A.toInt() to 0xFF9CC3F5.toInt() else 0xFFE3EDFB.toInt() to 0xFF1E5AA8.toInt()
            else -> if (d) 0xFF3A2A12.toInt() to 0xFFF5C77E.toInt() else 0xFFFFF1DB.toInt() to 0xFF8A4B00.toInt()
        }
    }

    // ----------------------------------------------------------------------------- shapes
    fun dp(context: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    /** A rounded rectangle (radius in dp; stroke 0 = none) */
    fun rounded(context: Context, fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(context, strokeDp), stroke)
        }

    /** A card: surface, 16 dp corners */
    fun card(view: View, palette: Palette = of(view.context)) {
        view.background = rounded(view.context, palette.surface, 16f)
    }

    /** A pill (chip / tag) with the given colours; outline: a 1 dp border instead of a fill */
    fun pill(view: TextView, bg: Int, fg: Int, outline: Int = 0) {
        view.background = rounded(view.context, bg, 999f, outline)
        view.setTextColor(fg)
    }

    /** A filled round button */
    fun button(view: TextView, bg: Int, fg: Int) {
        view.background = rounded(view.context, bg, 999f)
        view.backgroundTintList = null
        view.setTextColor(fg)
    }

    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)

    /** Two letters for an avatar ("Rahim Hasan" → "RH"; a number → "#") */
    fun initials(name: String?): String {
        val words = name.orEmpty().trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
        return when {
            words.isEmpty() -> "#"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /** A soft avatar colour picked from the name (the same name always gets the same colour) */
    fun avatarColors(context: Context, name: String?): Pair<Int, Int> {
        val d = of(context).dark
        val light = listOf(
            0xFFFDE7D2.toInt() to 0xFF9A4A0B.toInt(), 0xFFE3EDFB.toInt() to 0xFF1E5AA8.toInt(),
            0xFFDDF3E4.toInt() to 0xFF15803D.toInt(), 0xFFEDE7FB.toInt() to 0xFF5B2DB3.toInt(),
            0xFFFCE4EC.toInt() to 0xFFA3214F.toInt(), 0xFFE6F4F1.toInt() to 0xFF0B6E62.toInt(),
        )
        val dark = listOf(
            0xFF3A2A12.toInt() to 0xFFF5C77E.toInt(), 0xFF1C2E4A.toInt() to 0xFF9CC3F5.toInt(),
            0xFF173525.toInt() to 0xFF86D9A4.toInt(), 0xFF2C2148.toInt() to 0xFFC4B0F5.toInt(),
            0xFF3A1A27.toInt() to 0xFFF2A6C2.toInt(), 0xFF123A35.toInt() to 0xFF7FE0C8.toInt(),
        )
        val list = if (d) dark else light
        return list[Math.floorMod(name.orEmpty().hashCode(), list.size)]
    }
}
