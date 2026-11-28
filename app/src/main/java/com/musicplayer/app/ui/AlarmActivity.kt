package com.musicplayer.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.databinding.ActivityAlarmBinding
import java.util.Calendar

/**
 * Music alarm — wakes the user with a chosen song, the volume rising
 * gradually over 30 seconds. Stored locally (hour, minute, song, on/off);
 * the alarm itself is an exact AlarmManager alarm.
 */
class AlarmActivity : FullScreenActivity() {

    companion object {
        const val PREFS = "zeno_alarm"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_SONG_ID = "song_id"
        const val ACTION_ALARM = "com.musicplayer.app.ALARM"
        const val EXTRA_SONG_ID = "alarm_song_id"

        fun start(context: Context) {
            context.startActivity(Intent(context, AlarmActivity::class.java))
        }

        fun isEnabled(context: Context): Boolean {
            return try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false)
            } catch (e: Exception) {
                false
            }
        }
    }

    private lateinit var binding: ActivityAlarmBinding
    private var chosenSongId: Long = -1L
    private var chosenSongTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hour = prefs.getInt(KEY_HOUR, 7)
        val minute = prefs.getInt(KEY_MINUTE, 0)
        chosenSongId = prefs.getLong(KEY_SONG_ID, -1L)
        binding.timePicker.setIs24HourView(true)
        binding.timePicker.hour = hour
        binding.timePicker.minute = minute
        binding.enableSwitch.isChecked = isEnabled(this)
        refreshSongLabel()

        binding.pickSongButton.setOnClickListener { showSongPicker() }
        binding.saveButton.setOnClickListener { saveAlarm() }
        binding.disableButton.setOnClickListener { disableAlarm() }
    }

    private fun refreshSongLabel() {
        binding.songLabel.text = chosenSongTitle
            ?: if (chosenSongId >= 0) {
                MusicRepository.loadSongs(this)
                    .firstOrNull { it.id == chosenSongId }?.title
                    ?: getString(R.string.alarm_no_song)
            } else {
                getString(R.string.alarm_no_song)
            }
    }

    private fun showSongPicker() {
        val songs = MusicRepository.loadSongs(this)
        if (songs.isEmpty()) {
            Toast.makeText(this, R.string.alarm_no_library, Toast.LENGTH_SHORT).show()
            return
        }
        val titles = songs.map { "${it.title} — ${it.artist}" }.toTypedArray()
        val checked = songs.indexOfFirst { it.id == chosenSongId }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.alarm_pick_song)
            .setSingleChoiceItems(titles, checked) { dialog, which ->
                chosenSongId = songs[which].id
                chosenSongTitle = songs[which].title
                refreshSongLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAlarm() {
        if (chosenSongId < 0) {
            Toast.makeText(this, R.string.alarm_pick_song_first, Toast.LENGTH_SHORT).show()
            return
        }
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute
        val enabled = binding.enableSwitch.isChecked
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .putLong(KEY_SONG_ID, chosenSongId)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) {
            cancelAlarmIntent()
            Toast.makeText(this, R.string.alarm_disabled, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Android 12+: exact alarms need the user's grant in system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!manager.canScheduleExactAlarms()) {
                Toast.makeText(this, R.string.alarm_need_permission, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
        scheduleAlarm(hour, minute, chosenSongId)
        Toast.makeText(
            this,
            getString(R.string.alarm_saved, String.format("%02d:%02d", hour, minute)),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun disableAlarm() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false).apply()
        cancelAlarmIntent()
        Toast.makeText(this, R.string.alarm_disabled, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun alarmPendingIntent(songId: Long): PendingIntent {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_SONG_ID, songId)
        }
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm(hour: Int, minute: Int, songId: Long) {
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, trigger, alarmPendingIntent(songId)
            )
        } catch (e: Exception) {
            // Exact alarm not permitted: fall back to an inexact alarm.
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, trigger, alarmPendingIntent(songId)
            )
        }
    }

    private fun cancelAlarmIntent() {
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(alarmPendingIntent(chosenSongId))
    }
}
