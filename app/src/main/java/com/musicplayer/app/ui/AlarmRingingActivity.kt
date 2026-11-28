package com.musicplayer.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivityAlarmRingingBinding
import com.musicplayer.app.player.PlaybackService
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v1.0.13: ringing screen — shown over the lock screen by the alarm
 * notification's full-screen intent, with one big Stop button.
 * Finishes itself when the alarm is stopped from the notification.
 */
class AlarmRingingActivity : FullScreenActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ringAlarmName.text =
            intent.getStringExtra(AlarmStore.EXTRA_ALARM_NAME)
                ?: getString(R.string.alarm_default_name)
        binding.ringSongTitle.text =
            intent.getStringExtra(AlarmStore.EXTRA_SONG_TITLE) ?: ""
        binding.ringTime.text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis())

        binding.ringStopBtn.setOnClickListener {
            stopAlarm()
            finish()
        }
        ContextCompat.registerReceiver(
            this, stoppedReceiver,
            IntentFilter(AlarmStore.ACTION_ALARM_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stoppedReceiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun stopAlarm() {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = AlarmStore.ACTION_STOP_ALARM
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
        }
    }
}
