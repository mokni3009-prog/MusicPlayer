package com.musicplayer.app.player

/**
 * Carries live FFT data from the PlaybackService's Visualizer to the player screen.
 * The listener is set by PlayerActivity while it is visible.
 */
object VisualizerHub {

    var listener: ((ByteArray) -> Unit)? = null

    fun publish(fft: ByteArray) {
        listener?.invoke(fft)
    }
}
