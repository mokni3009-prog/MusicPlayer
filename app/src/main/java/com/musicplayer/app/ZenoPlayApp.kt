package com.musicplayer.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.player.SystemVolumeSync
import com.musicplayer.app.player.VolumeBoost
import com.musicplayer.app.report.ErrorReporter
import com.musicplayer.app.ui.LocaleHelper

/**
 * Application class: installs the global crash handler and flushes any
 * error reports saved by previous runs, before any activity or service
 * starts. Every step is guarded — a failure here must never kill the launch.
 */
class ZenoPlayApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        try {
            ErrorReporter.init(this)
        } catch (e: Exception) {
            Log.w("ZenoPlayApp", "ErrorReporter.init failed", e)
        }
        try {
            ErrorReporter.installCrashHandler()
        } catch (e: Exception) {
            Log.w("ZenoPlayApp", "installCrashHandler failed", e)
        }
        try {
            ErrorReporter.flushPendingReports()
        } catch (e: Exception) {
            Log.w("ZenoPlayApp", "flushPendingReports failed", e)
        }
        try {
            // v1.0.8: two-way sync between the volume slider and the device
            // volume. The slider starts from the CURRENT device volume; a
            // saved boost above 100% survives only while the device is
            // still at max.
            SystemVolumeSync.attach(this)
            val saved = PlaybackStateStore.loadVolume(this)
            val initial = SystemVolumeSync.initialPercent(saved)
            VolumeBoost.setVolumePercent(initial)
            PlaybackStateStore.saveVolume(this, initial)
        } catch (e: Exception) {
            Log.w("ZenoPlayApp", "SystemVolumeSync init failed", e)
        }
    }
}
