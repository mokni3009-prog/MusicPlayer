package com.musicplayer.app.player

import android.media.audiofx.Visualizer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback service. Media3 shows the media notification
 * automatically (play / pause / next / previous) while playback is active.
 *
 * It also drives the real audio visualizer: a [Visualizer] is attached to
 * the ExoPlayer audio session and publishes live FFT data to [VisualizerHub],
 * which the player screen draws as animated bars.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var visualizer: Visualizer? = null

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            setupVisualizer(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // When paused, publish silence once so the waves fall flat at once.
            if (!isPlaying) {
                VisualizerHub.publish(ByteArray(1024))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        player?.addListener(playerListener)
        session = MediaSession.Builder(this, player!!).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onDestroy() {
        player?.removeListener(playerListener)
        releaseVisualizer()
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    private fun setupVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) return
        releaseVisualizer()
        try {
            val viz = Visualizer(audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        viz: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int
                    ) {
                        // Waveform not used; FFT bars only.
                    }

                    override fun onFftDataCapture(viz: Visualizer, fft: ByteArray, samplingRate: Int) {
                        VisualizerHub.publish(fft.copyOf())
                    }
                },
                Visualizer.getMaxCaptureRate(), false, true
            )
            viz.enabled = true
            visualizer = viz
        } catch (e: Exception) {
            // Visualizer not available on this device; playback continues without waves.
            visualizer = null
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.release()
        } catch (e: Exception) {
            // Ignore release errors.
        }
        visualizer = null
    }
}
