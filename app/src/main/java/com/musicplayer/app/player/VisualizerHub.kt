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

    /** Extra listeners (e.g. the mini-player equalizer) — the single
     * [listener] above stays for the full player. */
    private val extraListeners = mutableSetOf<(FloatArray) -> Unit>()

    fun addListener(l: (FloatArray) -> Unit) {
        extraListeners.add(l)
    }

    fun removeListener(l: (FloatArray) -> Unit) {
        extraListeners.remove(l)
    }

    private fun emit(magnitudes: FloatArray) {
        // v1.0.16 (m23): the main listener used to be invoked unguarded
        // — a throw in the player screen's drawing code crashed the
        // audio thread. Guard it like the extra listeners.
        try {
            listener?.invoke(magnitudes)
        } catch (e: Exception) {
        }
        for (l in extraListeners.toList()) {
            try {
                l(magnitudes)
            } catch (e: Exception) {
            }
        }
    }

    private var lastProcessorPublishMs = 0L
    private var lastSystemPublishMs = 0L

    fun publishProcessor(magnitudes: FloatArray) {
        lastProcessorPublishMs = SystemClock.uptimeMillis()
        emit(magnitudes)
    }

    fun publishSystem(fft: ByteArray) {
        // Backup only: if the processor is alive, its data wins.
        if (SystemClock.uptimeMillis() - lastProcessorPublishMs < 300) return
        lastSystemPublishMs = SystemClock.uptimeMillis()
        emit(fftToMagnitudes(fft))
    }

    fun publishSilence() {
        emit(FloatArray(BAR_COUNT))
    }

    /** True when the spectrum data arriving right now comes from the
     * REAL audio (SpectrumAudioProcessor FFT on Media3 tracks, or the
     * system Visualizer FFT) — false when the views are only
     * simulating (e.g. VLC tracks, or nothing playing). Used by the
     * self-test to report the waves mode honestly. */
    fun isRealDataFresh(): Boolean =
        SystemClock.uptimeMillis() -
            maxOf(lastProcessorPublishMs, lastSystemPublishMs) < 600

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
