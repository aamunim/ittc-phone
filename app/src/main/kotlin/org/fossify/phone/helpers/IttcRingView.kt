package org.fossify.phone.helpers

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 🎯 ITTC 3.5: the Leads tab's progress ring — calls made against the day's target ("32 / of 50").
 * No target: a plain ring with the number. Everything drawn with paints made once (no allocation
 * in onDraw); a change animates in 200 ms.
 */
class IttcRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = 9f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 20f * density
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val oval = RectF()

    private var value = ""
    private var sub = ""
    /** 0..1 drawn now (animated towards [target]) */
    private var shown = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null

    fun setColors(track: Int, ring: Int, text: Int, subText: Int) {
        trackPaint.color = track
        ringPaint.color = ring
        valuePaint.color = text
        subPaint.color = subText
        invalidate()
    }

    /** [done] of [max] (max ≤ 0: no target — only the number) */
    fun set(done: Int, max: Int, valueText: String, subText: String) {
        value = valueText
        sub = subText
        val fraction = if (max > 0) (done.toFloat() / max).coerceIn(0f, 1f) else 0f
        if (fraction != target) {
            target = fraction
            animator?.cancel()
            if (!isAttachedToWindow || !isShown) {
                shown = fraction
            } else {
                animator = ValueAnimator.ofFloat(shown, fraction).apply {
                    duration = 200
                    addUpdateListener {
                        shown = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        shown = target
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val r = size / 2f - stroke / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, trackPaint)
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown, false, ringPaint)

        val hasSub = sub.isNotEmpty()
        val valueY = if (hasSub) cy - 1f * density else cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(value, cx, valueY, valuePaint)
        if (hasSub) canvas.drawText(sub, cx, cy + 15f * density, subPaint)
    }
}
