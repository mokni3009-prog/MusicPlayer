package com.musicplayer.app.player

/**
 * Shared volume state (0..200%) for the playback chain.
 *
 * - 0..100% is applied as the ExoPlayer / VLC volume (0.0..1.0).
 * - 100..200% is the BOOST: the player stays at full volume and
 *   [GainAudioProcessor] adds the extra gain (1.0..2.0) with a soft-clip
 *   limiter so the sound never distorts.
 * - ReplayGain (track/album normalisation) is a second gain factor applied
 *   in the same processor.
 *
 * The processor reads these volatile fields on every audio buffer, so a
 * volume change takes effect immediately without rebuilding the player.
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
    var volumePercent: Int = 80

    fun setVolumePercent(percent: Int) {
        volumePercent = percent.coerceIn(0, 200)
        boostGain = if (volumePercent > 100) volumePercent / 100f else 1.0f
    }

    /** Player volume (0.0..1.0) for the 0..100% part. */
    fun playerVolume(): Float = (volumePercent.coerceAtMost(100)) / 100f

    fun setReplayGainDb(db: Float) {
        // Clamp to a sane range (-12..+12 dB) and convert to a factor.
        val clamped = db.coerceIn(-12f, 12f)
        replayGain = Math.pow(10.0, (clamped / 20.0).toDouble()).toFloat()
    }
}
