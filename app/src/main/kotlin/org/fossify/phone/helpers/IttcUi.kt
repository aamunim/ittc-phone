package org.fossify.phone.helpers

import android.util.TypedValue
import android.view.View
import androidx.appcompat.content.res.AppCompatResources

/**
 * 🎨 ITTC: small touches shared by the screens — the system's pressed ripple on top of a custom
 * background, and a short fade when something appears. No new look: the same views, just smoother.
 */
object IttcUi {
    private const val FADE_MS = 120L

    /**
     * The theme's pressed ripple drawn over [view] (its own background stays as it is), clipped to
     * the background's shape so round chips keep round ripples. Nothing is done when the view already
     * has a foreground. Call once per view (in a list: when the row is made, not on every bind).
     */
    fun ripple(view: View) {
        if (view.foreground != null) return
        val value = TypedValue()
        if (!view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) || value.resourceId == 0) return
        view.foreground = AppCompatResources.getDrawable(view.context, value.resourceId) ?: return
        view.clipToOutline = true
    }

    /**
     * Shows [view] with a short fade to [to] (its resting opacity: 1, or the layout's own alpha), only
     * when it was not shown yet (a view already on screen is left as it is, so a refresh never
     * blinks). The fade always ends at [to].
     */
    fun fadeIn(view: View, to: Float = 1f) {
        if (view.visibility == View.VISIBLE) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(to).setDuration(FADE_MS).start()
    }

    /** [fadeIn] when [show], else gone (a fade still running is stopped at [to], so it never stays faded) */
    fun fadeIf(view: View, show: Boolean, to: Float = 1f) {
        if (show) {
            fadeIn(view, to)
        } else {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != to) {
                view.animate().cancel()
                view.alpha = to
            }
        }
    }
}
