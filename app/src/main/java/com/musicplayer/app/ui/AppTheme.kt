package com.musicplayer.app.ui

import android.content.Context
import android.content.res.Configuration

/**
 * App theme choice: the default Zeno Play navy identity, or the pure-black
 * AMOLED variant (same identity, black backgrounds for AMOLED screens).
 */
object AppTheme {
    private const val PREFS = "zeno_theme"
    private const val KEY_AMOLED = "amoled"

    fun isAmoled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AMOLED, false)
        } catch (e: Exception) {
            false
        }
    }

    fun setAmoled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AMOLED, enabled).apply()
        } catch (e: Exception) {
            // Ignore persistence errors; the theme just won't stick.
        }
    }

    /** True on Android TV (leanback) devices — no touchscreen, TV UI mode. */
    fun isTv(context: Context): Boolean {
        return try {
            val uiMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_TYPE_MASK
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
                !context.packageManager.hasSystemFeature("android.hardware.touchscreen")
        } catch (e: Exception) {
            false
        }
    }

    /** True on tablets (smallest width >= 600dp) — rotation is allowed. */
    fun isTablet(context: Context): Boolean {
        return try {
            context.resources.configuration.smallestScreenWidthDp >= 600
        } catch (e: Exception) {
            false
        }
    }
}
