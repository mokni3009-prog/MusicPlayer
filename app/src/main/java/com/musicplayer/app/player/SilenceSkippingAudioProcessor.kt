package com.musicplayer.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v1.0.16 — skip silence (feature 2): drops (near-)silent PCM buffers
 * from the output stream, so long silent intros, gaps in live
 * recordings and trailing silence are skipped instead of played.
 *
 * Runs AFTER the gain processor, so the silence test sees the boosted
 * signal (what the user actually hears).
 *
 * Safety caps (a too-aggressive setting must never stall a track):
 * - leading silence is dropped for at most 10 s;
 * - a silent stretch in the middle is dropped for at most 30 s, then
 *   buffers pass through until audible audio returns.
 * When the feature is off the processor is a pure pass-through.
 *
 * Position/seek stay consistent: ExoPlayer derives its position from
 * the rendered output, so dropping input only shortens the audible
 * part of the track.
 */
object SilenceSkip {

    private const val PREFS = "zeno_silence_skip"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENSITIVITY = "sensitivity"

    /** Live switch read by the processor on every buffer. */
    @Volatile
    var enabled: Boolean = false

    /** Amplitude below which a buffer counts as silent (0..1). */
    @Volatile
    var threshold: Float = 0.002f

    /** Sensitivity index 0 = low, 1 = medium, 2 = high. */
    fun sensitivity(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SENSITIVITY, 1)
    } catch (e: Exception) {
        1
    }

    fun setSensitivity(context: Context, value: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 2)).apply()
        } catch (e: Exception) {
        }
        applyThreshold(context)
    }

    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    } catch (e: Exception) {
        false
    }

    fun setEnabled(context: Context, value: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply()
        } catch (e: Exception) {
        }
        enabled = value
    }

    /** Reads the stored settings into the live fields (call at startup
     * and whenever the settings change). */
    fun applyAll(context: Context) {
        enabled = isEnabled(context)
        applyThreshold(context)
    }

    private fun applyThreshold(context: Context) {
        threshold = when (sensitivity(context)) {
            0 -> 0.0005f // low: only (near-)digital silence
            2 -> 0.008f // high: anything quieter than -42 dB
            else -> 0.002f // medium
        }
    }
}

class SilenceSkippingAudioProcessor : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    /** Frames dropped in the current silent stretch. */
    private var droppedFrames = 0L

    /** True once audible audio was output for the current track. */
    private var heardAudio = false

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun onFlush() {
        droppedFrames = 0L
        heardAudio = false
    }

    override fun onReset() {
        droppedFrames = 0L
        heardAudio = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!SilenceSkip.enabled || channelCount <= 0 ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT &&
                encoding != C.ENCODING_PCM_24BIT && encoding != C.ENCODING_PCM_32BIT)
        ) {
            // Pass through untouched (same EMPTY_BUFFER trap as the other
            // processors: never put() the shared empty buffer into itself).
            if (inputBuffer.remaining() > 0) {
                val output = replaceOutputBuffer(inputBuffer.remaining())
                output.put(inputBuffer)
                output.flip()
            }
            return
        }
        val frames = inputBuffer.remaining() / bytesPerFrame()
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val peak = peakAmplitude(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        if (peak >= SilenceSkip.threshold) {
            heardAudio = true
            droppedFrames = 0L
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }
        // Silent buffer: drop it, unless the safety cap is reached.
        val capFrames = if (heardAudio) 30L * sampleRate else 10L * sampleRate
        if (droppedFrames + frames <= capFrames) {
            droppedFrames += frames
            inputBuffer.position(inputBuffer.limit())
        } else {
            heardAudio = true
            droppedFrames = 0L
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
        }
    }

    private fun bytesPerFrame(): Int {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> 4 // float
        }
        return bytesPerSample * channelCount.coerceAtLeast(1)
    }

    /** Peak absolute amplitude (0..1) of the buffer, without consuming it. */
    private fun peakAmplitude(src: ByteBuffer): Float {
        var peak = 0f
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (src.remaining() >= 2) {
                    val v = kotlin.math.abs(src.short / 32768f)
                    if (v > peak) peak = v
                }
            }
            C.ENCODING_PCM_24BIT -> {
                while (src.remaining() >= 3) {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt() and 0xFF
                    var value = b0 or (b1 shl 8) or (b2 shl 16)
                    if (value and 0x800000 != 0) value -= 0x1000000
                    val v = kotlin.math.abs(value / 8388608f)
                    if (v > peak) peak = v
                }
            }
            C.ENCODING_PCM_32BIT -> {
                while (src.remaining() >= 4) {
                    val v = kotlin.math.abs(src.int / 2147483648f)
                    if (v > peak) peak = v
                }
            }
            else -> { // float
                while (src.remaining() >= 4) {
                    val v = kotlin.math.abs(src.float)
                    if (v > peak) peak = v
                }
            }
        }
        return peak
    }
}
