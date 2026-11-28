package com.musicplayer.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v1.0.13: after a reboot the AlarmManager alarms are gone — re-schedule
 * every enabled alarm so the alarm still rings.
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            AlarmScheduler.rescheduleAll(context)
        } catch (e: Exception) {
        }
    }
}
