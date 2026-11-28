package com.musicplayer.app.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.musicplayer.app.BuildConfig
import com.musicplayer.app.data.BackupManager
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.data.QueueStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.player.EqManager
import com.musicplayer.app.player.LyricsManager
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.ReverbManager
import com.musicplayer.app.player.VisualizerHub
import com.musicplayer.app.player.VlcEngine
import com.musicplayer.app.player.VolumeBoost
import com.musicplayer.app.report.ErrorReporter
import com.musicplayer.app.update.UpdateChecker
import com.musicplayer.app.widget.ZenoWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// ---------------------------------------------------------------------------
// TEMPORARY MODULE — the self-test (coach's order, 2026-11-28).
// Standalone unit, easy to delete: remove this file, SelfTestActivity.kt,
// activity_self_test.xml, its <activity> entry in the manifest and the one
// "self-test" button in the Settings tab — nothing else references it.
// It stays in v1.0.4 until the coach asks for its removal.
// ---------------------------------------------------------------------------

/**
 * Automated in-app self-test: exercises each feature for real (playback,
 * waves, lyrics, EQ, formats via Media3 and VLC, updater, backup,
 * notification, widget), checks the current screen for clipped elements /
 * truncated text / disabled buttons, and reports privacy + permissions.
 *
 * The report is shown in [SelfTestActivity] and sent automatically to the
 * coach via the crash-report server with type "self-test".
 */
object SelfTest {

    enum class Status { PASS, FAIL, SKIP, INFO }

    data class CheckResult(val name: String, val status: Status, val detail: String)

    /**
     * Runs every check. [activity] (the settings screen) is used only for
     * the UI walk; pass null to skip it. Never throws — a crashing check
     * becomes a FAIL result. The playback probe plays ~2 seconds of the
     * first song and then restores the previous queue and position.
     */
    suspend fun run(
        context: Context,
        activity: Activity?,
        onProgress: (String) -> Unit = {}
    ): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        val library = try {
            MusicRepository.loadSongs(context)
        } catch (e: Exception) {
            results += CheckResult(
                "library", Status.FAIL, "loadSongs failed: ${e.message}"
            )
            return results
        }
        results += CheckResult(
            "library", Status.PASS,
            "${library.size} songs, ${MusicRepository.albumsOf(library).size} albums, " +
                "${MusicRepository.artistsOf(library).size} artists"
        )

        // ---- Playback + waves probe (real playback, ~2s) ----
        var wavesOk: Boolean? = null
        if (library.isEmpty()) {
            results += CheckResult(
                "playback", Status.SKIP, "empty library — nothing to play"
            )
        } else {
            onProgress("playback")
            val probe = probePlayback(context, library)
            results += probe.first
            wavesOk = probe.second
        }
        // Real FFT or simulated? (fresh live data = real; nothing
        // arriving = the views are simulating, e.g. VLC track.)
        val realData = VisualizerHub.isRealDataFresh()
        results += when (wavesOk) {
            true -> CheckResult(
                "waves", Status.PASS,
                "live spectrum received during playback — " +
                    if (realData) "real FFT from the audio"
                    else "simulated (no live FFT — VLC track)"
            )
            false -> CheckResult(
                "waves", Status.FAIL, "no spectrum data during playback"
            )
            null -> CheckResult("waves", Status.SKIP, "no playback to measure")
        }

        // ---- Waveform shape: the saved shape + where it applies ----
        val shapeName = context.getString(
            WaveformStyle.LABELS[WaveformStyle.current(context)]
        )
        results += CheckResult(
            "waveform", Status.PASS,
            "shape \"$shapeName\" (${WaveformStyle.LABELS.size} shapes) " +
                "applied to the full player + the mini player, " +
                if (realData) "driven by real FFT" else "simulated right now"
        )

