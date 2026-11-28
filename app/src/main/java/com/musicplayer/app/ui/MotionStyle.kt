package com.musicplayer.app.ui

import android.content.Context
import android.provider.Settings
import com.musicplayer.app.R

/**
 * Navigation motion styles — 22 types + the luxurious default.
 *
 * The DEFAULT is the pair the coach picked as the best two, working
 * together: Shared element (the cover grows from the mini player to the
 * disc in the full player) + Morph (the tapped card grows into the new
 * screen), plus a staggered list entrance and a light slide between tabs.
 * Every other type is selectable from Settings and applies immediately to
 * all navigations, and is saved.
 *
 * When the system "reduce motion" / "remove animations" setting is on
 * (animator or transition scale set to 0), every style falls back to a
 * plain fade.
 */
enum class MotionStyle(val key: String, val labelRes: Int) {
    DEFAULT("default", R.string.motion_default),
    SLIDE("slide", R.string.motion_slide),
    FADE("fade", R.string.motion_fade),
    SHARED("shared", R.string.motion_shared),
    ZOOM("zoom", R.string.motion_zoom),
    MORPH("morph", R.string.motion_morph),
    FLIP("flip", R.string.motion_flip),
    CUBE("cube", R.string.motion_cube),
    BLUR("blur", R.string.motion_blur),
    STAGGER("stagger", R.string.motion_stagger),
    SPRING("spring", R.string.motion_spring),
    PARALLAX("parallax", R.string.motion_parallax),
    REVEAL("reveal", R.string.motion_reveal),
    SHEET("sheet", R.string.motion_sheet),
    AXIS("axis", R.string.motion_axis),
    EXPLODE("explode", R.string.motion_explode),
    KEN_BURNS("kenburns", R.string.motion_kenburns),
    PARTICLES("particles", R.string.motion_particles),
    RIPPLE("ripple", R.string.motion_ripple),
    SHIMMER("shimmer", R.string.motion_shimmer),
    DEPTH("depth", R.string.motion_depth),
    CAROUSEL("carousel", R.string.motion_carousel),
    PAGE_CURL("pagecurl", R.string.motion_pagecurl);

    companion object {
        private const val PREFS = "zeno_motion"
        private const val KEY_STYLE = "motion_style"

        fun current(context: Context): MotionStyle {
            return try {
                val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STYLE, DEFAULT.key) ?: DEFAULT.key
                entries.firstOrNull { it.key == key } ?: DEFAULT
            } catch (e: Exception) {
                DEFAULT
            }
        }

        fun set(context: Context, style: MotionStyle) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_STYLE, style.key).apply()
            } catch (e: Exception) {
            }
        }

        /** True when the system "remove animations" / reduce-motion is on. */
        fun isReduceMotion(context: Context): Boolean {
            return try {
                val resolver = context.contentResolver
                val animator = Settings.Global.getFloat(
                    resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
                val transition = Settings.Global.getFloat(
                    resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
                )
                animator == 0f || transition == 0f
            } catch (e: Exception) {
                false
            }
        }

        /** The style that actually applies (reduce-motion forces fade). */
        fun effective(context: Context): MotionStyle {
            return if (isReduceMotion(context)) FADE else current(context)
        }

        /** True for the two shared-element styles (and the default). */
        fun MotionStyle.usesSharedElement(): Boolean =
            this == DEFAULT || this == SHARED || this == MORPH
    }
}
