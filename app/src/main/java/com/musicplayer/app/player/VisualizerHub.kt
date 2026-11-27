package com.musicplayer.app.player

import android.os.SystemClock
import kotlin.math.hypot

/**
 * Carries live spectrum data to the player screen as 32 normalized magnitudes
 * (0..1), one per bar. Two sources feed it:
 *  - the in-app [SpectrumAudioProcessor] (primary: reads the real PCM stream,
 *    works on every device);
 *  - the system android.media.audiofx.Visualizer (backup: only forwarded when
 *    the processor has been silent for a while, so the two never fight).
 */
object VisualizerHub {

    const val BAR_COUNT = 32

    var listener: ((FloatArray) -> Unit)? = null

    private var lastProcessorPublishMs = 0L

    fun publishProcessor(magnitudes: FloatArray) {
        lastProcessorPublishMs = SystemClock.uptimeMillis()
        listener?.invoke(magnitudes)
    }

    fun publishSystem(fft: ByteArray) {
        // Backup only: if the processor is alive, its data wins.
        if (SystemClock.uptimeMillis() - lastProcessorPublishMs < 300) return
        listener?.invoke(fftToMagnitudes(fft))
    }

    fun publishSilence() {
        listener?.invoke(FloatArray(BAR_COUNT))
    }

    private fun fftToMagnitudes(fft: ByteArray): FloatArray {
        val binCount = fft.size / 2
        val out = FloatArray(BAR_COUNT)
        if (binCount == 0) return out
        for (i in 0 until BAR_COUNT) {
            val bin = (i * binCount) / BAR_COUNT
            val re = fft[2 * bin].toInt()
            val im = fft[2 * bin + 1].toInt()
            out[i] = (hypot(re.toDouble(), im.toDouble()) / 110.0).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }
}
