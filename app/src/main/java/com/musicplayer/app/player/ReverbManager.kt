package com.musicplayer.app.player

import android.content.Context
import android.media.audiofx.PresetReverb
import com.musicplayer.app.report.ErrorReporter

/**
 * Reverb sound effects — the Android built-in PresetReverb on the
 * player's audio session (same attachment point as the equalizer).
 *
 * Presets: 0 = none, 1 = small room, 2 = studio (medium room),
 * 3 = concert hall (large hall). VLC tracks have no reverb (Media3 only).
 */
object ReverbManager {

    const val PRESET_NONE = 0
    const val PRESET_SMALL_ROOM = 1
    const val PRESET_STUDIO = 2
    const val PRESET_CONCERT_HALL = 3

    private const val PREFS = "zeno_reverb"
    private const val KEY_PRESET = "preset"

    private var reverb: PresetReverb? = null
    private var attachedSessionId = 0

    fun loadPreset(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PRESET, PRESET_NONE)
        } catch (e: Exception) {
            PRESET_NONE
        }
    }

    fun savePreset(context: Context, preset: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PRESET, preset.coerceIn(0, 3)).apply()
        } catch (e: Exception) {
        }
    }

    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        try {
            if (attachedSessionId == audioSessionId && reverb != null) return
            release()
            reverb = PresetReverb(0, audioSessionId)
            attachedSessionId = audioSessionId
            applyPreset(context)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Reverb attach failed: ${e.message}",
                e
            )
        }
    }

    fun applyPreset(context: Context) {
        val preset = loadPreset(context)
        val rev = reverb ?: return
        try {
            rev.preset = when (preset) {
                PRESET_SMALL_ROOM -> PresetReverb.PRESET_SMALLROOM
                PRESET_STUDIO -> PresetReverb.PRESET_MEDIUMROOM
                PRESET_CONCERT_HALL -> PresetReverb.PRESET_LARGEHALL
                else -> PresetReverb.PRESET_NONE
            }
            rev.enabled = preset != PRESET_NONE
        } catch (e: Exception) {
        }
    }

    fun setPreset(context: Context, preset: Int) {
        savePreset(context, preset)
        applyPreset(context)
    }

    fun release() {
        try {
            reverb?.release()
        } catch (e: Exception) {
        }
        reverb = null
        attachedSessionId = 0
    }
}
