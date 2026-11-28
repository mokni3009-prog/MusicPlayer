package com.musicplayer.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.musicplayer.app.player.PlaybackService

/**
 * Fires the music alarm: starts the playback service with the chosen
 * song. The service ramps the volume up gradually (see PlaybackService
 * ACTION_ALARM handling). If the alarm was disabled, does nothing.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AlarmActivity.isEnabled(context)) return
        val songId = intent.getLongExtra(AlarmActivity.EXTRA_SONG_ID, -1L)
        if (songId < 0) return
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = AlarmActivity.ACTION_ALARM
            putExtra(AlarmActivity.EXTRA_SONG_ID, songId)
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
}
