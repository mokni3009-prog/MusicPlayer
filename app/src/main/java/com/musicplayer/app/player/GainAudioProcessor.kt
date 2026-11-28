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
 * (VolumeBoost.boostGain * VolumeBoost.replayGain). A tanh soft-clip limiter
 * keeps the output inside [-1, 1] so a 200% boost never produces hard
 * digital clipping/distortion.
 *
 * The audio passes through unchanged when both gains are 1.0.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

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
            output.put(inputBuffer)
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
                        val m = tanh((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        tanh(l * leftGain) to tanh(r * rightGain)
                    }
                    output.putShort((ol * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    output.putShort((orr * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                } else {
                    // Mono / multichannel: gain only, no balance/mono.
                    while (src.remaining() >= 2) {
                        val sample = src.short / 32768f
                        val boosted = tanh(sample * gain)
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
                        val m = tanh((l + r) * 0.5f * gain)
                        m to m
                    } else {
                        tanh(l * leftGain) to tanh(r * rightGain)
                    }
                    output.putFloat(ol)
                    output.putFloat(orr)
                } else {
                    while (src.remaining() >= 4) {
                        val sample = src.float
                        output.putFloat(tanh(sample * gain))
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
