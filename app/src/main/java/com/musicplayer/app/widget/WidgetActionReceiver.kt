package com.musicplayer.app.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection

/**
 * Handles the home-screen widget buttons without opening the app.
 *
 * Fast path: the player service is alive and a controller is connected —
 * the action runs immediately. Otherwise a controller is connected on
 * demand (the broadcast stays alive with goAsync); on "play" with an
 * empty queue the saved queue is restored first, then playback starts.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "com.musicplayer.app.WIDGET_PREV"
        const val ACTION_TOGGLE = "com.musicplayer.app.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.musicplayer.app.WIDGET_NEXT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val existing = PlayerConnection.controller
        if (existing != null) {
            doAction(action)
            return
        }
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val timeout = Runnable {
            try {
                future.get()?.release()
            } catch (e: Exception) {
            }
            pending.finish()
        }
        handler.postDelayed(timeout, 5000)
        future.addListener({
            try {
                val controller = future.get()
                PlayerConnection.controller = controller
                if (controller.mediaItemCount == 0 && action == ACTION_TOGGLE) {
                    // Nothing loaded: resume the saved queue, then play.
                    Thread {
                        val library = MusicRepository.loadSongs(context)
                        Handler(Looper.getMainLooper()).post {
                            PlayerConnection.restoreSavedQueue(context, library)
                            PlayerConnection.play()
                            handler.removeCallbacks(timeout)
                            releaseLater(controller, handler, pending)
                        }
                    }.start()
                } else {
                    doAction(action)
                    handler.removeCallbacks(timeout)
                    releaseLater(controller, handler, pending)
                }
            } catch (e: Exception) {
                handler.removeCallbacks(timeout)
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun releaseLater(
        controller: MediaController,
        handler: Handler,
        pending: PendingResult
    ) {
        handler.postDelayed({
            if (PlayerConnection.controller == controller) PlayerConnection.controller = null
            try {
                controller.release()
            } catch (e: Exception) {
            }
            pending.finish()
        }, 500)
    }

    private fun doAction(action: String) {
        when (action) {
            ACTION_PREV -> PlayerConnection.previous()
            ACTION_TOGGLE -> PlayerConnection.togglePlayPause()
            ACTION_NEXT -> PlayerConnection.next()
        }
    }
}
