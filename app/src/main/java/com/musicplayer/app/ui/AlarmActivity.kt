package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.musicplayer.app.databinding.ActivityAlarmBinding

/**
 * v1.0.13: the alarm app as a standalone screen — the same alarm screen
 * (design 3) as the ALARME tab in the library. The old single-alarm form
 * (one time, one song, on/off in the settings) is gone; alarms are now
 * multiple and live in [AlarmStore].
 */
class AlarmActivity : FullScreenActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AlarmActivity::class.java))
        }
    }

    private lateinit var binding: ActivityAlarmBinding
    private lateinit var screen: AlarmScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        screen = AlarmScreen(this, binding.alarmTab)
    }

    override fun onStart() {
        super.onStart()
        screen.start()
    }

    override fun onStop() {
        screen.stop()
        super.onStop()
    }
}
