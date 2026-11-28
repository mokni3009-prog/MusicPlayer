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
 *
 * v1.0.16 (M13): 24-bit and 32-bit PCM (typical hi-res FLAC / WAV) used
 * to pass through untouched — the slider honestly showed 200% while the
 * sound stayed at 100%, and balance / Mono did nothing. Both formats
 * are now processed like 16-bit / float.
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

    /** One little-endian 24-bit sample as a signed int. */
    private fun read24(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        var value = b0 or (b1 shl 8) or (b2 shl 16)
        if (value and 0x800000 != 0) value -= 0x1000000
        return value
    }

    /** One float sample (-1..1) as 3 little-endian bytes. */
    private fun write24(out: ByteBuffer, v: Float) {
        val i = (v.coerceIn(-1f, 0.9999999f) * 8388608f).toInt()
            .coerceIn(-8388608, 8388607)
        out.put((i and 0xFF).toByte())
        out.put((i shr 8 and 0xFF).toByte())
        out.put((i shr 16 and 0xFF).toByte())
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
        // v1.0.16 (M13): 24/32-bit PCM no longer passes through untouched.
        if ((gain == 1.0f && balance == 0f && !mono) ||
            channelCount <= 0 ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT &&
                encoding != C.ENCODING_PCM_24BIT && encoding != C.ENCODING_PCM_32BIT)
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
        } else if (encoding == C.ENCODING_PCM_24BIT) {
            // v1.0.16 (M13): 24-bit little-endian samples.
            while (src.remaining() >= 3 * channelCount) {
                if (stereo) {
                    val l = read24(src) / 8388608f
                    val r = read24(src) / 8388608f
                    val (ol, orr) = if (mono) {
                        val m = softLimit((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        softLimit(l * leftGain) to softLimit(r * rightGain)
                    }
                    write24(output, ol)
                    write24(output, orr)
                } else {
                    while (src.remaining() >= 3) {
                        val sample = read24(src) / 8388608f
                        write24(output, softLimit(sample * gain))
                    }
                }
            }
        } else if (encoding == C.ENCODING_PCM_32BIT) {
            // v1.0.16 (M13): 32-bit integer little-endian samples.
            while (src.remaining() >= 4 * channelCount) {
                if (stereo) {
                    val l = src.int / 2147483648f
                    val r = src.int / 2147483648f
                    val (ol, orr) = if (mono) {
                        val m = softLimit((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        softLimit(l * leftGain) to softLimit(r * rightGain)
                    }
                    output.putInt((ol * 2147483647f).toInt().coerceIn(-2147483648, 2147483647))
                    output.putInt((orr * 2147483647f).toInt().coerceIn(-2147483648, 2147483647))
                } else {
                    while (src.remaining() >= 4) {
                        val sample = src.int / 2147483648f
                        val boosted = softLimit(sample * gain)
                        output.putInt((boosted * 2147483647f).toInt().coerceIn(-2147483648, 2147483647))
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
