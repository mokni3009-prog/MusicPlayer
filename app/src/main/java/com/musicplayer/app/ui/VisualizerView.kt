package com.musicplayer.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.musicplayer.app.R
import kotlin.math.sin

/**
 * Draws live spectrum bars from the real audio output. Updated by
 * PlayerActivity from [com.musicplayer.app.player.VisualizerHub].
 *
 * The area is never empty: bars always keep a minimum height and a visible
 * baseline is drawn even on silence. While a song plays but no real data has
 * arrived recently (e.g. the audio path produced nothing yet), an animated
 * simulated wave is shown instead so the user always sees waves moving.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var magnitudes: FloatArray? = null
    private var lastUpdateMs = 0L
    private var isPlaying = false

    private var simPhase = 0f

    private val barPaint = Paint().apply {
        color = context.getColor(R.color.md_theme_primary)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val baselinePaint = Paint().apply {
        color = context.getColor(R.color.md_theme_primary)
        alpha = 90
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val density: Float get() = resources.displayMetrics.density
    private val minBarPx: Float get() = 6f * density
    private val baselinePx: Float get() = 2f * density

    fun updateMagnitudes(data: FloatArray) {
        magnitudes = data.copyOf()
        lastUpdateMs = SystemClock.uptimeMillis()
        postInvalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val bars = 32
        val data = magnitudes
        val fresh = data != null && now - lastUpdateMs < STALE_AFTER_MS
        val values: FloatArray = when {
            fresh -> data!!
            isPlaying -> simulatedMagnitudes()
            else -> FloatArray(bars) // paused / no data: flat minimum bars
        }
        val barWidth = width.toFloat() / bars
        for (i in 0 until bars) {
            val scaled = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val barHeight = minBarPx + scaled * (height - minBarPx - baselinePx)
            val left = i * barWidth + 2f
            val right = (i + 1) * barWidth - 2f
            val top = height - baselinePx - barHeight
            canvas.drawRoundRect(left, top, right, height - baselinePx, 3f, 3f, barPaint)
        }
        // Baseline so the visualizer area is always visible, even on silence.
        canvas.drawRect(0f, height - baselinePx, width.toFloat(), height.toFloat(), baselinePaint)
        // Keep the simulated wave animating while playing with no real data.
        if (isPlaying && !fresh) {
            postInvalidateDelayed(60)
        }
    }

    private fun simulatedMagnitudes(): FloatArray {
        simPhase += 0.35f
        val out = FloatArray(32)
        for (i in 0 until 32) {
            val wave = 0.30f + 0.22f * sin(simPhase + i * 0.65f) +
                0.12f * sin(simPhase * 1.7f + i * 0.23f)
            out[i] = wave.coerceIn(0.05f, 0.8f)
        }
        return out
    }

    companion object {
        private const val STALE_AFTER_MS = 600L
    }
}
