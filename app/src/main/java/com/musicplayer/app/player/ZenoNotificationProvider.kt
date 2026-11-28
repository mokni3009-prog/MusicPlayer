package com.musicplayer.app.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.musicplayer.app.ui.LockActivity
import com.musicplayer.app.ui.LuxuryPrefs

/**
 * The playback notification. When the full lock screen option is on,
 * tapping the notification opens LockActivity (shown over the lock
 * screen, blurred cover + full controls); otherwise it opens the app
 * as usual. Everything else is the standard Media3 notification.
 */
class ZenoNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    private val appContext = context.applicationContext

    override fun getNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaSession.ActionFactory,
        onNotificationChangedListener: MediaSession.NotificationChangedListener
    ): Notification {
        val notification = super.getNotification(
            mediaSession, customLayout, actionFactory, onNotificationChangedListener
        )
        if (LuxuryPrefs.lockScreenEnabled(appContext)) {
            try {
                val intent = Intent(appContext, LockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                notification.contentIntent = PendingIntent.getActivity(
                    appContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (e: Exception) {
            }
        }
        return notification
    }
}
