package com.musicplayer.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.musicplayer.app.R
import kotlin.math.hypot

/**
 * Draws live FFT bars from the real audio output. Updated by PlayerActivity
 * from [com.musicplayer.app.player.VisualizerHub]. Bars go flat when the
 * audio is paused (silence is published), and animate while playing.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fft: ByteArray? = null

    private val barPaint = Paint().apply {
        color = context.getColor(R.color.md_theme_primary)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateFft(data: ByteArray) {
        fft = data
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = fft ?: return
        val binCount = data.size / 2
        if (binCount == 0) return
        val bars = 32
        val barWidth = width.toFloat() / bars
        for (i in 0 until bars) {
            val bin = (i * binCount) / bars
            val re = data[2 * bin].toInt()
            val im = data[2 * bin + 1].toInt()
            val magnitude = hypot(re.toDouble(), im.toDouble())
            // FFT magnitudes are roughly 0..180; a small boost keeps quiet music moving.
            val scaled = (magnitude / 110.0).coerceIn(0.0, 1.0)
            val barHeight = (scaled * height).toFloat().coerceAtLeast(4f)
            val left = i * barWidth + 2f
            val right = (i + 1) * barWidth - 2f
            canvas.drawRect(left, height - barHeight, right, height.toFloat(), barPaint)
        }
    }
}
