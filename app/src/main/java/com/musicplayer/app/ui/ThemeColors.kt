package com.musicplayer.app.ui

import android.content.Context
import android.graphics.Color

/**
 * Theme colours for the player UI — three modes from Settings:
 * ZENO (gold -> cyan, the app identity), PINK (pink -> violet),
 * AUTO (the current song's cover colours, see CoverColors).
 *
 * The cover palette is cached here by the full player whenever a cover
 * loads, so the mini player and the header can follow it too.
 */
object ThemeColors {

    const val MODE_ZENO = 0
    const val MODE_PINK = 1
    const val MODE_AUTO = 2

    private val ZENO = Pair(0xFFFFD76A.toInt(), 0xFF2DE2E6.toInt())
    private val PINK = Pair(0xFFFF6FA5.toInt(), 0xFFB388FF.toInt())

    @Volatile
    private var coverPalette: CoverColors.Palette = CoverColors.DEFAULT

    fun setCoverPalette(palette: CoverColors.Palette) {
        coverPalette = palette
    }

    /** (accent, secondary) for the current theme mode. */
    fun current(context: Context): Pair<Int, Int> {
        return when (LuxuryPrefs.themeMode(context)) {
            MODE_PINK -> PINK
            MODE_AUTO -> coverPalette.accent to coverPalette.secondary
            else -> ZENO
        }
    }

    fun accent(context: Context): Int = current(context).first

    fun secondary(context: Context): Int = current(context).second

    /** A darker version of the accent, for text on dark backgrounds. */
    fun accentDark(context: Context): Int {
        val c = accent(context)
        return Color.rgb(
            (Color.red(c) * 0.75f).toInt().coerceIn(0, 255),
            (Color.green(c) * 0.75f).toInt().coerceIn(0, 255),
            (Color.blue(c) * 0.75f).toInt().coerceIn(0, 255)
        )
    }
}
