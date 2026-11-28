package com.musicplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * Volume-boost + ReplayGain audio processor.
 *
 * Sits in the ExoPlayer audio chain (after [SpectrumAudioProcessor]) and
 * multiplies the PCM samples by the combined gain
 * (VolumeBoost.boostGain * VolumeBoost.replayGain).
 *
 * Limiter (v1.0.6 fix): the old limiter was a plain tanh() over the whole
 * signal, which squashed already-loud mastered tracks — at 200% the peaks
 * only gained ~+2 dB, so the boost felt fake. The limiter now has a KNEE:
 * everything below 0.9 passes through LINEARLY (a 200% boost is a real
 * +6 dB on the bulk of the signal), and only the excess above 0.9 is
 * rounded off with tanh, so the output never hard-clips.
 *
 * The audio passes through unchanged when both gains are 1.0.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    companion object {
        /** Knee of the soft limiter: linear below this, rounded above it. */
        private const val KNEE = 0.9f

        /** Linear below the knee, tanh-rounded above it; never hard-clips. */
        fun softLimit(x: Float): Float {
            val ax = kotlin.math.abs(x)
            if (ax <= KNEE) return x
            val excess = ax - KNEE
            return (if (x < 0f) -1f else 1f) *
                (KNEE + (1f - KNEE) * tanh(excess / (1f - KNEE)))
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gain = VolumeBoost.boostGain * VolumeBoost.replayGain
        val balance = VolumeBoost.balance
        val mono = VolumeBoost.mono
        // Per-channel gains for stereo: balance shifts the level to one
        // side (leftGain/rightGain), mono mixes L+R into both channels.
        val leftGain = gain * if (balance < 0f) 1f + balance else 1f
        val rightGain = gain * if (balance > 0f) 1f - balance else 1f
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if ((gain == 1.0f && balance == 0f && !mono) ||
            channelCount <= 0 ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT)
        ) {
            // No boost: pass through untouched.
            // (Same EMPTY_BUFFER trap as SpectrumAudioProcessor: skip the
            // put when there is nothing to copy, otherwise put()ing the
            // shared empty buffer into itself throws IllegalArgumentException
            // and kills playback with ERROR_CODE_FAILED_RUNTIME_CHECK.)
            if (inputBuffer.remaining() > 0) {
                output.put(inputBuffer)
            }
            output.flip()
            return
        }
        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val stereo = channelCount == 2
        if (encoding == C.ENCODING_PCM_16BIT) {
            while (src.remaining() >= 2 * channelCount) {
                if (stereo) {
                    val l = src.short / 32768f
                    val r = src.short / 32768f
                    val (ol, orr) = if (mono) {
                        val m = softLimit((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        softLimit(l * leftGain) to softLimit(r * rightGain)
                    }
                    output.putShort((ol * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    output.putShort((orr * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                } else {
                    // Mono / multichannel: gain only, no balance/mono.
                    while (src.remaining() >= 2) {
                        val sample = src.short / 32768f
                        val boosted = softLimit(sample * gain)
                        val out = (boosted * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        output.putShort(out)
                    }
                }
            }
        } else {
            while (src.remaining() >= 4 * channelCount) {
                if (stereo) {
                    val l = src.float
                    val r = src.float
                    val (ol, orr) = if (mono) {
                        val m = softLimit((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        softLimit(l * leftGain) to softLimit(r * rightGain)
                    }
                    output.putFloat(ol)
                    output.putFloat(orr)
                } else {
                    while (src.remaining() >= 4) {
                        val sample = src.float
                        output.putFloat(softLimit(sample * gain))
                    }
                }
            }
        }
        // Consume the input buffer.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {}
    override fun onReset() {}
}
