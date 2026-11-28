package com.musicplayer.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions for the playback notification (both engines):
 * previous, play/pause and next. The actions call PlayerConnection
 * directly, so they run exactly the same logic as the player's own
 * buttons (including the "play once then next" mode). The close (X)
 * button is NOT handled here: it goes to the service itself
 * ([PlaybackService.ACTION_CLOSE_NOTIFICATION]) so it can stop
 * playback, dismiss the notification and stop the service cleanly.
 */
class VlcActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "com.musicplayer.app.VLC_PREV"
        const val ACTION_TOGGLE = "com.musicplayer.app.VLC_TOGGLE"
        const val ACTION_NEXT = "com.musicplayer.app.VLC_NEXT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_PREV -> PlayerConnection.previous()
            ACTION_TOGGLE -> PlayerConnection.togglePlayPause()
            ACTION_NEXT -> PlayerConnection.next()
        }
    }
}
