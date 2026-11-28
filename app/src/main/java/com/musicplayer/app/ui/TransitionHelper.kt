package com.musicplayer.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.musicplayer.app.R
import com.musicplayer.app.ui.MotionStyle.Companion.usesSharedElement
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.view.ViewGroup

/**
 * Applies the chosen navigation motion style to every activity launch
 * and return. Shared-element launches (DEFAULT / SHARED / MORPH) use a
 * MaterialContainerTransform + ChangeBounds/ChangeTransform set; the
 * other styles use overridePendingTransition with the anim resources;
 * a few styles (circular reveal, blur, depth, parallax, stagger,
 * Ken Burns, particles, shimmer) also run an in-activity effect via
 * [applyEnterEffect], hooked from FullScreenActivity.onPostCreate.
 */
object TransitionHelper {

    const val EXTRA_SHARED_USED = "zeno_shared_used"
    const val EXTRA_REVEAL_X = "zeno_reveal_x"
    const val EXTRA_REVEAL_Y = "zeno_reveal_y"

    /**
     * MaterialContainerTransform that can never crash the app.
     *
     * Root cause of the v1.0.5 full-player crash
     * (IllegalArgumentException: android:id/content is not a valid ancestor,
     * in MaterialContainerTransform.createAnimator): the platform transform
     * resolves its drawing view with TransitionUtils.findAncestorById(…,
     * android.R.id.content), which throws when the view handed to it does
     * not sit under android:id/content — e.g. a snapshot/ghost view living
     * in the window overlay instead of the activity hierarchy. The real
     * fix is the correct wiring (the shared view is the container that
     * carries the transitionName, and both activities set the
     * MaterialContainerTransformSharedElementCallback); this subclass is
     * the last-resort guard: if the framework ever hands the transform
     * an unusable view again, this child contributes no animator instead
     * of killing the app — the ChangeBounds / ChangeTransform /
     * ChangeImageTransform children still fly the cover, so the user
     * still sees a real shared-element motion, never a crash.
     */
    class SafeContainerTransform : MaterialContainerTransform() {
        override fun createAnimator(
            sceneRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): android.animation.Animator? {
            return try {
                super.createAnimator(sceneRoot, startValues, endValues)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * True when [view] sits under android:id/content in the view tree —
     * the precondition MaterialContainerTransform needs to resolve its
     * drawing view. A shared element that fails this check must never
     * be launched as a scene transition (fall back to a plain motion).
     */
    fun hasContentAncestor(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent.id == android.R.id.content) return true
            parent = parent.parent
        }
        return false
    }

    /** Launches [intent] from [activity] with the current motion style. */
    fun startActivity(
        activity: Activity,
        intent: Intent,
        sharedView: View? = null,
        sharedName: String? = null,
        revealAnchor: View? = null
    ) {
        val style = MotionStyle.effective(activity)
        if (style.usesSharedElement() && sharedView != null && sharedName != null) {
            if (hasContentAncestor(sharedView)) {
                intent.putExtra(EXTRA_SHARED_USED, true)
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    activity, android.util.Pair(sharedView, sharedName)
                )
                activity.startActivity(intent, options.toBundle())
                return
            }
            // Last resort only: the shared view cannot be morphed safely,
            // so run the same launch as a fade instead of crashing.
            activity.startActivity(intent)
            activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            return
        }
        if (style == MotionStyle.REVEAL) {
            val loc = IntArray(2)
            if (revealAnchor != null) {
                revealAnchor.getLocationOnScreen(loc)
                intent.putExtra(EXTRA_REVEAL_X, loc[0] + revealAnchor.width / 2)
                intent.putExtra(EXTRA_REVEAL_Y, loc[1] + revealAnchor.height / 2)
            }
            activity.startActivity(intent)
            return
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(enterAnim(style), exitAnim(style))
    }

    /** Applies the return motion when an activity finishes. Call from finish(). */
    fun applyExit(activity: Activity) {
        if (activity.intent.getBooleanExtra(EXTRA_SHARED_USED, false)) {
            // The framework runs the shared-element return transition.
            return
        }
        val style = MotionStyle.effective(activity)
        val (enter, exit) = backAnims(style)
        activity.overridePendingTransition(enter, exit)
    }

