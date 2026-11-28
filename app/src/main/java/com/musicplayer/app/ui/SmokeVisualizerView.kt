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

    /** Ring colours — Zeno gold -> cyan by default, or the current
     * song's cover colours (see CoverColors). */
    private var gold = context.getColor(R.color.zeno_gold)
    private var cyan = context.getColor(R.color.zeno_cyan)

    /** Tints the smoke ring with the given colours (cover colours). */
    fun setColors(start: Int, end: Int) {
        gold = start
        cyan = end
        invalidate()
    }

    /** The waveform shape (see WaveformStyle): 0 = circular smoke
     * (default) ... 19 = water ripples — 20 shapes in total. */
    var mode: Int = WaveformStyle.SMOKE
        private set

    fun setMode(newMode: Int) {
        mode = newMode.coerceIn(0, WaveformStyle.LABELS.size - 1)
        invalidate()
    }

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
        paint.colorFilter = null

        // The chosen waveform shape (Settings > "Forme des ondes").
        when (mode) {
            WaveformStyle.RADIAL_BARS -> drawRadialBars(canvas, values, t, cx, cy, half)
            WaveformStyle.STRAIGHT_BARS -> drawStraightBars(canvas, values, cx, cy, half)
            WaveformStyle.WAVE -> drawWave(canvas, values, t, cx, cy, half)
            WaveformStyle.PULSE_CIRCLE -> drawPulseCircle(canvas, mean, t, cx, cy, half)
            WaveformStyle.RINGS -> drawRings(canvas, values, mean, t, cx, cy, half)
            WaveformStyle.PARTICLES -> drawParticles(canvas, values, t, cx, cy, half)
            WaveformStyle.CIRCULAR_BAR -> drawCircularBar(canvas, mean, t, cx, cy, half)
            WaveformStyle.CIRCULAR_WAVE -> drawCircularWave(canvas, values, t, cx, cy, half)
            WaveformStyle.SUN_RAYS -> drawSunRays(canvas, values, t, cx, cy, half)
            WaveformStyle.SPIRAL -> drawSpiral(canvas, values, t, cx, cy, half)
            WaveformStyle.AURORA -> drawAurora(canvas, values, mean, t, cx, cy, half)
            WaveformStyle.FLOWER -> drawFlower(canvas, values, mean, t, cx, cy, half)
            WaveformStyle.FLAME -> drawFlame(canvas, values, t, cx, cy, half)
            WaveformStyle.CRYSTAL -> drawCrystal(canvas, values, mean, t, cx, cy, half)
            WaveformStyle.GALAXY -> drawGalaxy(canvas, values, t, cx, cy, half)
            WaveformStyle.RIBBONS -> drawRibbons(canvas, values, mean, t, cx, cy, half)
            WaveformStyle.BUBBLES -> drawBubbles(canvas, values, t, cx, cy, half)
            WaveformStyle.HEART -> drawHeart(canvas, mean, t, cx, cy, half)
            WaveformStyle.RIPPLES -> drawRipples(canvas, mean, t, cx, cy, half)
            else -> drawSmoke(canvas, values, mean, t, cx, cy, half)
        }

        // Keep the waves flowing while a song plays.
        if (isPlaying) postInvalidateDelayed(50)
    }

    /** Default shape: the circular smoke ring around the disc — real
     * drifting fog, not a few blurred lines: 48 puffs slowly orbiting
     * the ring at their own speeds, a denser inner mist, and 6 large
     * slow wisps, all breathing with the song's energy. */
    private fun drawSmoke(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        // Breathing ring: radius pulsates with the overall energy.
        val baseR = half * 0.84f
        val breath = 1f + 0.05f * mean + 0.02f * sin(t * 0.9f)
        val ringR = baseR * breath
        val spread = half * 0.06f
        val blobBase = (Math.PI.toFloat() * 2f * ringR / PUFFS) * 1.0f

        // 48 smoke puffs, each drifting around the ring at its own
        // angular speed — the fog visibly flows, never sits still.
        for (i in 0 until PUFFS) {
            val band = i * BANDS / PUFFS
            val frac = i.toFloat() / PUFFS
            val drift = 0.02f + 0.06f * ((i * 7 % 5) / 5f)
            val angle = frac * Math.PI.toFloat() * 2f +
                t * drift + sin(t * 0.7f + i * 0.5f) * 0.04f
            val mag = values.getOrElse(band) { 0f }.coerceIn(0f, 1f)
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

        // Denser inner mist — a second, smaller ring of faint puffs.
        for (i in 0 until PUFFS / 2) {
            val band = i * BANDS / (PUFFS / 2)
            val frac = i.toFloat() / (PUFFS / 2)
            val angle = frac * Math.PI.toFloat() * 2f - t * 0.04f +
                sin(t * 0.9f + i * 0.8f) * 0.05f
            val mag = values.getOrElse(band) { 0f }.coerceIn(0f, 1f)
            val r = ringR * 0.76f + mag * spread * 0.6f
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            val size = blobBase * 0.55f
            paint.colorFilter = PorterDuffColorFilter(
                lerpColor(gold, cyan, (frac + 0.5f + t * 0.01f) % 1f),
                PorterDuff.Mode.MULTIPLY
            )
            paint.alpha = if (isPlaying) (20 + (mag * 70).toInt()).coerceIn(0, 255) else 9
            rect.set(x - size, y - size, x + size, y + size)
            canvas.drawBitmap(blob, null, rect, paint)
        }

        // Six large slow wisps for the flowing-smoke feel.
        for (w in 0 until 6) {
            val wAngle = t * (0.05f + w * 0.012f) + w * (Math.PI.toFloat() * 2f / 6)
            val wMag = 0.35f + 0.25f * sin(t * 0.5f + w * 1.7f)
            val wr = ringR * (0.95f + 0.08f * sin(t * 0.4f + w))
            val wx = cx + cos(wAngle) * wr
            val wy = cy + sin(wAngle) * wr
            val wSize = blobBase * 2.1f
            paint.colorFilter = PorterDuffColorFilter(
                lerpColor(gold, cyan, (w / 6f + t * 0.01f) % 1f),
                PorterDuff.Mode.MULTIPLY
            )
            paint.alpha = if (isPlaying) (24 + (wMag * 40).toInt()).coerceIn(0, 255) else 10
            rect.set(wx - wSize, wy - wSize, wx + wSize, wy + wSize)
            canvas.drawBitmap(blob, null, rect, paint)
        }
        paint.colorFilter = null
    }

    /** Circular bars radiating around the disc, one per FFT band. */
    private fun drawRadialBars(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val barPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = half * 0.045f
        }
        val ringR = half * 0.84f
        for (i in 0 until BANDS) {
            val frac = i.toFloat() / BANDS
            val angle = frac * Math.PI.toFloat() * 2f - Math.PI.toFloat() / 2f
            val mag = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val len = half * 0.02f + mag * half * 0.16f
            val x0 = cx + cos(angle) * ringR
            val y0 = cy + sin(angle) * ringR
            val x1 = cx + cos(angle) * (ringR + len)
            val y1 = cy + sin(angle) * (ringR + len)
            barPaint.color = lerpColor(gold, cyan, frac)
            barPaint.alpha = if (isPlaying) (70 + (mag * 185).toInt()).coerceIn(0, 255) else 25
            canvas.drawLine(x0, y0, x1, y1, barPaint)
        }
    }

    /** Classic straight bars across the bottom of the view. */
    private fun drawStraightBars(
        canvas: Canvas, values: FloatArray, cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.012f
        val bw = (w - gap * (BANDS + 1)) / BANDS
        val maxH = h * 0.42f
        val barPaint = Paint().apply { isAntiAlias = true }
        for (i in 0 until BANDS) {
            val mag = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val bh = maxH * (0.06f + 0.94f * mag)
            val x = gap + i * (bw + gap)
            barPaint.color = lerpColor(gold, cyan, i.toFloat() / BANDS)
            barPaint.alpha = if (isPlaying) (90 + (mag * 165).toInt()).coerceIn(0, 255) else 30
            canvas.drawRoundRect(
                x, h - bh, x + bw, h, bw * 0.3f, bw * 0.3f, barPaint
            )
        }
    }

    /** A wavy line across the middle, following the spectrum. */
    private fun drawWave(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val wavePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = half * 0.03f
            color = cyan
            alpha = if (isPlaying) 220 else 60
        }
        val path = android.graphics.Path()
        val amp = half * 0.55f
        for (i in 0..BANDS) {
            val idx = i.coerceAtMost(BANDS - 1)
            val mag = values.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            val x = i.toFloat() / BANDS * w
            val y = cy + (mag - 0.45f) * amp +
                sin(t * 2f + i * 0.35f) * half * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, wavePaint)
        // Faint gold echo of the same wave.
        wavePaint.color = gold
        wavePaint.alpha = if (isPlaying) 110 else 30
        val echo = android.graphics.Path()
        for (i in 0..BANDS) {
            val idx = i.coerceAtMost(BANDS - 1)
            val mag = values.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            val x = i.toFloat() / BANDS * w
            val y = cy + half * 0.12f + (mag - 0.45f) * amp * 0.7f
            if (i == 0) echo.moveTo(x, y) else echo.lineTo(x, y)
        }
        canvas.drawPath(echo, wavePaint)
    }

    /** A circle around the disc that breathes with the overall energy. */
    private fun drawPulseCircle(
        canvas: Canvas, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val ringPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.035f
            color = lerpColor(gold, cyan, 0.5f + 0.5f * sin(t * 0.6f))
            alpha = if (isPlaying) (110 + (mean * 145).toInt()).coerceIn(0, 255) else 35
        }
        val r = half * 0.84f * (1f + 0.10f * mean + 0.02f * sin(t * 1.1f))
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.alpha = (ringPaint.alpha * 0.4f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r * 0.88f, ringPaint)
    }

    /** Three concentric rings driven by the low / mid / high bands. */
    private fun drawRings(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        fun bandMean(from: Int, to: Int): Float {
            var s = 0f
            for (i in from until to) s += values.getOrElse(i) { 0f }
            return (s / (to - from)).coerceIn(0f, 1f)
        }
        val ringPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        val radii = floatArrayOf(0.55f, 0.72f, 0.88f)
        val energies = floatArrayOf(bandMean(0, 6), bandMean(6, 18), bandMean(18, 32))
        val colors = intArrayOf(gold, cyan, Color.WHITE)
        for (i in radii.indices) {
            val e = energies[i]
            ringPaint.strokeWidth = half * (0.02f + 0.03f * e)
            ringPaint.color = colors[i]
            ringPaint.alpha = if (isPlaying) (60 + (e * 195).toInt()).coerceIn(0, 255) else 25
            canvas.drawCircle(
                cx, cy,
                half * radii[i] * (1f + 0.06f * e + 0.01f * sin(t + i)),
                ringPaint
            )
        }
    }

    /** Flying particles around the disc, each driven by one FFT band. */
    private fun drawParticles(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val dotPaint = Paint().apply { isAntiAlias = true }
        val ringR = half * 0.84f
        for (i in 0 until BANDS) {
            val frac = i.toFloat() / BANDS
            val mag = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val angle = frac * Math.PI.toFloat() * 2f + t * (0.15f + frac * 0.25f)
            val r = ringR * (0.72f + 0.45f * mag) +
                sin(t * 1.5f + i * 1.1f) * half * 0.04f
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            dotPaint.color = lerpColor(gold, cyan, frac)
            dotPaint.alpha = if (isPlaying) (80 + (mag * 175).toInt()).coerceIn(0, 255) else 30
            canvas.drawCircle(x, y, half * (0.012f + 0.03f * mag), dotPaint)
        }
    }

    /** A wave that runs around the circle: the ring radius follows the bands. */
    private fun drawCircularWave(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val ringR = half * 0.84f
        val spread = half * 0.10f
        val points = 64
        val path = android.graphics.Path()
        for (i in 0..points) {
            val idx = (i % points) * BANDS / points
            val mag = values.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            val angle = i.toFloat() / points * Math.PI.toFloat() * 2f - Math.PI.toFloat() / 2f
            val r = ringR + (mag - 0.35f) * spread + sin(t * 1.2f + i * 0.4f) * half * 0.01f
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val wavePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.025f
            color = cyan
            alpha = if (isPlaying) 220 else 60
        }
        canvas.drawPath(path, wavePaint)
        // Faint gold glow inside the wavy ring (visible around the disc).
        wavePaint.style = Paint.Style.FILL
        wavePaint.color = gold
        wavePaint.alpha = if (isPlaying) 28 else 10
        canvas.drawPath(path, wavePaint)
    }

    /** Sun rays radiating from the disc, length by band energy. */
    private fun drawSunRays(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val rays = 16
        val ringR = half * 0.84f
        val rayPaint = Paint().apply { isAntiAlias = true }
        val rot = t * 0.08f
        for (i in 0 until rays) {
            val band = i * BANDS / rays
            val mag = values.getOrElse(band) { 0f }.coerceIn(0f, 1f)
            val angle = i.toFloat() / rays * Math.PI.toFloat() * 2f + rot - Math.PI.toFloat() / 2f
            val len = half * 0.05f + mag * half * 0.22f
            val halfW = half * 0.018f * (0.6f + 0.4f * mag)
            val dx = cos(angle)
            val dy = sin(angle)
            val px = -dy
            val py = dx
            val x0 = cx + dx * ringR
            val y0 = cy + dy * ringR
            val x1 = cx + dx * (ringR + len) + px * halfW
            val y1 = cy + dy * (ringR + len) + py * halfW
            val x2 = cx + dx * (ringR + len) - px * halfW
            val y2 = cy + dy * (ringR + len) - py * halfW
            val path = android.graphics.Path().apply {
                moveTo(x0, y0)
                lineTo(x1, y1)
                lineTo(x2, y2)
                close()
            }
            rayPaint.color = if (i % 2 == 0) gold else cyan
            rayPaint.alpha = if (isPlaying) (70 + (mag * 160).toInt()).coerceIn(0, 255) else 25
            canvas.drawPath(path, rayPaint)
        }
    }
    /** A golden spiral around the disc: two arms of glowing dots,
     * radius growing with the band energies, slowly rotating. */
    private fun drawSpiral(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val dotPaint = Paint().apply { isAntiAlias = true }
        val dots = 60
        for (i in 0 until dots) {
            val arm = i % 2
            val theta = (i / dots.toFloat()) * Math.PI.toFloat() * 3f
            val angle = theta + t * 0.12f + arm * Math.PI.toFloat()
            val r = half * 0.10f + (theta / (Math.PI.toFloat() * 3f)) * half * 0.72f
            val mag = values.getOrElse(i * BANDS / dots) { 0f }.coerceIn(0f, 1f)
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            dotPaint.color = if (arm == 0) gold else cyan
            dotPaint.alpha = if (isPlaying) (70 + (mag * 185).toInt()).coerceIn(0, 255) else 28
            canvas.drawCircle(x, y, half * (0.012f + 0.028f * mag), dotPaint)
        }
    }

    /** Aurora borealis: three flowing translucent light bands across
     * the view — cyan, green and gold — rippling with the music. */
    private fun drawAurora(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val auroraGreen = 0xFF7CF2A0.toInt()
        val colors = intArrayOf(cyan, auroraGreen, gold)
        val alphas = intArrayOf(90, 70, 60)
        for (k in 0 until 3) {
            val bandMag = values.getOrElse(k * 9) { 0f }.coerceIn(0f, 1f)
            val path = android.graphics.Path()
            val steps = 40
            for (i in 0..steps) {
                val x = i.toFloat() / steps * w
                val y = cy + (k - 1) * half * 0.30f +
                    sin(x / w * Math.PI.toFloat() * 3f + t * 0.8f + k * 2.1f) *
                        half * 0.14f * (0.4f + mean) +
                    (bandMag - 0.4f) * half * 0.22f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val p = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = half * 0.10f
                color = colors[k]
                alpha = if (isPlaying) alphas[k] else 22
            }
            canvas.drawPath(path, p)
        }
    }

    /** A blooming flower: eight petals radiating from the disc, each
     * petal growing with its FFT band — the flower opens with the music. */
    private fun drawFlower(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val petals = 8
        val ringR = half * 0.80f
        val petalPaint = Paint().apply { isAntiAlias = true }
        for (i in 0 until petals) {
            val mag = values.getOrElse(i * BANDS / petals) { 0f }.coerceIn(0f, 1f)
            val len = ringR * (0.30f + 0.55f * mag) * (0.9f + 0.1f * mean)
            val wid = half * 0.09f * (0.7f + 0.3f * mag)
            val angleDeg = i * (360f / petals) + t * 3f
            canvas.save()
            canvas.rotate(angleDeg, cx, cy)
            rect.set(cx - wid, cy - ringR - len, cx + wid, cy - ringR + len * 0.2f)
            petalPaint.color = if (i % 2 == 0) gold else cyan
            petalPaint.alpha = if (isPlaying) (90 + (mag * 150).toInt()).coerceIn(0, 255) else 30
            canvas.drawOval(rect, petalPaint)
            canvas.restore()
        }
        // Golden heart of the flower, pulsing with the overall energy.
        petalPaint.color = gold
        petalPaint.alpha = if (isPlaying) (140 + (mean * 115).toInt()).coerceIn(0, 255) else 40
        canvas.drawCircle(cx, cy, half * (0.06f + 0.05f * mean), petalPaint)
    }

    /** Elegant flames licking up from the bottom of the view, height
     * by band energy — gold tongues with a cyan inner flame. */
    private fun drawFlame(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val flames = 12
        val flamePaint = Paint().apply { isAntiAlias = true }
        for (i in 0 until flames) {
            val mag = values.getOrElse(i * BANDS / flames) { 0f }.coerceIn(0f, 1f)
            val fx = w * (i + 0.5f) / flames + sin(t * 1.4f + i * 1.3f) * half * 0.015f
            val flameH = h * (0.05f + 0.17f * mag)
            val fw = w / flames * 0.42f * (0.6f + 0.4f * mag)
            val path = android.graphics.Path().apply {
                moveTo(fx - fw, h)
                quadTo(fx - fw * 1.8f, h - flameH * 0.55f, fx, h - flameH)
                quadTo(fx + fw * 1.8f, h - flameH * 0.55f, fx + fw, h)
                close()
            }
            flamePaint.color = gold
            flamePaint.alpha = if (isPlaying) (90 + (mag * 140).toInt()).coerceIn(0, 255) else 30
            canvas.drawPath(path, flamePaint)
            // Inner cyan flame.
            val inner = android.graphics.Path().apply {
                moveTo(fx - fw * 0.45f, h)
                quadTo(fx - fw * 0.8f, h - flameH * 0.3f, fx, h - flameH * 0.55f)
                quadTo(fx + fw * 0.8f, h - flameH * 0.3f, fx + fw * 0.45f, h)
                close()
            }
            flamePaint.color = cyan
            flamePaint.alpha = if (isPlaying) (70 + (mag * 110).toInt()).coerceIn(0, 255) else 22
            canvas.drawPath(inner, flamePaint)
        }
    }

    /** A glowing faceted crystal (diamond) behind the disc, its facets
     * lighting up with the low / mid / high bands. */
    private fun drawCrystal(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val s = half * 0.55f * (1f + 0.06f * mean)
        // Soft glow behind the crystal.
        paint.colorFilter = PorterDuffColorFilter(cyan, PorterDuff.Mode.MULTIPLY)
        paint.alpha = if (isPlaying) (30 + (mean * 60).toInt()).coerceIn(0, 255) else 12
        rect.set(cx - s * 1.3f, cy - s * 1.3f, cx + s * 1.3f, cy + s * 1.3f)
        canvas.drawBitmap(blob, null, rect, paint)
        paint.colorFilter = null
        val diamond = android.graphics.Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s * 0.7f, cy)
            lineTo(cx, cy + s)
            lineTo(cx - s * 0.7f, cy)
            close()
        }
        val fillPaint = Paint().apply {
            isAntiAlias = true
            color = gold
            alpha = if (isPlaying) (14 + (mean * 34).toInt()).coerceIn(0, 255) else 8
        }
        canvas.drawPath(diamond, fillPaint)
        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.03f
            color = cyan
            alpha = if (isPlaying) 220 else 60
        }
        canvas.drawPath(diamond, strokePaint)
        // Facet lines from the centre to each vertex, lit by the bands.
        val vertices = arrayOf(
            floatArrayOf(cx, cy - s),
            floatArrayOf(cx + s * 0.7f, cy),
            floatArrayOf(cx, cy + s),
            floatArrayOf(cx - s * 0.7f, cy)
        )
        val facetPaint = Paint().apply {
            isAntiAlias = true
            strokeWidth = half * 0.012f
            color = gold
        }
        for (i in vertices.indices) {
            val e = values.getOrElse(i * 8) { 0f }.coerceIn(0f, 1f)
            facetPaint.alpha = if (isPlaying) (60 + (e * 180).toInt()).coerceIn(0, 255) else 25
            canvas.drawLine(cx, cy, vertices[i][0], vertices[i][1], facetPaint)
        }
        // Horizontal facet through the middle.
        facetPaint.alpha = if (isPlaying) (60 + (values.getOrElse(12) { 0f } * 180).toInt()).coerceIn(0, 255) else 25
        canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, facetPaint)
    }

    /** A rotating spiral galaxy: two arms of stars orbiting the disc,
     * star size and brightness by band energy, gold / cyan / white. */
    private fun drawGalaxy(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        // Bright core.
        paint.colorFilter = PorterDuffColorFilter(gold, PorterDuff.Mode.MULTIPLY)
        paint.alpha = if (isPlaying) 70 else 25
        rect.set(cx - half * 0.3f, cy - half * 0.3f, cx + half * 0.3f, cy + half * 0.3f)
        canvas.drawBitmap(blob, null, rect, paint)
        paint.colorFilter = null
        val starPaint = Paint().apply { isAntiAlias = true }
        val stars = 90
        for (i in 0 until stars) {
            val arm = i % 2
            val theta = (i / stars.toFloat()) * Math.PI.toFloat() * 4f
            val angle = theta + t * 0.10f + arm * Math.PI.toFloat()
            val r = half * 0.08f + (i / stars.toFloat()) * half * 0.75f
            val mag = values.getOrElse(i * BANDS / stars) { 0f }.coerceIn(0f, 1f)
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            starPaint.color = when (i % 3) {
                0 -> Color.WHITE
                1 -> gold
                else -> cyan
            }
            starPaint.alpha = if (isPlaying) (80 + (mag * 175).toInt()).coerceIn(0, 255) else 28
            canvas.drawCircle(x, y, half * (0.008f + 0.022f * mag), starPaint)
        }
    }

    /** Silk ribbons: three flowing translucent curves drifting across
     * the view, amplitude modulated by the overall energy. */
    private fun drawRibbons(
        canvas: Canvas, values: FloatArray, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val colors = intArrayOf(gold, cyan, Color.WHITE)
        val alphas = intArrayOf(120, 110, 70)
        for (k in 0 until 3) {
            val bandMag = values.getOrElse(k * 10) { 0f }.coerceIn(0f, 1f)
            val path = android.graphics.Path()
            val steps = 40
            for (i in 0..steps) {
                val x = i.toFloat() / steps * w
                val y = cy + (k - 1) * half * 0.32f +
                    sin(x / w * Math.PI.toFloat() * 4f + t * 1.1f + k * 2f) *
                        half * 0.15f * (0.4f + mean) +
                    (bandMag - 0.4f) * half * 0.18f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val p = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = half * 0.045f
                color = colors[k]
                alpha = if (isPlaying) alphas[k] else 22
            }
            canvas.drawPath(path, p)
        }
    }

    /** Golden bubbles rising from the bottom, size and speed by band
     * energy, fading as they reach the top. */
    private fun drawBubbles(
        canvas: Canvas, values: FloatArray, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bubbles = 24
        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.012f
            color = gold
        }
        val hiPaint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
        for (i in 0 until bubbles) {
            val mag = values.getOrElse(i * BANDS / bubbles) { 0f }.coerceIn(0f, 1f)
            val speed = 0.05f + (i % 5) * 0.012f
            val phase = (t * speed + i * 0.13f) % 1f
            val y = h - phase * h
            val x = w * ((i * 37 % 100) / 100f) + sin(t * 0.8f + i) * half * 0.03f
            val r = half * (0.015f + 0.045f * mag)
            strokePaint.alpha = if (isPlaying)
                ((60 + mag * 150) * (1f - phase * 0.5f)).toInt().coerceIn(0, 255) else 25
            canvas.drawCircle(x, y, r, strokePaint)
            hiPaint.alpha = if (isPlaying) (120 * (1f - phase)).toInt().coerceIn(0, 255) else 20
            canvas.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.25f, hiPaint)
        }
    }

    /** A pulsing golden heart around the disc, beating with the music —
     * scale and glow follow the overall energy. */
    private fun drawHeart(
        canvas: Canvas, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val s = half * 0.62f * (1f + 0.10f * mean + 0.03f * sin(t * 2.2f))
        // Soft cyan glow behind the heart.
        paint.colorFilter = PorterDuffColorFilter(cyan, PorterDuff.Mode.MULTIPLY)
        paint.alpha = if (isPlaying) (30 + (mean * 60).toInt()).coerceIn(0, 255) else 12
        rect.set(cx - s * 1.4f, cy - s * 1.4f, cx + s * 1.4f, cy + s * 1.4f)
        canvas.drawBitmap(blob, null, rect, paint)
        paint.colorFilter = null
        val heart = android.graphics.Path().apply {
            moveTo(cx, cy + s * 0.38f)
            cubicTo(cx - s * 0.95f, cy - s * 0.15f, cx - s * 0.48f, cy - s * 0.85f, cx, cy - s * 0.32f)
            cubicTo(cx + s * 0.48f, cy - s * 0.85f, cx + s * 0.95f, cy - s * 0.15f, cx, cy + s * 0.38f)
            close()
        }
        val fillPaint = Paint().apply {
            isAntiAlias = true
            color = gold
            alpha = if (isPlaying) (60 + (mean * 160).toInt()).coerceIn(0, 255) else 28
        }
        canvas.drawPath(heart, fillPaint)
        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.03f
            color = cyan
            alpha = if (isPlaying) 200 else 55
        }
        canvas.drawPath(heart, strokePaint)
    }

    /** Water ripples: expanding concentric rings from the centre, like
     * drops falling on still water — a new ring starts with each beat. */
    private fun drawRipples(
        canvas: Canvas, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val ringPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = half * 0.02f
        }
        for (i in 0 until 5) {
            val phase = (t * 0.35f + i * 0.2f) % 1f
            val r = phase * half * 0.95f
            ringPaint.color = lerpColor(gold, cyan, phase)
            ringPaint.alpha = if (isPlaying)
                ((1f - phase) * (120f + mean * 100f)).toInt().coerceIn(0, 255) else 20
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
        // The drop at the centre, pulsing with the energy.
        val dotPaint = Paint().apply { isAntiAlias = true; color = gold }
        dotPaint.alpha = if (isPlaying) (140 + (mean * 115).toInt()).coerceIn(0, 255) else 40
        canvas.drawCircle(cx, cy, half * (0.05f + 0.10f * mean), dotPaint)
    }

    private fun drawCircularBar(
        canvas: Canvas, mean: Float, t: Float,
        cx: Float, cy: Float, half: Float
    ) {
        val ringR = half * 0.84f
        val barPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = half * 0.06f
        }
        rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        barPaint.color = Color.WHITE
        barPaint.alpha = 22
        canvas.drawArc(rect, 0f, 360f, false, barPaint)
        val sweep = (mean.coerceIn(0f, 1f) * 360f).coerceAtLeast(4f)
        barPaint.color = lerpColor(gold, cyan, mean)
        barPaint.alpha = if (isPlaying) 235 else 60
        canvas.drawArc(rect, -90f, sweep, false, barPaint)
        // Dot at the arc tip.
        val tipAngle = Math.toRadians((-90 + sweep).toDouble()).toFloat()
        val tx = cx + cos(tipAngle) * ringR
        val ty = cy + sin(tipAngle) * ringR
        barPaint.style = Paint.Style.FILL
        canvas.drawCircle(tx, ty, half * 0.045f, barPaint)
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
        private const val PUFFS = 48
        private const val STALE_AFTER_MS = 600L
    }
}
