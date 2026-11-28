package com.musicplayer.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback — button taps and beat pulses. Uses the system's
 * haptic engine (no permission needed). Strength comes from the
 * luxury settings (light / medium / strong).
 */
object Haptics {

    /** Short tap feedback on a button. */
    fun tap(view: View, context: Context) {
        if (!LuxuryPrefs.hapticsEnabled(context)) return
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (e: Exception) {
        }
    }

    /** Beat pulse: a short vibration with the music's bass hit. */
    fun beat(context: Context) {
        if (!LuxuryPrefs.hapticsEnabled(context)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            val millis = when (LuxuryPrefs.hapticsStrength(context)) {
                0 -> 10L
                2 -> 35L
                else -> 20L
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        millis, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis)
            }
        } catch (e: Exception) {
        }
    }
}
