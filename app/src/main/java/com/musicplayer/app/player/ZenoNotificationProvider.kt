package com.musicplayer.app.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.musicplayer.app.R
import com.musicplayer.app.data.Song
import com.musicplayer.app.ui.LockActivity
import com.musicplayer.app.ui.LuxuryPrefs

/**
 * The playback notification provider (Media3 1.5).
 *
 * Media3 1.5 removed the old `MediaSessionService.onUpdateNotification`
 * override and the `MediaSession.Builder.setMediaNotificationProvider`
 * call: notifications are now provided through [MediaNotification.Provider]
 * (set on the service with `setMediaNotificationProvider`), and its
 * `createNotification` returns a [MediaNotification] instead of a raw
 * [Notification].
 *
 * Behaviour (same as before):
 * - Normal tracks: the standard Media3 notification.
 * - VLC tracks: a custom notification with broadcast actions.
 * - When the full lock screen option is on, tapping the notification
 *   opens LockActivity (blurred cover + full controls) instead of the app.
 *   The preference is read on every notification build, so toggling it
 *   takes effect immediately.
 */
@OptIn(UnstableApi::class)
class ZenoNotificationProvider(context: Context) : MediaNotification.Provider {

    private val appContext = context.applicationContext
    private val defaultProvider = DefaultMediaNotificationProvider(context)

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val result = if (VlcEngine.active) {
            val song = PlayerConnection.currentSong()
            if (song == null) {
                defaultProvider.createNotification(
                    mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback
                )
            } else {
                MediaNotification(
                    DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                    buildVlcNotification(song)
                )
            }
        } else {
            defaultProvider.createNotification(
                mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback
            )
        }
        if (LuxuryPrefs.lockScreenEnabled(appContext)) {
            try {
                val intent = Intent(appContext, LockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                result.notification.contentIntent = PendingIntent.getActivity(
                    appContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (e: Exception) {
            }
        }
        return result
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        return defaultProvider.handleCustomCommand(session, action, extras)
    }

    /** Custom notification for VLC tracks (moved here from the old
     * `PlaybackService.onUpdateNotification` override). */
    private fun buildVlcNotification(song: Song): Notification {
        val playing = VlcEngine.isPlaying
        val toggleIntent = PendingIntent.getBroadcast(
            appContext, 0,
            Intent(appContext, VlcActionReceiver::class.java).setAction(VlcActionReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getBroadcast(
            appContext, 1,
            Intent(appContext, VlcActionReceiver::class.java).setAction(VlcActionReceiver.ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            appContext, 2,
            Intent(appContext, VlcActionReceiver::class.java).setAction(VlcActionReceiver.ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getBroadcast(
            appContext, 3,
            Intent(appContext, VlcActionReceiver::class.java).setAction(VlcActionReceiver.ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(appContext, PlaybackService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(appContext.getString(R.string.app_name))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_previous, appContext.getString(R.string.previous), prevIntent)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                appContext.getString(if (playing) R.string.pause else R.string.play),
                toggleIntent
            )
            .addAction(R.drawable.ic_next, appContext.getString(R.string.next), nextIntent)
            .addAction(R.drawable.ic_close, appContext.getString(R.string.close), closeIntent)
            .build()
    }
}
