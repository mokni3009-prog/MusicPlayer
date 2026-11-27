package com.musicplayer.app.player

import android.content.Context
import android.media.audiofx.Visualizer
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback service. Media3 shows the media notification
 * automatically (play / pause / next / previous) while playback is active.
 *
 * It also drives the audio visualizer. The primary source is
 * [SpectrumAudioProcessor], which reads the real PCM stream inside ExoPlayer
 * and publishes live spectrum data to [VisualizerHub] — this works on every
 * device. Audio offload is disabled so the PCM always flows through it.
 * The system [Visualizer] effect is kept only as a backup: it is only
 * forwarded when the processor has been silent for a while.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var visualizer: Visualizer? = null
    private val spectrumProcessor = SpectrumAudioProcessor()

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            setupVisualizer(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // When paused, publish silence once so the waves fall flat at once.
            if (!isPlaying) {
                VisualizerHub.publishSilence()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Media3 1.4.1 has no ExoPlayer.Builder.setAudioProcessors: the audio
        // processors are attached through a DefaultAudioSink built by a custom
        // DefaultRenderersFactory.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf<androidx.media3.common.audio.AudioProcessor>(spectrumProcessor)
                    )
                    .build()
            }
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
        // Media3 1.4.1 has no ExoPlayer.Builder.setAudioOffloadPreferences:
        // offload is disabled through the track selection parameters, so the
        // PCM always flows through the in-app spectrum processor.
        val exoPlayer = player!!
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    )
                    .build()
            )
            .build()
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
                        // Backup only: VisualizerHub drops this while the
                        // SpectrumAudioProcessor is publishing.
                        VisualizerHub.publishSystem(fft.copyOf())
                    }
                },
                Visualizer.getMaxCaptureRate(), false, true
            )
            viz.enabled = true
            visualizer = viz
        } catch (e: Exception) {
            // Visualizer not available on this device; the in-app processor
            // is the primary source, so playback continues with waves anyway.
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
