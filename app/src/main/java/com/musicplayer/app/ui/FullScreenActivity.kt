package com.musicplayer.app.ui

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.musicplayer.app.R

/**
 * Base activity for every Zeno Play screen.
 *
 * Force full screen, always:
 * - edge-to-edge content (draws behind the system bars, no black bars,
 *   no letterboxing, no window mode),
 * - immersive sticky mode: the status bar and the navigation bar are hidden
 *   and re-hide themselves after any swipe that reveals them,
 * - layout into the display cutout (shortEdges).
 *
 * The flags are re-applied on every resume / focus change, so the app
 * never leaves full-screen mode.
 */
abstract class FullScreenActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Language (French / Arabic / English) + layout direction (RTL for
        // Arabic) applied before anything inflates.
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // AMOLED black theme (if enabled in settings) must be applied
        // before super.onCreate inflates anything.
        if (AppTheme.isAmoled(this)) {
            setTheme(R.style.Theme_ZenoPlay_Amoled)
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        applyImmersive()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Motion: the entry effect for the chosen motion style.
        TransitionHelper.applyEnterEffect(this)
    }

    override fun finish() {
        super.finish()
        // Motion: the return animation (skipped for shared-element returns).
        TransitionHelper.applyExit(this)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /** Hides the status bar and navigation bar (immersive sticky). */
    protected fun applyImmersive() {
        // v1.0.16 (m25): the old code used the deprecated
        // decorView.systemUiVisibility flags — replaced with
        // WindowInsetsControllerCompat (same behaviour, no deprecation).
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
