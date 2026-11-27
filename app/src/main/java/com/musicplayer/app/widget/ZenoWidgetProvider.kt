package com.musicplayer.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.ui.MainActivity

/**
 * Home-screen widget — cover + title/artist + prev / play-pause / next,
 * without opening the app. Tapping the cover/title opens the library.
 *
 * The playback service pushes [updateAll] on every track/state change;
 * the provider also refreshes on system updates.
 */
class ZenoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ZenoWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val song = PlayerConnection.currentSong()
            val playing = PlayerConnection.isPlaying()
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_zeno)
                views.setTextViewText(R.id.widgetTitle, song?.title ?: context.getString(R.string.no_song))
                views.setTextViewText(R.id.widgetArtist, song?.artist ?: context.getString(R.string.from_device))
                views.setImageViewResource(
                    R.id.widgetPlayPause,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
                views.setImageViewResource(R.id.widgetCover, R.drawable.zeno_logo_mark)
                views.setOnClickPendingIntent(R.id.widgetPrev, actionIntent(context, WidgetActionReceiver.ACTION_PREV))
                views.setOnClickPendingIntent(R.id.widgetPlayPause, actionIntent(context, WidgetActionReceiver.ACTION_TOGGLE))
                views.setOnClickPendingIntent(R.id.widgetNext, actionIntent(context, WidgetActionReceiver.ACTION_NEXT))
                views.setOnClickPendingIntent(
                    R.id.widgetOpen,
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                manager.updateAppWidget(id, views)
            }
            // Real cover (background thread, cached); the mark stays if none.
            if (song != null) {
                Thread {
                    try {
                        val bitmap = AlbumArtLoader.loadTrackCover(context, song)
                        if (bitmap != null) {
                            val views = RemoteViews(context.packageName, R.layout.widget_zeno)
                            views.setImageViewBitmap(R.id.widgetCover, bitmap)
                            for (id in ids) manager.updateAppWidget(id, views)
                        }
                    } catch (e: Exception) {
                    }
                }.start()
            }
        }

        private fun actionIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
