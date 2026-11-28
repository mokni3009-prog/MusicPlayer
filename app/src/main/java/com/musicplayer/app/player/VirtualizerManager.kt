package com.musicplayer.app.player

import android.content.Context
import android.media.audiofx.Virtualizer
import com.musicplayer.app.report.ErrorReporter

/**
 * v1.0.16 — 3D surround / Virtualizer (feature 3): the Android
 * built-in Virtualizer on the player's audio session (same attachment
 * point as the equalizer and the reverb, so it cannot break their
 * chain — it is a separate effect on the same session).
 *
 * Strength: 0 = light, 1 = medium, 2 = strong (300 / 600 / 1000 on
 * the Virtualizer's 0..1000 scale). Off by default.
 *
 * VLC tracks have no Virtualizer (Media3 only) — libVLC has no
 * equivalent effect, and the Virtualizer needs an Android audio
 * session. Some devices do not support it at all (creation throws);
 * then the feature silently stays off.
 */
object VirtualizerManager {

    private const val PREFS = "zeno_virtualizer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRENGTH = "strength"

    private var virtualizer: Virtualizer? = null

    @Volatile
    var attachedSessionId = 0
        private set

    /** True when a live Virtualizer is attached to an audio session. */
    fun isAttached(): Boolean = virtualizer != null && attachedSessionId != 0

    fun isEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            false
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
        }
        applyCurrent(context)
    }

    /** Strength index 0 = light, 1 = medium, 2 = strong. */
    fun strength(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_STRENGTH, 1)
        } catch (e: Exception) {
            1
        }
    }

    fun setStrength(context: Context, strength: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_STRENGTH, strength.coerceIn(0, 2)).apply()
        } catch (e: Exception) {
        }
        applyCurrent(context)
    }

    private fun strengthValue(context: Context): Short {
        return when (strength(context)) {
            0 -> 300
            2 -> 1000
            else -> 600
        }.toShort()
    }

    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        try {
            if (attachedSessionId == audioSessionId && virtualizer != null) return
            release()
            virtualizer = Virtualizer(0, audioSessionId)
            attachedSessionId = audioSessionId
            applyCurrent(context)
        } catch (e: Exception) {
            // Unsupported on this device (or session) — stay off.
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Virtualizer attach failed: ${e.message}",
                e
            )
            release()
        }
    }

    fun applyCurrent(context: Context) {
        val v = virtualizer ?: return
        try {
            v.setStrength(strengthValue(context))
            v.enabled = isEnabled(context)
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            virtualizer?.release()
        } catch (e: Exception) {
        }
        virtualizer = null
        attachedSessionId = 0
    }
}
