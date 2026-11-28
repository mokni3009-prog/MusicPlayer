package com.musicplayer.app.ui

import android.content.Context
import com.musicplayer.app.R

/**
 * Waveform shape in the full player — the user picks one from Settings
 * ("Forme des ondes"). The default is the circular smoke ring. Every
 * shape reads the real song's FFT (simulated on VLC tracks, like the
 * smoke ring) and applies immediately when the player opens.
 */
object WaveformStyle {

    const val SMOKE = 0
    const val RADIAL_BARS = 1
    const val STRAIGHT_BARS = 2
    const val WAVE = 3
    const val PULSE_CIRCLE = 4
    const val RINGS = 5
    const val PARTICLES = 6
    const val CIRCULAR_BAR = 7
    const val CIRCULAR_WAVE = 8
    const val SUN_RAYS = 9
    const val SPIRAL = 10
    const val AURORA = 11
    const val FLOWER = 12
    const val FLAME = 13
    const val CRYSTAL = 14
    const val GALAXY = 15
    const val RIBBONS = 16
    const val BUBBLES = 17
    const val HEART = 18
    const val RIPPLES = 19

    val LABELS = listOf(
        R.string.waveform_smoke,
        R.string.waveform_radial_bars,
        R.string.waveform_straight_bars,
        R.string.waveform_wave,
        R.string.waveform_pulse_circle,
        R.string.waveform_rings,
        R.string.waveform_particles,
        R.string.waveform_circular_bar,
        R.string.waveform_circular_wave,
        R.string.waveform_sun_rays,
        R.string.waveform_spiral,
        R.string.waveform_aurora,
        R.string.waveform_flower,
        R.string.waveform_flame,
        R.string.waveform_crystal,
        R.string.waveform_galaxy,
        R.string.waveform_ribbons,
        R.string.waveform_bubbles,
        R.string.waveform_heart,
        R.string.waveform_ripples
    )

    private const val PREFS = "zeno_waveform"
    private const val KEY_SHAPE = "waveform_shape"

    fun current(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SHAPE, SMOKE).coerceIn(0, LABELS.size - 1)
        } catch (e: Exception) {
            SMOKE
        }
    }

    fun set(context: Context, shape: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SHAPE, shape.coerceIn(0, LABELS.size - 1)).apply()
        } catch (e: Exception) {
        }
    }
}
