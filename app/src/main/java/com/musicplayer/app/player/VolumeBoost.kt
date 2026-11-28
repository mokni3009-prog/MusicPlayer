package com.musicplayer.app.player

/**
 * Shared volume state (0..200%) for the playback chain.
 *
 * Since v1.0.8 the 0..100% part drives the DEVICE volume (AudioManager,
 * STREAM_MUSIC — see [SystemVolumeSync]); the player itself always runs
 * at full internal volume and the system volume does the attenuation.
 * - 100..200% is the BOOST: the device stays at max and
 *   [GainAudioProcessor] adds the extra gain (1.0..2.0) with a soft-clip
 *   limiter so the sound never distorts.
 * - ReplayGain (track/album normalisation) is a second gain factor applied
 *   in the same processor.
 *
 * The processor reads these volatile fields on every audio buffer, so a
 * volume change takes effect immediately without rebuilding the player.
 *
 * Volume curve (v1.0.6 fix, kept in v1.0.8 for the system-volume mapping):
 * the 0..100% range used to be LINEAR (percent / 100), which the ear hears
 * as almost flat — 50% is only -6 dB, so dragging the slider barely
 * changed the loudness and only 0% (silence) felt different. The volume
 * is now a SQUARE law (perceptual / audio taper): 50% is -12 dB, 80% is
 * -4 dB, so the slider feels graduated across its whole travel.
 */
object VolumeBoost {
    /** Extra gain for the 100..200% boost range (1.0 = no boost). */
    @Volatile
    var boostGain: Float = 1.0f

    /** ReplayGain factor for the current track (1.0 = no correction). */
    @Volatile
    var replayGain: Float = 1.0f

    /** Current UI volume, 0..200. */
    @Volatile
    @set:JvmName("setVolumePercentValue")
    var volumePercent: Int = 80

    /** Stereo balance: -1.0 (full left) .. 0 (centre) .. +1.0 (full right). */
    @Volatile
    var balance: Float = 0f

    /** Mono mode: both channels play the L+R mix. */
    @Volatile
    var mono: Boolean = false

    fun setVolumePercent(percent: Int) {
        volumePercent = percent.coerceIn(0, 200)
        boostGain = if (volumePercent > 100) volumePercent / 100f else 1.0f
    }

    /**
     * Internal ExoPlayer volume (0.0..1.0). Since v1.0.8 the 0..100% slider
     * range drives the DEVICE volume, so the player itself always runs at
     * full volume (1.0); the >100% boost still comes from
     * GainAudioProcessor. Sleep-timer fade is applied on top by the callers.
     */
    fun playerVolume(): Float = 1.0f

    /**
     * System-volume fraction (0.0..1.0) for the 0..100% part — square law,
     * perceptual (see the class KDoc). Used by [SystemVolumeSync] to map
     * the slider to AudioManager steps.
     */
    fun systemVolumeFraction(percent: Int): Float {
        val linear = (percent.coerceIn(0, 100)) / 100f
        return linear * linear
    }

    /**
     * VLC volume (0..200) for a slider value + fade factor. The 0..100%
     * part is now the DEVICE volume, so VLC stays at 100 (full); above
     * 100% VLC adds the boost itself, as before.
     */
    fun vlcVolumePercent(percent: Int, fadeFactor: Float): Int {
        val base = if (percent > 100) percent else 100
        return (base * fadeFactor).toInt().coerceIn(0, 200)
    }

    fun setReplayGainDb(db: Float) {
        // Clamp to a sane range (-12..+12 dB) and convert to a factor.
        val clamped = db.coerceIn(-12f, 12f)
        replayGain = Math.pow(10.0, (clamped / 20.0).toDouble()).toFloat()
    }
}
