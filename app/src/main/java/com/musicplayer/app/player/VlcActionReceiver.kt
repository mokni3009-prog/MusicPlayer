package com.musicplayer.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions for the custom VLC notification (see
 * [PlaybackService.onUpdateNotification]): previous, play/pause, next and
 * close. These are used only while a rare-format track plays on the VLC
 * fallback engine; normal tracks use the Media3 notification.
 */
class VlcActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "com.musicplayer.app.VLC_PREV"
        const val ACTION_TOGGLE = "com.musicplayer.app.VLC_TOGGLE"
        const val ACTION_NEXT = "com.musicplayer.app.VLC_NEXT"
        const val ACTION_CLOSE = "com.musicplayer.app.VLC_CLOSE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_PREV -> PlayerConnection.previous()
            ACTION_TOGGLE -> PlayerConnection.togglePlayPause()
            ACTION_NEXT -> PlayerConnection.next()
            ACTION_CLOSE -> {
                VlcEngine.stop()
                PlayerConnection.pause()
            }
        }
    }
}
