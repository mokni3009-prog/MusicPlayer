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
            // v1.0.16 (m14): a failed re-schedule after reboot used to
            // fail silently — every alarm would then be dead until the
            // app was opened. Log it so it is visible on the server.
            com.musicplayer.app.report.ErrorReporter.log(
                com.musicplayer.app.report.ErrorReporter.TYPE_ERROR,
                "Alarm re-schedule after boot failed: ${e.message}",
                e
            )
        }
    }
}
