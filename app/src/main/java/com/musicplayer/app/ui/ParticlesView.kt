package com.musicplayer.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Golden particles drifting around the cover disc — the PARTICLES motion
 * style. Small gold dots rise slowly and fade, with a soft glow. Only
 * visible while the style is PARTICLES and the player is open.
 */
class ParticlesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Particle(
        var angle: Float,
        var radius: Float,
        var size: Float,
        var speed: Float,
        var phase: Float
    )

    private val paint = Paint().apply { isAntiAlias = true }
    private val gold = context.getColor(com.musicplayer.app.R.color.zeno_gold)
    private val cyan = context.getColor(com.musicplayer.app.R.color.zeno_cyan)
    private val particles = mutableListOf<Particle>()
    private var running = false
    private var time = 0f

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            time += 0.05f
            invalidate()
            postDelayed(this, 50)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        particles.clear()
        val random = Random(42)
        val cx = w / 2f
        val cy = h / 2f
        val base = (kotlin.math.min(w, h) / 2f) * 0.62f
        for (i in 0 until 26) {
            particles.add(
                Particle(
                    angle = random.nextFloat() * 360f,
                    radius = base * (0.95f + random.nextFloat() * 0.45f),
                    size = 2f + random.nextFloat() * 4f,
                    speed = 0.08f + random.nextFloat() * 0.22f,
                    phase = random.nextFloat() * 6.28f
                )
            )
        }
        // cx/cy are only used in onDraw; silence the unused warning by
        // keeping the computation local to the draw pass.
        @Suppress("UNUSED_VARIABLE")
        val center = cx to cy
    }

    fun setRunning(value: Boolean) {
        if (value == running) return
        running = value
        if (value) post(ticker) else removeCallbacks(ticker)
        visibility = if (value) View.VISIBLE else View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        val cx = width / 2f
        val cy = height / 2f
        for ((index, p) in particles.withIndex()) {
            val angle = Math.toRadians((p.angle + time * p.speed * 40f).toDouble())
            val r = p.radius + sin(time * 0.7f + p.phase) * 6f
            val x = (cx + kotlin.math.cos(angle) * r).toFloat()
            val y = (cy + kotlin.math.sin(angle) * r - time * p.speed * 12f % 30f).toFloat()
            val alpha = (0.35f + 0.65f * abs(sin(time * 0.9f + p.phase))).toFloat()
            paint.color = if (index % 3 == 0) cyan else gold
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, p.size, paint)
        }
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v
}
