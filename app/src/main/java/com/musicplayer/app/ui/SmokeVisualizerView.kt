package com.musicplayer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.musicplayer.app.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular smoke visualizer — a flowing gold→cyan mist ring around the
 * album-cover disc in the full player (NOT bars).
 *
 * The ring is made of soft overlapping puffs, one per FFT band (32), whose
 * size, distance and brightness follow the REAL audio spectrum from
 * [com.musicplayer.app.player.SpectrumAudioProcessor] (via VisualizerHub).
 * A slow drift + a few large wisps make the smoke flow and breathe; the
 * whole ring gently pulsates with the overall energy.
 *
 * On VLC tracks (rare formats) there is no PCM, so the view falls back to
 * the same animated simulation as the old bar visualizer — the ring is
 * never empty while a song plays. When paused it fades to a faint static
 * glow.
 */
class SmokeVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var magnitudes: FloatArray? = null
    private var lastUpdateMs = 0L
    private var isPlaying = false
    private var simPhase = 0f
    private val startMs = SystemClock.uptimeMillis()
    /** Mean of the lowest FFT bands (bass) from the last drawn frame —
     * used by the player to pulse the cover disc with the beat. */
    private var lastBass = 0f

    /** Current bass energy (0..1): real FFT on Media3 tracks, simulated
     * on VLC tracks; 0 when paused. */
    fun bassEnergy(): Float = if (isPlaying) lastBass else 0f

    /** One soft white blob (radial gradient), tinted per puff at draw time. */
    private val blob: Bitmap by lazy { makeBlob() }
    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val rect = RectF()

    private val gold = context.getColor(R.color.zeno_gold)
    private val cyan = context.getColor(R.color.zeno_cyan)

    private fun makeBlob(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = true }
        p.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.WHITE, 0xB3FFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return bmp
    }

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
        val t = (now - startMs) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) / 2f
        if (half <= 0f) return

        val data = magnitudes
        val fresh = data != null && now - lastUpdateMs < STALE_AFTER_MS
        val values: FloatArray = when {
            fresh -> data!!
            isPlaying -> simulatedMagnitudes()
            else -> FloatArray(BANDS)
        }
        var mean = 0f
        for (v in values) mean += v
        mean /= BANDS
        // Bass energy (lowest bands) for the cover beat-pulse.
        var bass = 0f
        for (i in 0 until 4) bass += values.getOrElse(i) { 0f }
        lastBass = (bass / 4f).coerceIn(0f, 1f)

        // Soft halo behind the disc (very faint when paused).
        val haloR = half * 0.98f
        paint.colorFilter = PorterDuffColorFilter(
            lerpColor(gold, cyan, 0.5f + 0.5f * sin(t * 0.25f)),
            PorterDuff.Mode.MULTIPLY
        )
        paint.alpha = if (isPlaying) (12 + (mean * 26).toInt()) else 7
        rect.set(cx - haloR, cy - haloR, cx + haloR, cy + haloR)
        canvas.drawBitmap(blob, null, rect, paint)

        // Breathing ring: radius pulsates with the overall energy.
        val baseR = half * 0.84f
        val breath = 1f + 0.05f * mean + 0.02f * sin(t * 0.9f)
        val ringR = baseR * breath
        val spread = half * 0.06f
        val blobBase = (Math.PI.toFloat() * 2f * ringR / BANDS) * 1.0f

        // One smoke puff per FFT band, drifting slowly around the ring.
        for (i in 0 until BANDS) {
            val frac = i.toFloat() / BANDS
            val angle = frac * Math.PI.toFloat() * 2f +
                t * 0.05f + sin(t * 0.7f + i * 0.5f) * 0.05f
            val mag = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val r = ringR + mag * spread + sin(t * 1.3f + i * 0.9f) * half * 0.01f
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            val size = blobBase * (0.75f + mag * 0.9f)
            paint.colorFilter = PorterDuffColorFilter(
                lerpColor(gold, cyan, (frac + t * 0.02f) % 1f),
                PorterDuff.Mode.MULTIPLY
            )
            paint.alpha = if (isPlaying) (36 + (mag * 150).toInt()).coerceIn(0, 255) else 16
            rect.set(x - size, y - size, x + size, y + size)
            canvas.drawBitmap(blob, null, rect, paint)
        }

        // A few large slow wisps for the flowing-smoke feel.
        for (w in 0 until 4) {
            val wAngle = t * (0.05f + w * 0.012f) + w * (Math.PI.toFloat() * 2f / 4)
            val wMag = 0.35f + 0.25f * sin(t * 0.5f + w * 1.7f)
            val wr = ringR * (0.95f + 0.08f * sin(t * 0.4f + w))
            val wx = cx + cos(wAngle) * wr
            val wy = cy + sin(wAngle) * wr
            val wSize = blobBase * 2.1f
            paint.colorFilter = PorterDuffColorFilter(
                lerpColor(gold, cyan, (w / 4f + t * 0.01f) % 1f),
                PorterDuff.Mode.MULTIPLY
            )
            paint.alpha = if (isPlaying) (24 + (wMag * 40).toInt()).coerceIn(0, 255) else 10
            rect.set(wx - wSize, wy - wSize, wx + wSize, wy + wSize)
            canvas.drawBitmap(blob, null, rect, paint)
        }
        paint.colorFilter = null

        // Keep the smoke flowing while a song plays.
        if (isPlaying) postInvalidateDelayed(50)
    }

    private fun simulatedMagnitudes(): FloatArray {
        simPhase += 0.35f
        val out = FloatArray(BANDS)
        for (i in 0 until BANDS) {
            val wave = 0.30f + 0.22f * sin(simPhase + i * 0.65f) +
                0.12f * sin(simPhase * 1.7f + i * 0.23f)
            out[i] = wave.coerceIn(0.05f, 0.8f)
        }
        return out
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt()
        )
    }

    companion object {
        private const val BANDS = 32
        private const val STALE_AFTER_MS = 600L
    }
}