        // ---- Lyrics (embedded / .lrc only — no network here) ----
        onProgress("lyrics")
        val testSong = library.firstOrNull()
        if (testSong == null) {
            results += CheckResult("lyrics", Status.SKIP, "no song to test")
        } else {
            try {
                val lyrics = LyricsManager.loadLyrics(context, testSong, false)
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    results += CheckResult(
                        "lyrics", Status.PASS,
                        "${lyrics.lines.size} synced lines for \"${testSong.title}\""
                    )
                } else {
                    results += CheckResult(
                        "lyrics", Status.SKIP,
                        "no synced lyrics for \"${testSong.title}\" (normal)"
                    )
                }
            } catch (e: Exception) {
                results += CheckResult("lyrics", Status.FAIL, e.message ?: "error")
            }
        }

        // ---- Equalizer: live attachment, not just saved prefs ----
        onProgress("equalizer")
        try {
            Class.forName("android.media.audiofx.Equalizer")
            val bands = EqManager.currentBandsDb(context)
            EqManager.setLevels(context, bands)
            val back = EqManager.currentBandsDb(context)
            // v1.0.9: the prefs round-trip passed for months while the EQ
            // was attached to NOTHING (Media3 never fired
            // onAudioSessionIdChanged), so also verify the live effect
            // object is really attached to an audio session.
            val attached = EqManager.available && EqManager.attachedSessionId != 0
            val songPlaying = PlayerConnection.currentSong() != null
            results += when {
                !back.contentEquals(bands) ->
                    CheckResult("equalizer", Status.FAIL, "levels did not round-trip")
                attached ->
                    CheckResult(
                        "equalizer", Status.PASS,
                        "5 bands attached to session ${EqManager.attachedSessionId}, " +
                            "preset \"${EqManager.currentPresetName(context)}\", " +
                            "bass ${EqManager.currentBass(context)}"
                    )
                !songPlaying ->
                    CheckResult(
                        "equalizer", Status.SKIP,
                        "no song playing yet — play a song, then re-run the test"
                    )
                else ->
                    CheckResult(
                        "equalizer", Status.FAIL,
                        "EQ values save, but no live effect is attached to any audio session"
                    )
            }
        } catch (e: Exception) {
            results += CheckResult("equalizer", Status.FAIL, e.message ?: "error")
        }

        // ---- Volume boost (200%): the gain stage must survive a fallback
        // rebuild (v1.0.14). Before the fix, a playback error rebuilt the
        // player WITHOUT the gain processor: boostGain was 2.0 in memory
        // but nothing read it, so the slider showed 200% while the sound
        // stayed at 100%. The check sets 200% in memory only (no device
        // volume change), verifies the gain value AND that the active
        // player was built with the gain stage, then restores the value.
        onProgress("volume-boost")
        try {
            val savedPercent = VolumeBoost.volumePercent
            VolumeBoost.setVolumePercent(200)
            val boostAt200 = VolumeBoost.boostGain
            val stage = PlaybackService.gainStageActive
            VolumeBoost.setVolumePercent(savedPercent)
            results += when {
                stage == null -> CheckResult(
                    "volume-boost", Status.SKIP,
                    "no player built yet — play a song, then re-run the test"
                )
                boostAt200 != 2.0f -> CheckResult(
                    "volume-boost", Status.FAIL,
                    "boostGain is $boostAt200 at 200%, expected 2.0"
                )
                stage == false -> CheckResult(
                    "volume-boost", Status.FAIL,
                    "active player built WITHOUT the gain stage (boost is fake)"
                )
                else -> CheckResult(
                    "volume-boost", Status.PASS,
                    "boostGain 2.0 at 200% and the active player has the gain stage"
                )
            }
        } catch (e: Exception) {
            results += CheckResult("volume-boost", Status.FAIL, e.message ?: "error")
        }

        // ---- Formats: Media3 (primary) + VLC (fallback) ----
        onProgress("formats")
        try {
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
            val session = PlayerConnection.controller != null
            results += CheckResult(
                "formats-media3", Status.PASS,
                "ExoPlayer present" + if (session) ", session connected" else ""
            )
        } catch (e: Exception) {
            results += CheckResult("formats-media3", Status.FAIL, e.message ?: "error")
        }
        try {
            VlcEngine.init(context)
            val vlcOnly = library.count { Song.needsVlc(it) }
            results += CheckResult(
                "formats-vlc", Status.PASS,
                "libVLC initialised; $vlcOnly rare-format song(s) in the library"
            )
        } catch (e: Exception) {
            results += CheckResult("formats-vlc", Status.FAIL, e.message ?: "error")
        }

        // ---- In-app updater (metadata only — no download) ----
        onProgress("updater")
        try {
            val latest = withTimeoutOrNull(10_000) { UpdateChecker.fetchLatest() }
            results += if (latest != null) {
                CheckResult(
                    "updater", Status.PASS,
                    "GitHub reachable, latest ${latest.tag} (${latest.versionName})"
                )
            } else {
                CheckResult("updater", Status.FAIL, "GitHub unreachable (offline?)")
            }
        } catch (e: Exception) {
            results += CheckResult("updater", Status.FAIL, e.message ?: "error")
        }

        // ---- Backup round-trip (in memory — nothing written to disk) ----
        onProgress("backup")
        try {
            val backup = BackupManager.buildBackup(context, library)
            val json = BackupManager.serialize(backup)
            val restored = BackupManager.restore(context, json, library)
            results += CheckResult(
                "backup", Status.PASS,
                "backup ${json.length} bytes, restore completed (${restored.playlists} playlists)"
            )
        } catch (e: Exception) {
            results += CheckResult("backup", Status.FAIL, e.message ?: "error")
        }

        // ---- Notification channel + permission ----
        onProgress("notification")
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val channel = manager.getNotificationChannel("zeno_playback")
            val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            results += when {
                channel == null -> CheckResult(
                    "notification", Status.SKIP,
                    "channel not created yet (playback service never ran)"
                )
                !enabled -> CheckResult(
                    "notification", Status.FAIL, "notifications permission denied"
                )
                else -> CheckResult("notification", Status.PASS, "channel zeno_playback ready")
            }
        } catch (e: Exception) {
            results += CheckResult("notification", Status.FAIL, e.message ?: "error")
        }

        // ---- Widget ----
        onProgress("widget")
        try {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ZenoWidgetProvider::class.java)
            )
            results += CheckResult(
                "widget", Status.INFO,
                if (ids.isNotEmpty()) "placed on the home screen (${ids.size})"
                else "not placed (optional)"
            )
        } catch (e: Exception) {
            results += CheckResult("widget", Status.FAIL, e.message ?: "error")
        }

        // ---- UI walk of the current screen ----
        if (activity != null) {
            onProgress("ui")
            results += checkCurrentScreen(activity)
        }

        // ---- Permissions ----
        onProgress("permissions")
        val missing = mutableListOf<String>()
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, audioPerm) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += audioPerm
        }
        results += if (missing.isEmpty()) {
            CheckResult("permissions", Status.PASS, "audio + notification granted")
        } else {
            CheckResult("permissions", Status.FAIL, "missing: ${missing.joinToString()}")
        }

        // ---- Privacy: no tracker / ad SDKs present ----
        onProgress("privacy")
        val trackers = listOf(
            "com.google.firebase.analytics.FirebaseAnalytics",
            "com.google.android.gms.ads.MobileAds",
            "com.google.firebase.crashlytics.FirebaseCrashlytics"
        ).filter { classExists(it) }
        results += if (trackers.isEmpty()) {
            CheckResult("privacy", Status.PASS, "no Firebase / AdMob / Crashlytics classes")
        } else {
            CheckResult("privacy", Status.FAIL, "tracker present: ${trackers.joinToString()}")
        }

        // ---- Storage ----
        try {
            val stat = StatFs(context.filesDir.path)
            val freeMb = stat.availableBytes / (1024 * 1024)
            results += CheckResult("storage", Status.INFO, "$freeMb MB free")
        } catch (e: Exception) {
        }

        // ---- Luxury components resolvable ----
        onProgress("components")
        try {
            val pm = context.packageManager
            val names = listOf(
                LockActivity::class.java, WrappedActivity::class.java,
                AlarmActivity::class.java, SelfTestActivity::class.java
            )
            var ok = true
            for (cls in names) {
                try {
                    pm.getActivityInfo(ComponentName(context, cls), 0)
                } catch (e: Exception) {
                    ok = false
                }
            }
            try {
                pm.getReceiverInfo(ComponentName(context, AlarmReceiver::class.java), 0)
            } catch (e: Exception) {
                ok = false
            }
            results += if (ok) {
                CheckResult("components", Status.PASS, "lock / wrapped / alarm / self-test present")
            } else {
                CheckResult("components", Status.FAIL, "a component is missing from the manifest")
            }
        } catch (e: Exception) {
            results += CheckResult("components", Status.FAIL, e.message ?: "error")
        }

        // ---- Reverb: live attachment, not just the saved preset ----
        onProgress("reverb")
        try {
            Class.forName("android.media.audiofx.PresetReverb")
            val preset = ReverbManager.loadPreset(context)
            // v1.0.9: same trap as the equalizer — the preset round-trip
            // passed while no live reverb was attached to any session.
            val attached = ReverbManager.isAttached()
            val songPlaying = PlayerConnection.currentSong() != null
            results += when {
                preset !in 0..3 ->
                    CheckResult("reverb", Status.FAIL, "invalid preset $preset")
                attached ->
                    CheckResult(
                        "reverb", Status.PASS,
                        "preset $preset, attached to session ${ReverbManager.attachedSessionId}"
                    )
                !songPlaying ->
                    CheckResult(
                        "reverb", Status.SKIP,
                        "no song playing yet — play a song, then re-run the test"
                    )
                else ->
                    CheckResult(
                        "reverb", Status.FAIL,
                        "preset $preset saved, but no live reverb is attached"
                    )
            }
        } catch (e: Exception) {
            results += CheckResult("reverb", Status.FAIL, e.message ?: "error")
        }

        // ---- Data: queues + stats ----
        try {
            val queues = QueueStore.load(context)
            val plays = PlaybackStateStore.playCounts(context).values.sum()
            results += CheckResult(
                "data", Status.INFO,
                "${queues.size} saved queue(s), $plays total play(s) recorded"
            )
        } catch (e: Exception) {
        }

        return results
    }

    /** Plays ~2s of the first song, checks the position advances and the
     * spectrum flows, then restores the previous queue/position. Returns
     * (playback result, waves ok?). */
    private suspend fun probePlayback(
        context: Context,
        library: List<Song>
    ): Pair<CheckResult, Boolean?> {
        val controller = PlayerConnection.controller
            ?: return CheckResult(
                "playback", Status.FAIL, "no playback session connected"
            ) to null
        val wasPlaying = controller.isPlaying
        val savedSongs = PlayerConnection.songs.toList()
        val savedIndex = PlayerConnection.currentIndex()
        val savedPos = PlayerConnection.currentPositionMs()
        var maxMagnitude = 0f
        val listener: (FloatArray) -> Unit = { magnitudes ->
            for (m in magnitudes) if (m > maxMagnitude) maxMagnitude = m
        }
        VisualizerHub.addListener(listener)
        return try {
            PlayerConnection.playSongs(library, 0)
            val pos1 = PlayerConnection.currentPositionMs()
            delay(2000)
            val pos2 = PlayerConnection.currentPositionMs()
            val advanced = pos2 > pos1 + 300
            // Restore what was playing before the probe.
            if (savedSongs.isNotEmpty() && savedIndex in savedSongs.indices) {
                PlayerConnection.playSongs(savedSongs, savedIndex)
                PlayerConnection.seekTo(savedPos)
                if (!wasPlaying) PlayerConnection.togglePlayPause()
            } else {
                if (controller.isPlaying) PlayerConnection.togglePlayPause()
            }
            val result = if (advanced) {
                CheckResult("playback", Status.PASS, "position advanced ${pos1}ms -> ${pos2}ms")
            } else {
                CheckResult("playback", Status.FAIL, "position stuck at ${pos2}ms")
            }
            result to (maxMagnitude > 0.02f)
        } catch (e: Exception) {
            CheckResult("playback", Status.FAIL, e.message ?: "error") to null
        } finally {
            VisualizerHub.removeListener(listener)
        }
    }

    /** Walks the current screen's view tree: truncated text, views drawn
     * outside the screen, clickable views that are disabled. */
    private fun checkCurrentScreen(activity: Activity): CheckResult {
        return try {
            val root = activity.window.decorView
            val displayMetrics = activity.resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val issues = mutableListOf<String>()
            val loc = IntArray(2)
            fun walk(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view.width > 0 && view.height > 0) {
                    view.getLocationOnScreen(loc)
                    if (loc[0] + view.width > screenW + 1 ||
                        loc[1] + view.height > screenH + 1
                    ) {
                        issues += "${view.javaClass.simpleName} off-screen " +
                            "(${loc[0]},${loc[1]} ${view.width}x${view.height})"
                    }
                    if (view.isClickable && !view.isEnabled) {
                        issues += "${view.javaClass.simpleName} clickable but disabled"
                    }
                    if (view is TextView) {
                        val layout = view.layout
                        if (layout != null) {
                            for (line in 0 until layout.lineCount) {
                                if (layout.getEllipsisCount(line) > 0) {
                                    issues += "text truncated: \"${view.text}\""
                                    break
                                }
                            }
                        }
                    }
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
            }
            walk(root)
            if (issues.isEmpty()) {
                CheckResult("ui", Status.PASS, "no clipped / truncated / dead elements found")
            } else {
                CheckResult(
                    "ui", Status.FAIL,
                    "${issues.size} issue(s): ${issues.take(5).joinToString(" | ")}"
                )
            }
        } catch (e: Exception) {
            CheckResult("ui", Status.FAIL, e.message ?: "error")
        }
    }

    private fun classExists(name: String): Boolean {
        return try {
            Class.forName(name)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /** Full text report (sent to the coach + shown in the app). */
    fun formatReport(context: Context, results: List<CheckResult>): String {
        val passed = results.count { it.status == Status.PASS }
        val failed = results.count { it.status == Status.FAIL }
        val skipped = results.count { it.status == Status.SKIP }
        val sb = StringBuilder()
        sb.append("Zeno Play v${BuildConfig.VERSION_NAME} — self-test\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Result: $passed passed, $failed failed, $skipped skipped\n\n")
        for (r in results) {
            sb.append("${r.status} — ${r.name}: ${r.detail}\n")
        }
        return sb.toString()
    }

    /** Sends the report to the coach via the crash-report server. */
    fun sendReport(report: String) {
        ErrorReporter.log(ErrorReporter.TYPE_SELF_TEST, report)
    }
}
