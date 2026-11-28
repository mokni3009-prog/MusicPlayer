package com.musicplayer.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Colours taken from a song's cover — the full player's gradient and glow
 * follow the current song's artwork. Without a cover the Zeno identity
 * (gold -> cyan) is used.
 *
 * No extra dependency: the bitmap is downscaled to 16x16, vivid pixels are
 * bucketed by hue, and the best bucket (saturated, mid-lightness, frequent)
 * gives the accent; the runner-up gives the secondary colour.
 */
object CoverColors {

    data class Palette(val accent: Int, val secondary: Int)

    /** Zeno identity fallback: gold -> cyan. */
    val DEFAULT = Palette(0xFFFFD76A.toInt(), 0xFF2DE2E6.toInt())

    fun extract(bitmap: Bitmap?): Palette {
        if (bitmap == null) return DEFAULT
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            val buckets = Array(12) { Bucket() }
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val pixel = small.getPixel(x, y)
                    if (Color.alpha(pixel) < 200) continue
                    val hsv = FloatArray(3)
                    Color.colorToHSV(pixel, hsv)
                    val h = hsv[0] // 0..360
                    val s = hsv[1] // 0..1
                    val v = hsv[2] // 0..1
                    // Skip near-black, near-white and grey pixels.
                    if (s < 0.25f || v < 0.12f || v > 0.95f) continue
                    val weight = s * (1f - abs(v - 0.55f))
                    val bucket = buckets[(h / 30f).toInt().coerceIn(0, 11)]
                    bucket.weight += weight
                    bucket.r += Color.red(pixel) * weight
                    bucket.g += Color.green(pixel) * weight
                    bucket.b += Color.blue(pixel) * weight
                }
            }
            val ranked = buckets.sortedByDescending { it.weight }
            val best = ranked.firstOrNull { it.weight > 0 } ?: return DEFAULT
            val accent = best.averageColor()
            val second = ranked.drop(1).firstOrNull { it.weight > 0 }
            val secondary = second?.averageColor() ?: darken(accent, 0.55f)
            Palette(accent, secondary)
        } catch (e: Exception) {
            DEFAULT
        }
    }

    private class Bucket {
        var weight = 0f
        var r = 0f
        var g = 0f
        var b = 0f

        fun averageColor(): Int {
            if (weight <= 0f) return Color.BLACK
            return Color.rgb(
                (r / weight).toInt().coerceIn(0, 255),
                (g / weight).toInt().coerceIn(0, 255),
                (b / weight).toInt().coerceIn(0, 255)
            )
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }
}
