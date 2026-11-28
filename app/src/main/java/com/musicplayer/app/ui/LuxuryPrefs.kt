package com.musicplayer.app.ui

import android.content.Context

/**
 * Luxury features' options (Settings). Each feature has its own switch /
 * choices here; the player and the settings screen read from this single
 * place. All values are plain SharedPreferences — nothing leaves the device.
 */
object LuxuryPrefs {

    private const val PREFS = "zeno_luxury"

    // 1. Blurred cover background in the full player
    fun blurEnabled(context: Context): Boolean = getBool(context, "blur_enabled", true)
    fun setBlurEnabled(context: Context, v: Boolean) = putBool(context, "blur_enabled", v)
    /** 0 = light, 1 = medium, 2 = strong. */
    fun blurStrength(context: Context): Int = getInt(context, "blur_strength", 1)
    fun setBlurStrength(context: Context, v: Int) = putInt(context, "blur_strength", v.coerceIn(0, 2))

    // 2. Cover tilt with the gyroscope
    fun gyroEnabled(context: Context): Boolean = getBool(context, "gyro_enabled", false)
    fun setGyroEnabled(context: Context, v: Boolean) = putBool(context, "gyro_enabled", v)
    /** 0 = low, 1 = medium, 2 = high sensitivity. */
    fun gyroSensitivity(context: Context): Int = getInt(context, "gyro_sensitivity", 1)
    fun setGyroSensitivity(context: Context, v: Int) = putInt(context, "gyro_sensitivity", v.coerceIn(0, 2))

    // 3. Haptics on buttons and with the beats
    fun hapticsEnabled(context: Context): Boolean = getBool(context, "haptics_enabled", true)
    fun setHapticsEnabled(context: Context, v: Boolean) = putBool(context, "haptics_enabled", v)
    /** 0 = light, 1 = medium, 2 = strong. */
    fun hapticsStrength(context: Context): Int = getInt(context, "haptics_strength", 1)
    fun setHapticsStrength(context: Context, v: Int) = putInt(context, "haptics_strength", v.coerceIn(0, 2))

    // 4. Golden particles around the cover
    fun particlesEnabled(context: Context): Boolean = getBool(context, "particles_enabled", true)
    fun setParticlesEnabled(context: Context, v: Boolean) = putBool(context, "particles_enabled", v)
    /** 0 = few, 1 = medium, 2 = many. */
    fun particlesDensity(context: Context): Int = getInt(context, "particles_density", 1)
    fun setParticlesDensity(context: Context, v: Int) = putInt(context, "particles_density", v.coerceIn(0, 2))

    // 5. Animated equalizer in the mini player: 0 = bars, 1 = smoke, 2 = pulse
    fun miniEqEnabled(context: Context): Boolean = getBool(context, "mini_eq_enabled", true)
    fun setMiniEqEnabled(context: Context, v: Boolean) = putBool(context, "mini_eq_enabled", v)
    fun miniEqStyle(context: Context): Int = getInt(context, "mini_eq_style", 0)
    fun setMiniEqStyle(context: Context, v: Int) = putInt(context, "mini_eq_style", v.coerceIn(0, 2))

    // 6. Karaoke lyrics: 0 = line by line, 1 = word by word
    fun karaokeMode(context: Context): Int = getInt(context, "karaoke_mode", 0)
    fun setKaraokeMode(context: Context, v: Int) = putInt(context, "karaoke_mode", v.coerceIn(0, 1))

    // 7. Wrapped summary: enabled + period (0 = yearly, 1 = monthly)
    fun wrappedEnabled(context: Context): Boolean = getBool(context, "wrapped_enabled", true)
    fun setWrappedEnabled(context: Context, v: Boolean) = putBool(context, "wrapped_enabled", v)
    fun wrappedPeriod(context: Context): Int = getInt(context, "wrapped_period", 0)
    fun setWrappedPeriod(context: Context, v: Int) = putInt(context, "wrapped_period", v.coerceIn(0, 1))

    // 9. Full lock screen (blurred cover + full controls)
    fun lockScreenEnabled(context: Context): Boolean = getBool(context, "lock_screen_enabled", true)
    fun setLockScreenEnabled(context: Context, v: Boolean) = putBool(context, "lock_screen_enabled", v)

    // 10. Theme colours: 0 = gold/cyan (Zeno), 1 = pink, 2 = auto from cover
    fun themeMode(context: Context): Int = getInt(context, "theme_mode", 0)
    fun setThemeMode(context: Context, v: Int) = putInt(context, "theme_mode", v.coerceIn(0, 2))

    // 11. Double-tap the cover to like (flying heart)
    fun doubleTapLikeEnabled(context: Context): Boolean = getBool(context, "doubletap_like_enabled", true)
    fun setDoubleTapLikeEnabled(context: Context, v: Boolean) = putBool(context, "doubletap_like_enabled", v)

    private fun getBool(context: Context, key: String, def: Boolean): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, def)
        } catch (e: Exception) {
            def
        }
    }

    private fun putBool(context: Context, key: String, v: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
        } catch (e: Exception) {
        }
    }

    private fun getInt(context: Context, key: String, def: Int): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, def)
        } catch (e: Exception) {
            def
        }
    }

    private fun putInt(context: Context, key: String, v: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, v).apply()
        } catch (e: Exception) {
        }
    }
}
