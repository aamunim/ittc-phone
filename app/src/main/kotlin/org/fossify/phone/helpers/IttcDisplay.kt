package org.fossify.phone.helpers

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fossify.commons.helpers.AppTextScale
import org.fossify.phone.R
import kotlin.math.roundToInt

/**
 * 🎓 ITTC Phone 3.12: display sizes for the whole app (Settings → Display & text size, and the side
 * panel's text size).
 *
 * - Text size (80–150 %, on top of the phone's font size): AppTextScale (commons) gives every
 *   activity a font scale of the phone's × this in attachBaseContext, so every sp follows it.
 * - Rows & buttons (90 / 100 / 115 %): ITTC rows, cards and chips are scaled once when they are made
 *   ([scaleRow]: paddings, minimum heights and fixed sizes; a touch target never under 44 dp).
 * - Dial pad keys (90–130 %): the main screen's dial pad key size.
 * - Call screen (90–140 %): the name and number on the call screen.
 *
 * All read once and kept in memory; a change raises AppTextScale's version, and every open screen
 * made with an older one is made again when it shows (BaseSimpleActivity.onResume).
 */
object IttcDisplay {
    private const val KEY_ROWS = "rows_pct"
    private const val KEY_DIALPAD = "dialpad_pct"
    private const val KEY_CALL = "call_pct"

    const val ROWS_COMPACT = 90
    const val ROWS_COMFORTABLE = 100
    const val ROWS_LARGE = 115
    const val DIALPAD_MIN = 90
    const val DIALPAD_MAX = 130
    const val CALL_MIN = 90
    const val CALL_MAX = 140

    /** The text size presets: Small, Normal, Large, Extra */
    val TEXT_PRESETS = intArrayOf(90, 100, 115, 130)

    @Volatile
    private var loaded = false
    private var rows = 100
    private var dialpad = 100
    private var call = 100

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = AppTextScale.prefs(ctx)
            rows = p.getInt(KEY_ROWS, 100).coerceIn(ROWS_COMPACT, ROWS_LARGE)
            dialpad = p.getInt(KEY_DIALPAD, 100).coerceIn(DIALPAD_MIN, DIALPAD_MAX)
            call = p.getInt(KEY_CALL, 100).coerceIn(CALL_MIN, CALL_MAX)
            loaded = true
        }
    }

    // ------------------------------------------------------------------------- the values
    fun textPercent(ctx: Context): Int = AppTextScale.percent(ctx)
    fun rowsPercent(ctx: Context): Int {
        load(ctx)
        return rows
    }

    fun dialpadPercent(ctx: Context): Int {
        load(ctx)
        return dialpad
    }

    fun callPercent(ctx: Context): Int {
        load(ctx)
        return call
    }

    fun rowScale(ctx: Context): Float = rowsPercent(ctx) / 100f
    fun dialpadScale(ctx: Context): Float = dialpadPercent(ctx) / 100f
    fun callScale(ctx: Context): Float = callPercent(ctx) / 100f

    fun setTextPercent(ctx: Context, pct: Int) = AppTextScale.setPercent(ctx, pct)

    fun setRowsPercent(ctx: Context, pct: Int) = save(ctx, KEY_ROWS, pct.coerceIn(ROWS_COMPACT, ROWS_LARGE), rowsPercent(ctx)) { rows = it }
    fun setDialpadPercent(ctx: Context, pct: Int) = save(ctx, KEY_DIALPAD, pct.coerceIn(DIALPAD_MIN, DIALPAD_MAX), dialpadPercent(ctx)) { dialpad = it }
    fun setCallPercent(ctx: Context, pct: Int) = save(ctx, KEY_CALL, pct.coerceIn(CALL_MIN, CALL_MAX), callPercent(ctx)) { call = it }

    private inline fun save(ctx: Context, key: String, value: Int, old: Int, set: (Int) -> Unit) {
        if (value == old) return
        set(value)
        AppTextScale.prefs(ctx).edit().putInt(key, value).apply()
        AppTextScale.bumpVersion(ctx)
    }

    /** The version of the sizes (a screen made with another one is made again) */
    fun version(ctx: Context): Int = AppTextScale.version(ctx)

    /** The text size the screen of [ctx] was made with (its font scale over the phone's) */
    fun appliedTextPercent(ctx: Context): Int {
        val own = ctx.resources.configuration.fontScale
        val phone = ctx.applicationContext.resources.configuration.fontScale
        if (phone <= 0f) return 100
        return (own / phone * 100f).roundToInt()
    }

    // ------------------------------------------------------------------------- rows & buttons
    /**
     * Scales a row / card / chip made from a layout by the "Rows & buttons" size: paddings, minimum
     * heights and fixed sizes of [root] and every view in it. A size of 44 dp or more never goes
     * under 44 dp (touch targets). Call once, when the view is made (not on each bind). Nothing is
     * done at 100 %.
     */
    fun scaleRow(root: View, scale: Float = rowScale(root.context)) {
        if (scale == 1f) return
        val touch = root.resources.getDimensionPixelSize(R.dimen.ittc_touch)
        scaleTree(root, scale, touch)
    }

    private fun scaleTree(v: View, s: Float, touch: Int) {
        fun sz(x: Int): Int {
            if (x <= 0) return x
            val out = (x * s).roundToInt()
            return if (x >= touch && out < touch) touch else out
        }

        // (paddings never lift a target under 44 dp: they are inside the view)
        v.setPaddingRelative(
            (v.paddingStart * s).roundToInt(), (v.paddingTop * s).roundToInt(),
            (v.paddingEnd * s).roundToInt(), (v.paddingBottom * s).roundToInt()
        )
        if (v.minimumHeight > 0) v.minimumHeight = sz(v.minimumHeight)
        if (v is TextView && v.minHeight > 0) v.minHeight = sz(v.minHeight)
        // (ConstraintLayout keeps its own minimum height)
        if (v is ConstraintLayout && v.minHeight > 0) v.minHeight = sz(v.minHeight)
        v.layoutParams?.let { lp ->
            var changed = false
            if (lp.width > 0) {
                lp.width = sz(lp.width); changed = true
            }
            if (lp.height > 0) {
                lp.height = sz(lp.height); changed = true
            }
            if (changed) v.layoutParams = lp
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) scaleTree(v.getChildAt(i), s, touch)
        }
    }

    // ------------------------------------------------------------------------- live text size
    /**
     * While the side panel's slider moves: the text of the screen behind at [pct] (from the size it
     * was made with), with no new screen per step. Each text's own size is kept in a tag the first
     * time, so moving back and forth never drifts. [skip]: a part left as it is (the panel). The
     * screen is made again with the real size when the panel closes.
     */
    fun previewText(activity: Activity, root: View, pct: Int, skip: View? = null) {
        val applied = appliedTextPercent(activity).takeIf { it > 0 } ?: 100
        val ratio = pct.toFloat() / applied
        liveTree(root, ratio, skip)
    }

    private fun liveTree(v: View, ratio: Float, skip: View?) {
        if (v === skip) return
        if (v is TextView) {
            val base = (v.getTag(R.id.ittc_live_text_base) as? Float) ?: v.textSize.also { v.setTag(R.id.ittc_live_text_base, it) }
            val want = base * ratio
            if (v.textSize != want) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, want)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) liveTree(v.getChildAt(i), ratio, skip)
        }
    }
}
