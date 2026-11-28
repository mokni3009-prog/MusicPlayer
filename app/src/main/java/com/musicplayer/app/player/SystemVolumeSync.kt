package com.musicplayer.app.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Two-way sync between the in-app volume slider (0..200%) and the DEVICE
 * volume (AudioManager, STREAM_MUSIC) — v1.0.8.
 *
 * Slider -> device: the 0..100% part drives the system volume with the same
 * square-law (perceptual) curve the slider always used, so the feel is
 * unchanged; above 100% the device stays at max and the extra boost is
 * internal (GainAudioProcessor / VlcEngine), exactly as before.
 *
 * Device -> slider: a ContentObserver on the system settings watches
 * STREAM_MUSIC. Hardware volume keys, the notification and Android Auto all
 * change the device volume — the observer picks those changes up and the
 * slider follows. Loop guard: every volume we set ourselves is recorded in
 * [lastAppliedStep]; an observer event whose step equals it is our own echo
 * and is ignored, and [PlayerConnection.onSystemVolumeChanged] never writes
 * the system volume back.
 *
 * Owned by the Application (process lifetime): the observer is registered
 * once in [attach] and never unregistered.
 */
object SystemVolumeSync {

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var observer: ContentObserver? = null

    /** System step last written by us (or last seen) — the loop guard. */
    @Volatile
    private var lastAppliedStep: Int = -1

    /** Application context (for persisting the synced slider value). */
    fun context(): Context? = appContext

    fun attach(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        appContext = app
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        lastAppliedStep = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onSystemVolumeChanged()
            }
        }
        try {
            app.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, observer!!
            )
        } catch (e: Exception) {
            observer = null
        }
    }

    /** Max STREAM_MUSIC steps on this device (usually 15). */
    fun maxSteps(): Int {
        return try {
            audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        } catch (e: Exception) {
            15
        }
    }

    fun currentStep(): Int {
        return try {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Slider percent (0..100) -> system step, square-law curve. */
    fun sliderToStep(percent: Int): Int {
        val max = maxSteps()
        if (percent >= 100) return max
        if (percent <= 0) return 0
        return (VolumeBoost.systemVolumeFraction(percent) * max).roundToInt()
            .coerceIn(0, max)
    }

    /** System step -> slider percent (0..100), inverse square law. */
    fun stepToSlider(step: Int): Int {
        val max = maxSteps().coerceAtLeast(1)
        val fraction = (step.coerceIn(0, max)) / max.toFloat()
        return (sqrt(fraction) * 100f).roundToInt().coerceIn(0, 100)
    }

    /** Current device volume expressed as a slider percent (0..100). */
    fun currentSliderPercent(): Int = stepToSlider(currentStep())

    /**
     * Slider percent -> device volume. 0..100% sets the system volume,
     * 100..200% leaves the device at max (the boost is internal).
     * No-op when the device is already at the matching step.
     */
    fun applySliderPercent(percent: Int) {
        val am = audioManager ?: return
        val step = sliderToStep(percent)
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != step) {
                lastAppliedStep = step
                am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            } else {
                lastAppliedStep = step
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Initial slider value at app launch: the CURRENT device volume.
     * A saved boost above 100% survives only while the device is still at
     * max — if the user lowered the device volume meanwhile, the slider
     * shows the real (lower) level and the boost is gone.
     */
    fun initialPercent(savedPercent: Int): Int {
        val current = currentSliderPercent()
        return if (savedPercent > 100 && current >= 100) savedPercent else current
    }

    /** Observer callback (main thread): an external device-volume change. */
    private fun onSystemVolumeChanged() {
        val step = currentStep()
        if (step == lastAppliedStep) return // our own echo — ignore
        lastAppliedStep = step
        PlayerConnection.onSystemVolumeChanged(stepToSlider(step))
    }
}
