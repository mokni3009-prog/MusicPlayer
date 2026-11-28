package com.musicplayer.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.musicplayer.app.R
import com.musicplayer.app.player.PlaybackService

/**
 * v1.0.13: fires one music alarm — looks the alarm up in the store
 * (a disabled or deleted alarm never rings), re-schedules a repeating
 * alarm for its next occurrence, disables a one-time alarm, then starts
 * the playback service with the chosen song. Works with the app closed:
 * the receiver runs on its own, no activity needed.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmStore.ACTION_ALARM) return
        val alarmId = intent.getStringExtra(AlarmStore.EXTRA_ALARM_ID)
        if (alarmId == null) {
            // Pre-v1.0.13 single alarm (scheduled without an id): fire it
            // once from the legacy prefs; the next store load migrates it.
            fireLegacyAlarm(context)
            return
        }
        val alarms = AlarmStore.load(context)
        val alarm = alarms.firstOrNull { it.id == alarmId } ?: return
        if (!alarm.enabled) return
        // Re-schedule / disable BEFORE firing, so a crash while ringing
        // can never lose the next occurrence.
        if (alarm.days == 0) {
            AlarmStore.save(
                context,
                alarms.map { if (it.id == alarmId) it.copy(enabled = false) else it }
            )
        } else {
            AlarmScheduler.schedule(context, alarm)
        }
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = AlarmStore.ACTION_ALARM
            putExtra(AlarmStore.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmStore.EXTRA_SONG_ID, alarm.songId)
            putExtra(AlarmStore.EXTRA_ALARM_NAME, alarm.name)
            putExtra(AlarmStore.EXTRA_SONG_TITLE, alarm.songTitle)
            putExtra(AlarmStore.EXTRA_FADE_IN, alarm.fadeIn)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
        }
    }

    /** A pre-v1.0.13 alarm that was already scheduled when the app was
     * updated: ring it once from the legacy prefs. */
    private fun fireLegacyAlarm(context: Context) {
        try {
            val legacy = context.getSharedPreferences("zeno_alarm", Context.MODE_PRIVATE)
            if (!legacy.getBoolean("enabled", false)) return
            val songId = legacy.getLong("song_id", -1L)
            if (songId < 0) return
            legacy.edit().putBoolean("enabled", false).apply()
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                action = AlarmStore.ACTION_ALARM
                putExtra(AlarmStore.EXTRA_ALARM_ID, "legacy")
                putExtra(AlarmStore.EXTRA_SONG_ID, songId)
                putExtra(
                    AlarmStore.EXTRA_ALARM_NAME,
                    context.getString(R.string.alarm_default_name)
                )
                putExtra(AlarmStore.EXTRA_SONG_TITLE, "")
                putExtra(AlarmStore.EXTRA_FADE_IN, true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
    }
}
