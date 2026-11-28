package com.musicplayer.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.musicplayer.app.report.ErrorReporter

/**
 * Notification actions for the playback notification (both engines):
 * previous, play/pause and next. The actions call PlayerConnection
 * directly, so they run exactly the same logic as the player's own
 * buttons (including the "play once then next" mode). The close (X)
 * button is NOT handled here: it goes to the service itself
 * ([PlaybackService.ACTION_CLOSE_NOTIFICATION]) so it can stop
 * playback, dismiss the notification and stop the service cleanly.
 *
 * v1.0.14: this receiver is now only a FALLBACK for notifications
 * posted by older versions. New notifications send the buttons
 * straight to the service ([PlaybackService.ACTION_NOTIFICATION_*]),
 * because this path runs through the activity's MediaController, which
 * is dead once the app UI is closed — the buttons went silently dead.
 * A tap that finds no controller is now reported (TYPE_ERROR) instead
 * of failing silently.
 */
class VlcActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "com.musicplayer.app.VLC_PREV"
        const val ACTION_TOGGLE = "com.musicplayer.app.VLC_TOGGLE"
        const val ACTION_NEXT = "com.musicplayer.app.VLC_NEXT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_PREV -> {
                if (PlayerConnection.controller == null) {
                    ErrorReporter.log(
                        ErrorReporter.TYPE_ERROR,
                        "Notification previous ignored: no controller (stale notification?)"
                    )
                    return
                }
                PlayerConnection.previous()
            }
            ACTION_TOGGLE -> {
                // Toggle works without a controller while VLC owns the
                // track; otherwise a missing controller means the tap
                // can do nothing.
                if (PlayerConnection.controller == null && !VlcEngine.active) {
                    ErrorReporter.log(
                        ErrorReporter.TYPE_ERROR,
                        "Notification play/pause ignored: no controller and VLC idle (stale notification?)"
                    )
                    return
                }
                PlayerConnection.togglePlayPause()
            }
            ACTION_NEXT -> {
                if (PlayerConnection.controller == null) {
                    ErrorReporter.log(
                        ErrorReporter.TYPE_ERROR,
                        "Notification next ignored: no controller (stale notification?)"
                    )
                    return
                }
                PlayerConnection.next()
            }
        }
    }
}
