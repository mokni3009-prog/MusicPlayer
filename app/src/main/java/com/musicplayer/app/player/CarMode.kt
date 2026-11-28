package com.musicplayer.app.player

import android.content.Context
import android.content.Intent

/**
 * v1.0.16 — car mode (feature 9): activates AUTOMATICALLY when the
 * phone connects to the car's Bluetooth (A2DP car-audio device) and
 * switches back to normal when it disconnects. There is no manual
 * button — that was the user's explicit choice.
 *
 * The state lives in SharedPreferences so it survives process death;
 * [ACTION_CAR_MODE_CHANGED] is broadcast locally whenever it flips,
 * so the player can apply the big-button layout immediately.
 */
object CarMode {

    const val ACTION_CAR_MODE_CHANGED = "com.musicplayer.app.CAR_MODE_CHANGED"

    private const val PREFS = "zeno_car_mode"
    private const val KEY_ACTIVE = "active"

    fun isActive(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false)
        } catch (e: Exception) {
            false
        }
    }

    fun setActive(context: Context, active: Boolean) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val was = prefs.getBoolean(KEY_ACTIVE, false)
            if (was == active) return
            prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
            context.sendBroadcast(Intent(ACTION_CAR_MODE_CHANGED))
        } catch (e: Exception) {
        }
    }
}
