package com.musicplayer.app.ui

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivitySplashBinding

/**
 * First loading screen: the Zeno Play logo with the three promises
 * ("Gratuit pour toujours · Sans publicité · Vie privée respectée —
 * pour toujours") — shown as soon as the user taps the app icon, before
 * the main screen opens. The three promises hold forever.
 *
 * The splash stays visible for 3 seconds so users can read the
 * three promises, with a gradient progress bar and a countdown showing
 * the remaining time.
 *
 * Logo animation (elegant, Zeno Play identity):
 * - a neon glow behind the logo pulses gold → cyan,
 * - the card's gold stroke breathes between gold and cyan,
 * - a light band sweeps across the logo every few seconds,
 * - the logo itself has a subtle size pulse (1.00 → 1.05).
 * The logo stays clear and readable at all times. The animation is purely
 * visual: it runs during the fixed 1400 ms splash and never delays the
 * move to the main screen.
 */
class SplashActivity : FullScreenActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val animators = mutableListOf<ValueAnimator>()
    private var splashStartMs = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val elapsed = android.os.SystemClock.uptimeMillis() - splashStartMs
            val clamped = elapsed.coerceIn(0L, SPLASH_MS)
            binding.splashProgress.progress = clamped.toInt()
            val remainingSec = ((SPLASH_MS - clamped + 999L) / 1000L).coerceAtLeast(0L)
            binding.splashCountdown.text = "$remainingSec s"
            if (clamped < SPLASH_MS) {
                handler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        splashStartMs = android.os.SystemClock.uptimeMillis()
        startLogoAnimation()
        handler.post(progressRunnable)
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val next = if (AppTheme.isTv(this)) {
                Intent(this, TvMainActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
            TransitionHelper.startActivity(this, next)
            finish()
        }, SPLASH_MS)
    }

    private fun startLogoAnimation() {
        val card = binding.logoCard
        val glow = binding.logoGlow
        val shine = binding.shineView
        val density = resources.displayMetrics.density
        val gold = ContextCompat.getColor(this, R.color.zeno_gold)
        val cyan = ContextCompat.getColor(this, R.color.zeno_cyan)

        // 1. Subtle size pulse (1.00 → 1.05 → 1.00), 1.6 s loop.
        val scaleX = ObjectAnimator.ofFloat(card, "scaleX", 1f, 1.05f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(card, "scaleY", 1f, 1.05f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        // 2. Neon glow behind the logo, pulsing gold → cyan in brightness.
        val glowPulse = ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 0.9f, 0.35f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        // 3. Card stroke breathing between gold and cyan.
        val strokePulse = ValueAnimator.ofObject(ArgbEvaluator(), gold, cyan).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                card.strokeColor = anim.animatedValue as Int
            }
        }
        // 4. Light band sweeping across the logo: the first half of each
        // 2.8 s cycle is the sweep (left → right), the second half is a
        // pause with the band parked off-screen.
        val sweepRangePx = 340f * density
        val shineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            // SHIMMER motion style: a fast shimmer sweep while loading.
            duration = if (MotionStyle.effective(this@SplashActivity) == MotionStyle.SHIMMER) {
                1200L
            } else {
                SHINE_MS
            }
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                if (f < 0.5f) {
                    val p = f * 2f
                    shine.translationX = -sweepRangePx / 2f + p * sweepRangePx
                    // Fade in, hold, fade out across the sweep.
                    shine.alpha = when {
                        p < 0.15f -> p / 0.15f
                        p > 0.85f -> (1f - p) / 0.15f
                        else -> 1f
                    } * 0.9f
                } else {
                    shine.translationX = sweepRangePx / 2f
                    shine.alpha = 0f
                }
            }
        }
        animators.addAll(listOf(scaleX, scaleY, glowPulse, strokePulse, shineAnimator))
        animators.forEach { it.start() }
    }

    private fun stopLogoAnimation() {
        animators.forEach { it.cancel() }
        animators.clear()
    }

    override fun onDestroy() {
        stopLogoAnimation()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MS = 3_000L
        private const val PROGRESS_TICK_MS = 100L
        private const val PULSE_MS = 1600L
        private const val SHINE_MS = 2800L
    }
}