    /** In-activity effects, run once after setContentView (via onPostCreate). */
    fun applyEnterEffect(activity: Activity) {
        val style = MotionStyle.effective(activity)
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        when (style) {
            MotionStyle.REVEAL -> {
                val x = activity.intent.getIntExtra(EXTRA_REVEAL_X, -1)
                val y = activity.intent.getIntExtra(EXTRA_REVEAL_Y, -1)
                content.post {
                    val cx = if (x >= 0) x else content.width / 2
                    val cy = if (y >= 0) y else content.height / 2
                    val radius = kotlin.math.hypot(
                        content.width.toDouble(), content.height.toDouble()
                    ).toFloat()
                    try {
                        val reveal = ViewAnimationUtils.createCircularReveal(
                            content, cx, cy, 0f, radius
                        ).apply { duration = 450 }
                        content.visibility = View.VISIBLE
                        reveal.start()
                    } catch (e: Exception) {
                    }
                }
            }
            MotionStyle.BLUR, MotionStyle.DEPTH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val blur = RenderEffect.createBlurEffect(
                            24f, 24f, Shader.TileMode.CLAMP
                        )
                        content.setRenderEffect(blur)
                        val anim = ValueAnimator.ofFloat(24f, 0f).apply {
                            duration = 400
                            interpolator = AccelerateDecelerateInterpolator()
                            addUpdateListener { v ->
                                val r = (v.animatedValue as Float)
                                content.setRenderEffect(
                                    if (r <= 0.1f) null
                                    else RenderEffect.createBlurEffect(
                                        r, r, Shader.TileMode.CLAMP
                                    )
                                )
                            }
                        }
                        anim.start()
                    } catch (e: Exception) {
                    }
                }
                if (style == MotionStyle.DEPTH) {
                    content.scaleX = 1.12f
                    content.scaleY = 1.12f
                    content.alpha = 0f
                    content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(350)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
            }
            MotionStyle.PARALLAX -> {
                // Layers move at different speeds: the header is quicker
                // than the content below it.
                content.post {
                    val root = (content as? android.view.ViewGroup) ?: return@post
                    if (root.childCount == 0) return@post
                    val first = root.getChildAt(0)
                    first.translationX = 90f
                    first.alpha = 0f
                    first.animate().translationX(0f).alpha(1f).setDuration(300).start()
                    content.translationX = 220f
                    content.animate().translationX(0f).setDuration(420).start()
                }
            }
            else -> Unit
        }
    }

    /** Slow Ken Burns zoom on the cover disc (style KEN_BURNS). */
    fun applyKenBurns(view: View): ObjectAnimator {
        return ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.09f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Shared-element transition set for the target activity's window. */
    fun sharedTransition(): TransitionSet {
        return TransitionSet().apply {
            addTransition(
                SafeContainerTransform().apply {
                    duration = 450
                    interpolator = AccelerateDecelerateInterpolator()
                }
            )
            addTransition(ChangeBounds().apply { duration = 450 })
            addTransition(ChangeTransform().apply { duration = 450 })
            addTransition(ChangeImageTransform().apply { duration = 450 })
            ordering = TransitionSet.ORDERING_TOGETHER
        }
    }

    /** Light slide used for tab switches under the DEFAULT style. */
    fun applyTabSwitch(view: View, context: android.content.Context) {
        val style = MotionStyle.effective(context)
        if (style == MotionStyle.DEFAULT || style == MotionStyle.SLIDE) {
            view.alpha = 0f
            view.translationX = 40f
            view.animate().alpha(1f).translationX(0f).setDuration(220).start()
        } else {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(180).start()
        }
    }

    fun enterAnim(style: MotionStyle): Int = when (style) {
        MotionStyle.SLIDE, MotionStyle.DEFAULT, MotionStyle.PARALLAX -> R.anim.slide_in_right
        MotionStyle.FADE, MotionStyle.STAGGER, MotionStyle.BLUR,
        MotionStyle.KEN_BURNS, MotionStyle.PARTICLES, MotionStyle.RIPPLE,
        MotionStyle.SHIMMER -> R.anim.fade_in
        MotionStyle.ZOOM -> R.anim.zoom_in
        MotionStyle.FLIP -> R.anim.flip_in
        MotionStyle.CUBE -> R.anim.cube_in
        MotionStyle.SPRING -> R.anim.spring_in
        MotionStyle.SHEET -> R.anim.sheet_in
        MotionStyle.AXIS -> R.anim.axis_in
        MotionStyle.EXPLODE -> R.anim.explode_in
        MotionStyle.DEPTH -> R.anim.depth_in
        MotionStyle.CAROUSEL -> R.anim.carousel_in
        MotionStyle.PAGE_CURL -> R.anim.pagecurl_in
        MotionStyle.SHARED, MotionStyle.MORPH, MotionStyle.REVEAL -> R.anim.fade_in
    }

    fun exitAnim(style: MotionStyle): Int = when (style) {
        MotionStyle.SLIDE, MotionStyle.DEFAULT, MotionStyle.PARALLAX -> R.anim.slide_out_left
        MotionStyle.FADE, MotionStyle.STAGGER, MotionStyle.BLUR,
        MotionStyle.KEN_BURNS, MotionStyle.PARTICLES, MotionStyle.RIPPLE,
        MotionStyle.SHIMMER -> R.anim.fade_out
        MotionStyle.ZOOM -> R.anim.zoom_out
        MotionStyle.FLIP -> R.anim.flip_out
        MotionStyle.CUBE -> R.anim.cube_out
        MotionStyle.SPRING -> R.anim.spring_out
        MotionStyle.SHEET -> R.anim.sheet_out
        MotionStyle.AXIS -> R.anim.axis_out
        MotionStyle.EXPLODE -> R.anim.explode_out
        MotionStyle.DEPTH -> R.anim.depth_out
        MotionStyle.CAROUSEL -> R.anim.carousel_out
        MotionStyle.PAGE_CURL -> R.anim.pagecurl_out
        MotionStyle.SHARED, MotionStyle.MORPH, MotionStyle.REVEAL -> R.anim.fade_out
    }

    /** (enter, exit) for the finish() call — the reverse direction. */
    private fun backAnims(style: MotionStyle): Pair<Int, Int> = when (style) {
        MotionStyle.SLIDE, MotionStyle.DEFAULT, MotionStyle.PARALLAX ->
            Pair(R.anim.slide_in_left, R.anim.slide_out_right)
        MotionStyle.SHEET -> Pair(R.anim.fade_in, R.anim.sheet_out)
        MotionStyle.AXIS -> Pair(R.anim.axis_in_back, R.anim.axis_out_back)
        else -> Pair(exitAnim(style), enterAnim(style))
    }
}
