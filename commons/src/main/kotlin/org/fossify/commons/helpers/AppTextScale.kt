package org.fossify.commons.helpers

import android.content.Context
import android.content.res.Configuration
import org.fossify.commons.R
import org.fossify.commons.extensions.baseConfig
import kotlin.math.roundToInt

/**
 * 🎓 ITTC Phone 3.12: the app's own text size (80–150 %), on top of the phone's font size. Every
 * activity's base context gets a font scale of the phone's × this (attachBaseContext), so every sp
 * in the app follows it. [version] goes up with each change of a display size: open activities made
 * with an older one are made again when they come back to the screen.
 *
 * Read once from its own small preferences file and kept in memory (nothing read per activity).
 * Fossify's old font size setting (Small / Medium / Large / Extra large) is turned into the same
 * percent once and set back to the app's default, so the two never add up.
 */
object AppTextScale {
    const val PREFS = "ittc_display"
    const val KEY_TEXT = "text_pct"
    const val KEY_VERSION = "size_version"
    private const val KEY_MIGRATED = "font_size_migrated"

    const val MIN = 80
    const val MAX = 150
    const val STEP = 5

    @Volatile
    private var percentCache = -1

    @Volatile
    private var versionCache = -1

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The text size in percent (100: as the phone) */
    fun percent(context: Context): Int {
        if (percentCache < 0) load(context)
        return percentCache
    }

    /** Goes up with each change of a display size */
    fun version(context: Context): Int {
        if (versionCache < 0) load(context)
        return versionCache
    }

    @Synchronized
    private fun load(context: Context) {
        if (percentCache >= 0 && versionCache >= 0) return
        val prefs = prefs(context)
        var pct = prefs.getInt(KEY_TEXT, 100)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            // (Fossify's font size, relative to the app's default one, as a percent)
            try {
                val config = context.baseConfig
                val default = context.resources.getInteger(R.integer.default_font_size)
                val current = config.fontSize
                if (current != default && !prefs.contains(KEY_TEXT)) {
                    val sizes = intArrayOf(12, 16, 18, 22)
                    val from = sizes.getOrElse(default) { 18 }
                    val to = sizes.getOrElse(current) { from }
                    pct = snap(to * 100f / from)
                }
                if (current != default) config.fontSize = default
            } catch (_: Throwable) {
            }
            prefs.edit().putInt(KEY_TEXT, pct).putBoolean(KEY_MIGRATED, true).apply()
        }
        percentCache = pct.coerceIn(MIN, MAX)
        versionCache = prefs.getInt(KEY_VERSION, 0)
    }

    /** [value] rounded to a step of 5, within 80–150 */
    fun snap(value: Float): Int = ((value / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)

    /** Saves the text size (the open screens follow when they show again) */
    fun setPercent(context: Context, pct: Int) {
        val value = snap(pct.toFloat())
        if (value == percent(context)) return
        percentCache = value
        prefs(context).edit().putInt(KEY_TEXT, value).apply()
        bumpVersion(context)
    }

    /** A display size changed: every open screen is made again when it comes back */
    fun bumpVersion(context: Context) {
        val v = version(context) + 1
        versionCache = v
        prefs(context).edit().putInt(KEY_VERSION, v).apply()
    }

    /**
     * [base] with the font scale the phone's × the app's text size (the same context when it is
     * 100 %). For attachBaseContext.
     */
    fun wrap(base: Context): Context {
        val pct = try {
            percent(base)
        } catch (_: Throwable) {
            100
        }
        if (pct == 100) return base
        return try {
            // (only the font scale: every other value still follows the phone, e.g. on a turn)
            val config = Configuration()
            config.fontScale = base.resources.configuration.fontScale * pct / 100f
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }
    }
}
