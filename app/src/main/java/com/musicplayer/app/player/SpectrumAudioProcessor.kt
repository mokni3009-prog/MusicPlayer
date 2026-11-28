package com.musicplayer.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computes real spectrum data from the actual PCM audio flowing through
 * ExoPlayer, so the player screen draws waves that truly follow the music.
 *
 * Unlike android.media.audiofx.Visualizer (a system effect that many devices
 * block, or that never receives data when audio is offloaded), this processor
 * sits in the app's own audio chain: every decoded PCM buffer passes through
 * it unchanged, and each 1024-sample mono window is analysed (Hann window +
 * radix-2 FFT) into 32 log-spaced magnitude bars published to [VisualizerHub].
 * Audio offload must stay disabled so PCM always flows through this chain.
 */
class SpectrumAudioProcessor : BaseAudioProcessor() {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private val window = FloatArray(FFT_SIZE)
    private var windowPos = 0
    private val smoothed = FloatArray(BAR_COUNT)
    private var lastPublishMs = 0L

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        windowPos = 0
        smoothed.fill(0f)
        // Pass the audio through unchanged, in the same format.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Analyse a duplicate (little-endian PCM) so the original buffer stays
        // untouched for the pass-through copy below.
        val copy = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (channelCount > 0 &&
            (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT ||
                encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT)
        ) {
            analyse(copy)
        }
        // Pass the audio through unchanged.
        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        // Never put() an empty buffer: after a reset, replaceOutputBuffer(0)
        // returns Media3's shared EMPTY_BUFFER, and put()ing that buffer
        // into itself throws IllegalArgumentException (src == this) — which
        // kills playback with ERROR_CODE_FAILED_RUNTIME_CHECK (1004). With
        // no data there is nothing to copy, so skip the put entirely.
        if (remaining > 0) {
            output.put(inputBuffer)
        }
        output.flip()
    }

    override fun onFlush() {
        windowPos = 0
    }

    override fun onReset() {
        windowPos = 0
        smoothed.fill(0f)
    }

    private fun analyse(buffer: ByteBuffer) {
        val frameBytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2 * channelCount
            C.ENCODING_PCM_FLOAT -> 4 * channelCount
            // v1.0.15: 24-bit and 32-bit PCM were silently skipped (the
            // guard in queueInput only allowed 16-bit/float), so on a
            // decoder that outputs 24/32-bit PCM the waves saw nothing
            // at all while the music played normally.
            C.ENCODING_PCM_24BIT -> 3 * channelCount
            C.ENCODING_PCM_32BIT -> 4 * channelCount
            else -> return
        }
        while (buffer.remaining() >= frameBytes && windowPos < FFT_SIZE) {
            var sum = 0f
            for (ch in 0 until channelCount) {
                sum += when (encoding) {
                    C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    C.ENCODING_PCM_24BIT -> read24Bit(buffer) / 8388608f
                    C.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                    else -> buffer.float
                }
            }
            window[windowPos++] = sum / channelCount
            if (windowPos == FFT_SIZE) {
                publishSpectrum()
                windowPos = 0
            }
        }
    }

    /** One little-endian 24-bit sample as a signed int. */
    private fun read24Bit(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        var value = b0 or (b1 shl 8) or (b2 shl 16)
        if (value and 0x800000 != 0) value -= 0x1000000
        return value
    }

    private fun publishSpectrum() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishMs < MIN_PUBLISH_INTERVAL_MS) return
        lastPublishMs = now

        // Hann window + FFT.
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (FFT_SIZE - 1)))
            real[i] = window[i] * w
        }
        fft(real, imag)

        val magnitudes = FloatArray(BAR_COUNT)
        val logMax = log2((FFT_SIZE / 2).toFloat())
        for (bar in 0 until BAR_COUNT) {
            val binStart = 2f.pow(bar * logMax / BAR_COUNT).toInt().coerceAtLeast(1)
            val binEnd = 2f.pow((bar + 1) * logMax / BAR_COUNT).toInt()
                .coerceAtLeast(binStart + 1)
            var peak = 0f
            for (bin in binStart until min(binEnd, FFT_SIZE / 2)) {
                val mag = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                if (mag > peak) peak = mag
            }
            // A full-scale sine peaks near FFT_SIZE/2; add gain and a sqrt
            // curve so quiet music still moves, then clamp to 0..1.
            var value = (peak / (FFT_SIZE / 2f)) * GAIN
            value = sqrt(value.coerceIn(0f, 1f))
            // Rise fast, fall slowly, so bars move smoothly instead of flickering.
            smoothed[bar] = maxOf(value, smoothed[bar] * DECAY)
            magnitudes[bar] = smoothed[bar]
        }
        VisualizerHub.publishProcessor(magnitudes)
    }

    /** Iterative radix-2 Cooley-Tukey FFT, in place. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until len / 2) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + len / 2] * curWr - imag[i + k + len / 2] * curWi
                    val vi = real[i + k + len / 2] * curWi + imag[i + k + len / 2] * curWr
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + len / 2] = ur - vr
                    imag[i + k + len / 2] = ui - vi
                    val nextWr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val FFT_SIZE = 1024
        private const val BAR_COUNT = 32
        private const val MIN_PUBLISH_INTERVAL_MS = 40L
        private const val GAIN = 3.0f
        private const val DECAY = 0.82f
    }
}
