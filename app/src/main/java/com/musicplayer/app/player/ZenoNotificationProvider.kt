package com.musicplayer.app.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
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
 * Behaviour (v1.0.10 — one custom notification for BOTH engines):
 * - Always four actions: previous / play-pause / next / close (X), in
 *   that order. The Media3 default provider only adds Next when the
 *   player reports a next item (Util.getAvailableCommands adds
 *   COMMAND_SEEK_TO_NEXT_MEDIA_ITEM only when hasNextMediaItem() is
 *   true), so on the last song of a queue — or a single-song queue —
 *   the notification showed only Previous + Pause. The custom actions
 *   below call the service directly, so Next is always there and always
 *   runs the same logic as the player's Next button (including the
 *   "play once then next" mode).
 * - v1.0.14: the three playback buttons go to the service itself
 *   ([PlaybackService.ACTION_NOTIFICATION_PREV/TOGGLE/NEXT]) instead of
 *   broadcasts to [VlcActionReceiver] — the broadcast path ran through
 *   the activity's MediaController, which is dead once the app UI is
 *   closed, so the buttons went silently dead. The receiver stays as a
 *   fallback for notifications posted by older versions.
 * - The notification is ongoing: it can never be swiped away, while
 *   playing AND while paused. Only the X button closes it (see
 *   [PlaybackService.ACTION_CLOSE_NOTIFICATION]).
 * - Big notification: app name + song number in the queue, title,
 *   artist, cover art, and the four buttons — Zeno identity (dark
 *   navy + gold->cyan), same as the reference design.
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
        val song = PlayerConnection.currentSong()
        val result = if (song == null) {
            // No song yet: fall back to the Media3 default notification.
            defaultProvider.createNotification(
                mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback
            )
        } else {
            MediaNotification(
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                buildNotification(mediaSession, song)
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
        // No Media3 custom actions are used (all buttons are plain
        // service intents): let the session handle it.
        return false
    }

    /** One notification for both engines, built from the current song. */
    private fun buildNotification(session: MediaSession, song: Song): Notification {
        val player = session.player
        val vlc = VlcEngine.active
        val playing = if (vlc) VlcEngine.isPlaying else player.isPlaying
        val positionMs = if (vlc) VlcEngine.positionMs else player.currentPosition
        val index = player.currentMediaItemIndex
        val count = player.mediaItemCount
        val subText = if (index >= 0 && count > 0) {
            "${appContext.getString(R.string.app_name)} • ${index + 1}/$count"
        } else {
            appContext.getString(R.string.app_name)
        }
        val builder = NotificationCompat.Builder(appContext, PlaybackService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(subText)
            .setContentIntent(session.sessionActivity)
            // Ongoing in both states: the user closes it only with X.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .addAction(
                R.drawable.ic_previous,
                appContext.getString(R.string.previous),
                serviceIntent(PlaybackService.ACTION_NOTIFICATION_PREV, 1)
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                appContext.getString(if (playing) R.string.pause else R.string.play),
                serviceIntent(PlaybackService.ACTION_NOTIFICATION_TOGGLE, 0)
            )
            .addAction(
                R.drawable.ic_next,
                appContext.getString(R.string.next),
                serviceIntent(PlaybackService.ACTION_NOTIFICATION_NEXT, 2)
            )
            .addAction(
                R.drawable.ic_close,
                appContext.getString(R.string.close),
                closeIntent()
            )
        // Cover art (same loader the player UI uses; cached, null when the
        // track has no embedded picture).
        try {
            AlbumArtLoader.loadTrackCover(appContext, song)?.let { builder.setLargeIcon(it) }
        } catch (e: Exception) {
        }
        // Elapsed time while playing (like the Media3 default notification).
        if (playing) {
            builder.setWhen(System.currentTimeMillis() - positionMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Playback buttons: handled by the service itself (v1.0.14) so they
     * work even when the app UI is destroyed. */
    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            appContext, requestCode,
            Intent(appContext, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** X: handled by the service itself (stop playback, dismiss, stopSelf). */
    private fun closeIntent(): PendingIntent =
        PendingIntent.getService(
            appContext, 3,
            Intent(appContext, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_CLOSE_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
