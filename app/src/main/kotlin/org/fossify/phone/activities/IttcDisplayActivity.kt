package org.fossify.phone.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.AppTextScale
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityDisplayBinding
import org.fossify.phone.helpers.IttcDisplay
import org.fossify.phone.helpers.IttcTheme
import org.fossify.phone.helpers.IttcUi
import kotlin.math.roundToInt

/**
 * 🎓 ITTC Phone 3.12: Settings → Display & text size. The preview follows each slider while it
 * moves; the size is saved — and the whole app follows — when the slider is let go (this screen is
 * made again at once, the others when they show again).
 */
class IttcDisplayActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityDisplayBinding::inflate)
    private lateinit var p: IttcTheme.Palette

    // the text size this screen was made with, and the values shown (saved when a slider is let go)
    private var madeText = 100
    private var text = 100
    private var rows = 100
    private var dialpad = 100
    private var call = 100
    private var moreOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = IttcTheme.of(this)
        setContentView(binding.root)
        madeText = IttcDisplay.textPercent(this)
        text = madeText
        rows = IttcDisplay.rowsPercent(this)
        dialpad = IttcDisplay.dialpadPercent(this)
        call = IttcDisplay.callPercent(this)
        moreOpen = savedInstanceState?.getBoolean(STATE_MORE) ?: false

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcDsScroll))
            ittcDsCoordinator.setBackgroundColor(p.bg)
            IttcTheme.card(ittcDsPreviewCard, p)
            IttcTheme.card(ittcDsTextCard, p)
            IttcTheme.card(ittcDsMoreCard, p)
            ittcDsMoreCard.clipToOutline = true
            listOf(ittcDsPreviewLabel, ittcDsTextLabel, ittcDsNote).forEach { it.setTextColor(p.muted) }
            listOf(
                ittcDsPreviewName, ittcDsTextValue, ittcDsMoreTitle, ittcDsRowsLabel, ittcDsRowsValue, ittcDsDialpadLabel,
                ittcDsDialpadValue, ittcDsCallLabel, ittcDsCallValue, ittcDsPreviewCallName, ittcDsASmall, ittcDsABig,
            ).forEach { it.setTextColor(p.text) }
            listOf(ittcDsPreviewLine, ittcDsPreviewCallNumber).forEach { it.setTextColor(p.text2) }
            ittcDsPreviewDue.setTextColor(p.primary)
            ittcDsReset.setTextColor(p.primary)
            ittcDsMoreChevron.setColorFilter(p.muted)

            // the preview: a lead row, Save and Next call, a dial pad key, the call screen's name
            val (avBg, avFg) = IttcTheme.avatarColors(this@IttcDisplayActivity, getString(R.string.ittc_ds_sample_name))
            ittcDsPreviewAvatar.text = IttcTheme.initials(getString(R.string.ittc_ds_sample_name))
            ittcDsPreviewAvatar.background = IttcTheme.rounded(this@IttcDisplayActivity, avBg, 999f)
            ittcDsPreviewAvatar.setTextColor(avFg)
            ittcDsPreviewCall.background = IttcTheme.rounded(this@IttcDisplayActivity, p.callSoft, 999f)
            ittcDsPreviewCall.setColorFilter(p.call)
            IttcTheme.button(ittcDsPreviewSave, p.primary, p.onPrimary)
            ittcDsPreviewNext.background = IttcTheme.rounded(this@IttcDisplayActivity, p.surface, 999f, p.outline)
            ittcDsPreviewNext.setTextColor(p.primary)
            ittcDsPreviewKey.background = IttcTheme.rounded(this@IttcDisplayActivity, p.surfaceAlt, 999f)
            ittcDsPreviewCallScreen.background = IttcTheme.rounded(this@IttcDisplayActivity, p.inCallBg, 12f)
            ittcDsPreviewCallName.setTextColor(0xFFFFFFFF.toInt())
            ittcDsPreviewCallNumber.setTextColor(0xFFDCE8F7.toInt())

            listOf(ittcDsTextSeek, ittcDsRowsSeek, ittcDsDialpadSeek, ittcDsCallSeek).forEach { seek ->
                seek.progressTintList = IttcTheme.tint(p.primary)
                seek.thumbTintList = IttcTheme.tint(p.primary)
                seek.progressBackgroundTintList = IttcTheme.tint(p.outline)
            }

            ittcDsTextSeek.max = (AppTextScale.MAX - AppTextScale.MIN) / AppTextScale.STEP
            ittcDsTextSeek.progress = (text - AppTextScale.MIN) / AppTextScale.STEP
            ittcDsRowsSeek.progress = ROWS.indexOf(rows).coerceAtLeast(0)
            ittcDsDialpadSeek.progress = (dialpad - IttcDisplay.DIALPAD_MIN) / 5
            ittcDsCallSeek.progress = (call - IttcDisplay.CALL_MIN) / 5

            ittcDsTextSeek.setOnSeekBarChangeListener(slider { text = AppTextScale.MIN + it * AppTextScale.STEP })
            ittcDsRowsSeek.setOnSeekBarChangeListener(slider { rows = ROWS[it.coerceIn(0, ROWS.size - 1)] })
            ittcDsDialpadSeek.setOnSeekBarChangeListener(slider { dialpad = IttcDisplay.DIALPAD_MIN + it * 5 })
            ittcDsCallSeek.setOnSeekBarChangeListener(slider { call = IttcDisplay.CALL_MIN + it * 5 })
            // (the small and the big A: one step down / up)
            ittcDsASmall.setOnClickListener { stepText(-1) }
            ittcDsABig.setOnClickListener { stepText(1) }

            presets().forEachIndexed { i, chip ->
                IttcUi.ripple(chip)
                chip.setOnClickListener {
                    text = IttcDisplay.TEXT_PRESETS[i]
                    ittcDsTextSeek.progress = (text - AppTextScale.MIN) / AppTextScale.STEP
                    save()
                }
            }

            ittcDsMoreHeader.setOnClickListener {
                moreOpen = !moreOpen
                showMore()
            }
            ittcDsReset.setOnClickListener {
                text = 100
                rows = 100
                dialpad = 100
                call = 100
                save()
            }
        }
        showMore()
        showValues()
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForDisplaySize) return
        setupTopAppBar(binding.ittcDsAppbar, NavigationIcon.Arrow, topBarColor = p.bg)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MORE, moreOpen)
    }

    private var tracking = false

    /** A slider: the preview while it moves; saved when it is let go (or changed without a touch) */
    private fun slider(set: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            set(progress)
            showValues()
            if (!tracking) save()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            tracking = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            tracking = false
            save()
        }
    }

    /** Saves the sizes; the app follows (this screen at once) */
    private fun save() {
        showValues()
        val before = IttcDisplay.version(this)
        IttcDisplay.setTextPercent(this, text)
        IttcDisplay.setRowsPercent(this, rows)
        IttcDisplay.setDialpadPercent(this, dialpad)
        IttcDisplay.setCallPercent(this, call)
        if (IttcDisplay.version(this) != before) recreate()
    }

    private fun stepText(by: Int) {
        val seek = binding.ittcDsTextSeek
        seek.progress = (seek.progress + by).coerceIn(0, seek.max)
        text = AppTextScale.MIN + seek.progress * AppTextScale.STEP
        save()
    }

    private fun presets(): List<TextView> = binding.run {
        listOf(ittcDsPresetSmall, ittcDsPresetNormal, ittcDsPresetLarge, ittcDsPresetExtra)
    }

    private fun showMore() {
        binding.ittcDsMoreBody.visibility = if (moreOpen) View.VISIBLE else View.GONE
        binding.ittcDsPreviewMore.visibility = if (moreOpen) View.VISIBLE else View.GONE
        binding.ittcDsMoreChevron.rotation = if (moreOpen) 180f else 0f
    }

    /** The values beside the sliders, the presets and the preview for the values shown */
    private fun showValues() {
        binding.apply {
            ittcDsTextValue.text = getString(R.string.ittc_ds_percent, text)
            ittcDsRowsValue.text = getString(rowsLabel(rows))
            ittcDsDialpadValue.text = getString(R.string.ittc_ds_percent, dialpad)
            ittcDsCallValue.text = getString(R.string.ittc_ds_percent, call)
            presets().forEachIndexed { i, chip ->
                val on = IttcDisplay.TEXT_PRESETS[i] == text
                chip.background = IttcTheme.rounded(this@IttcDisplayActivity, if (on) p.primarySoft else p.surfaceAlt, 999f, if (on) p.primary else 0)
                chip.setTextColor(if (on) p.primary else p.text2)
            }
        }
        preview()
    }

    private fun preview() {
        val t = text.toFloat() / madeText // (this screen's sp already carry the text size it was made with)
        val r = rows / 100f
        val d = dialpad / 100f
        val c = call / 100f
        binding.apply {
            sp(ittcDsPreviewName, 15f * t)
            sp(ittcDsPreviewLine, 12f * t)
            sp(ittcDsPreviewDue, 12f * t)
            sp(ittcDsPreviewSave, 15f * t)
            sp(ittcDsPreviewNext, 15f * t)
            val pad = px(14f * r)
            ittcDsPreviewCard.setPadding(pad, pad, pad, pad)
            ittcDsPreviewRow.minimumHeight = px(72f * r)
            size(ittcDsPreviewAvatar, touch(44f * r))
            size(ittcDsPreviewCall, touch(44f * r))
            val callPad = px(12f * r)
            ittcDsPreviewCall.setPadding(callPad, callPad, callPad, callPad)
            ittcDsPreviewSave.minHeight = touch(48f * r)
            ittcDsPreviewNext.minHeight = touch(48f * r)

            // (as the main screen's dial pad: the digit follows the key, the text size only up to 125 %)
            val keyPx = px(60f * d)
            size(ittcDsPreviewKey, keyPx)
            val phone = applicationContext.resources.configuration.fontScale
            val fontScale = (phone * text / 100f).coerceIn(0.85f, 1.25f)
            ittcDsPreviewKey.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(26f) * d * fontScale)
            ittcDsPreviewKey.text = keyText(dpF(10f) * d)
            sp(ittcDsPreviewCallName, 34f * c * t)
            sp(ittcDsPreviewCallNumber, 20f * c * t)
        }
    }

    private fun keyText(lettersPx: Float): CharSequence {
        val out = SpannableStringBuilder(getString(R.string.ittc_ds_sample_key))
        out.setSpan(ForegroundColorSpan(p.text), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = out.length
        out.append("\nJKL")
        out.setSpan(AbsoluteSizeSpan(lettersPx.roundToInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(p.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun sp(v: TextView, value: Float) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
    }

    private fun dpF(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun px(v: Float): Int = dpF(v).roundToInt()

    /** A touch target: never under 44 dp */
    private fun touch(v: Float): Int = maxOf(px(v), resources.getDimensionPixelSize(R.dimen.ittc_touch))

    private fun size(v: View, px: Int) {
        val lp = v.layoutParams ?: return
        if (lp.width == px && lp.height == px) return
        lp.width = px
        lp.height = px
        v.layoutParams = lp
    }

    companion object {
        private const val STATE_MORE = "ittc_ds_more"
        private val ROWS = intArrayOf(IttcDisplay.ROWS_COMPACT, IttcDisplay.ROWS_COMFORTABLE, IttcDisplay.ROWS_LARGE)

        fun rowsLabel(pct: Int): Int = when {
            pct <= IttcDisplay.ROWS_COMPACT -> R.string.ittc_ds_rows_compact
            pct >= IttcDisplay.ROWS_LARGE -> R.string.ittc_ds_rows_large
            else -> R.string.ittc_ds_rows_comfortable
        }

        fun open(context: Context) {
            context.startActivity(Intent(context, IttcDisplayActivity::class.java))
        }
    }
}
