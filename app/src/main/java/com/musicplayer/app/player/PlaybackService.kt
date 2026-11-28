package com.musicplayer.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PerSongAudioStore
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.report.ErrorReporter
import com.musicplayer.app.ui.AlarmRingingActivity
import com.musicplayer.app.ui.AlarmStore
import org.jaudiotagger.audio.AudioFileIO

/**
 * Background playback service (Media3 MediaLibraryService — Android Auto
 * browses the library through it).
 *
 * Two engines:
 * - Media3/ExoPlayer is the PRIMARY engine (Gapless, Auto, notification,
 *   live waves, speed, equalizer).
 * - libVLC is the FALLBACK engine ([VlcEngine]): when the current track
 *   uses a format Media3 cannot decode (see [Song.VLC_ONLY_EXTENSIONS])
 *   or when Media3 fails on a track, playback switches to VLC
 *   automatically, with no user action. While a VLC track plays, the
 *   visualizer shows its animated simulated waves and the notification is
 *   a custom one built by [ZenoNotificationProvider].
 *
 * Also here: sleep timer with fade-out (enforced by the ticker), A–B
 * repeat, "play once then next", resume-state saving, play counts and
 * listening-time accumulation, ReplayGain, per-output equalizer switching,
 * headset-unplug pause, and the v1.0.3 error reporting + plain-player
 * fallback.
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "root"
        const val ALL_ID = "all"
        const val ALBUMS_ID = "albums"
        const val ARTISTS_ID = "artists"
        const val PLAYLISTS_ID = "playlists"
        const val FOLDERS_ID = "folders"

        internal const val NOTIFICATION_CHANNEL_ID = "zeno_playback"

        /** Alarm channel + notification id (v1.0.13). */
        internal const val ALARM_CHANNEL_ID = "zeno_alarm"
        private const val ALARM_NOTIFICATION_ID = 2001

        /** Notification X button: stop playback, dismiss the notification
         * and stop the foreground service (handled in onStartCommand). */
        const val ACTION_CLOSE_NOTIFICATION = "com.musicplayer.app.CLOSE_NOTIFICATION"

        /** Notification buttons (v1.0.14): previous / play-pause / next,
         * handled directly by the service in onStartCommand so they work
         * even when the app UI is destroyed. The old broadcast path
         * (VlcActionReceiver -> the activity's MediaController) dies with
         * the activity, which is why the buttons went silently dead. */
        const val ACTION_NOTIFICATION_PREV = "com.musicplayer.app.NOTIFICATION_PREV"
        const val ACTION_NOTIFICATION_TOGGLE = "com.musicplayer.app.NOTIFICATION_TOGGLE"
        const val ACTION_NOTIFICATION_NEXT = "com.musicplayer.app.NOTIFICATION_NEXT"

        /**
         * Whether the CURRENT ExoPlayer was built with the custom
         * renderers factory (spectrum + gain stage). Null = no player has
         * been built yet in this process. Set wherever a player is built;
         * read by the self-test "volume-boost" check.
         */
        @Volatile
        var gainStageActive: Boolean? = null

        private const val SLEEP_FADE_MS = 10_000L
        private const val TICK_MS = 250L
        private const val SAVE_POSITION_EVERY_MS = 5_000L
    }

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var visualizer: Visualizer? = null
    private val spectrumProcessor = SpectrumAudioProcessor()
    private val gainProcessor = GainAudioProcessor()

    /** True once we already fell back to the plain player — never retry twice. */
    private var fallbackActive = false

    /** True while the current track is being played by VLC. */
    private var vlcPlayingCurrent = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSaveMs = 0L
    private var lastTickMs = 0L
    private var listenSecondsAccum = 0L
    private var currentTrackListenedMs = 0L
    private var currentTrackId: Long = -1L
    private var lastPositionMs = 0L
    private var replayGainCache = HashMap<Long, Float>()

    private var libraryCache: List<Song>? = null

    /** The alarm volume ramp, if one is running (cancelled by stopAlarm). */
    private var alarmRamp: Runnable? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlayerConnection.pause()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            refreshEqOutput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            refreshEqOutput()
        }
    }

    private fun refreshEqOutput() {
        try {
            val output = EqManager.detectOutput(this)
            EqManager.onOutputChanged(this, output)
        } catch (e: Exception) {
            // EQ output detection must never break playback.
        }
    }

    // ---- Ticker (sleep timer, A–B, resume saving, listening time) --------

    private val ticker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val now = System.currentTimeMillis()
            val playing = PlayerConnection.isPlaying()
            if (playing) {
                if (lastTickMs > 0) {
                    val delta = now - lastTickMs
                    currentTrackListenedMs += delta
                    listenSecondsAccum += delta / 1000
                    if (listenSecondsAccum >= 30) {
                        PlaybackStateStore.addListenSeconds(
                            applicationContext, listenSecondsAccum
                        )
                        listenSecondsAccum = 0
                    }
                }
                // "Play once then next": with repeat ONE the track loops
                // instead of ending. Detect the loop (position wrapped back
                // from near the end) and move to the next track instead.
                val pos = PlayerConnection.currentPositionMs()
                val dur = PlayerConnection.durationMs()
                if (PlayerConnection.playOnceThenNext &&
                    lastPositionMs > 0 && dur > 0 &&
                    lastPositionMs > dur * 9 / 10 && pos < lastPositionMs - 1000
                ) {
                    PlayerConnection.playOnceThenNext = false
                    PlayerConnection.next()
                }
                lastPositionMs = pos
                // A–B repeat: loop back to A when the position passes B.
                if (PlayerConnection.abEnabled) {
                    val abPos = PlayerConnection.currentPositionMs()
                    if (abPos >= PlayerConnection.abEndMs && PlayerConnection.abEndMs > 0) {
                        PlayerConnection.seekTo(PlayerConnection.abStartMs)
                    }
                }
                // Sleep timer (minutes mode): fade out in the last 10s.
                if (PlayerConnection.sleepMode == PlayerConnection.SLEEP_MINUTES) {
                    val remaining = PlayerConnection.sleepRemainingMs()
                    if (remaining <= 0) {
                        stopForSleepTimer()
                    } else if (remaining <= SLEEP_FADE_MS) {
                        val factor = (remaining.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                        applyFadeFactor(factor)
                    }
                }
                // Save the resume position every few seconds.
                if (now - lastSaveMs >= SAVE_POSITION_EVERY_MS) {
                    lastSaveMs = now
                    saveResumeState()
                }
            }
            lastTickMs = now
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private fun applyFadeFactor(factor: Float) {
        PlayerConnection.sleepFadeFactor = factor
        player?.volume = VolumeBoost.playerVolume() * factor
        VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(VolumeBoost.volumePercent, factor))
    }

    private fun stopForSleepTimer() {
        PlayerConnection.cancelSleep()
        applyFadeFactor(1.0f)
        PlayerConnection.pause()
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                getString(R.string.sleep_timer_stopped),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveResumeState() {
        try {
            val exo = player ?: return
            val ids = PlayerConnection.songs.map { it.id }
            if (ids.isEmpty()) return
            val index = exo.currentMediaItemIndex.coerceAtLeast(0)
            val pos = PlayerConnection.currentPositionMs()
            PlaybackStateStore.saveQueue(applicationContext, ids, index, pos)
        } catch (e: Exception) {
            // Resume saving is best-effort.
        }
    }

    // ---- VLC listener ------------------------------------------------------

    private val vlcListener = object : VlcEngine.Listener {
        override fun onVlcStarted(song: Song) {
            vlcPlayingCurrent = true
            // v1.0.9: re-apply the EQ now that VLC has a live input — an
            // equalizer set before the media starts can be dropped.
            VlcEngine.applyEqualizer(
                EqManager.currentBandsDb(applicationContext),
                EqManager.currentBass(applicationContext)
            )
            nudgeNotification()
        }

        override fun onVlcPaused(song: Song) {
            nudgeNotification()
        }

        override fun onVlcStopped() {
            vlcPlayingCurrent = false
            nudgeNotification()
        }

        override fun onVlcCompleted(song: Song) {
            recordPlayIfListened()
            handleTrackFinished()
        }

        override fun onVlcError(song: Song, message: String) {
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "VLC fallback failed for ${song.extension}: $message",
                null,
                mapOf("extension" to song.extension)
            )
            vlcPlayingCurrent = false
            // Skip to the next track; stop if there is none.
            val exo = player
            if (exo != null && exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
                exo.play()
            } else {
                exo?.pause()
            }
        }
    }

    /** After a track finishes: sleep-after-songs countdown, then next. */
    private fun handleTrackFinished() {
        if (PlayerConnection.sleepMode == PlayerConnection.SLEEP_SONGS) {
            PlayerConnection.sleepSongsRemaining--
            if (PlayerConnection.sleepSongsRemaining <= 0) {
                stopForSleepTimer()
                return
            }
        }
        if (PlayerConnection.playOnceThenNext) {
            PlayerConnection.playOnceThenNext = false
        }
        val exo = player ?: return
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        } else if (exo.repeatMode == Player.REPEAT_MODE_ALL) {
            exo.seekTo(0, 0)
            exo.prepare()
            exo.play()
        } else {
            exo.pause()
        }
    }

    private fun recordPlayIfListened() {
        try {
            val id = currentTrackId
            if (id > 0 && currentTrackListenedMs >= 30_000) {
                PlaybackStateStore.recordPlay(applicationContext, id)
            }
        } catch (e: Exception) {
        } finally {
            currentTrackListenedMs = 0L
        }
    }

    /** Nudges the notification to refresh (e.g. after a VLC state change). */
    private fun nudgeNotification() {
        try {
            val exo = player ?: return
            if (exo.playbackState != Player.STATE_IDLE) {
                exo.seekTo(exo.currentPosition)
            }
        } catch (e: Exception) {
            // Best-effort only.
        }
    }

    // ---- Player listener ---------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            setupVisualizer(audioSessionId)
            if (audioSessionId != 0) {
                EqManager.attach(applicationContext, audioSessionId)
                ReverbManager.attach(applicationContext, audioSessionId)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                VisualizerHub.publishSilence()
            }
            com.musicplayer.app.widget.ZenoWidgetProvider.updateAll(applicationContext)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recordPlayIfListened()
            saveResumeState()
            PlayerConnection.clearAB()
            com.musicplayer.app.widget.ZenoWidgetProvider.updateAll(applicationContext)
            val song = mediaItem?.mediaId?.toLongOrNull()
                ?.let { id -> PlayerConnection.songs.firstOrNull { it.id == id } }
                ?: PlayerConnection.currentSong()
            currentTrackId = song?.id ?: -1L
            currentTrackListenedMs = 0L
            if (song == null) return
            applyReplayGainAsync(song)
            applyPerSongAudio(song)
            // Sleep timer (songs mode) counts finished tracks; the
            // countdown happens in handleTrackFinished / onPlaybackStateChanged.
            if (Song.needsVlc(song)) {
                routeToVlc(song)
            } else if (vlcPlayingCurrent || VlcEngine.active) {
                VlcEngine.stop()
                vlcPlayingCurrent = false
                // v1.0.6: the ExoPlayer volume must be re-applied here —
                // while VLC owned the track the player's volume was never
                // touched, so without this the slider setting was lost
                // on every VLC -> ExoPlayer switch.
                player?.volume = VolumeBoost.playerVolume() *
                    PlayerConnection.sleepFadeFactor
                player?.play()
            }
        }

        /** Applies the song's own saved EQ + speed (or the global ones). */
        private fun applyPerSongAudio(song: Song) {
            try {
                val saved = PerSongAudioStore.get(applicationContext, song.id)
                val ctx = applicationContext
                if (saved?.bandsDb != null) {
                    EqManager.applyLevelsNow(ctx, saved.bandsDb, saved.bass ?: 0)
                } else {
                    EqManager.applyCurrent(ctx)
                }
                val speed = saved?.speed ?: PlaybackStateStore.loadSpeed(ctx)
                player?.setPlaybackParameters(
                    androidx.media3.common.PlaybackParameters(speed, 1f)
                )
                VlcEngine.setRate(speed)
            } catch (e: Exception) {
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                recordPlayIfListened()
                handleTrackFinished()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val song = PlayerConnection.currentSong()
            val needsVlc = song != null && Song.needsVlc(song)
            // Expected routing: Media3 cannot decode this track — VLC
            // takes over. Not an error to report.
            if (needsVlc && !vlcPlayingCurrent) {
                routeToVlc(song!!)
                return
            }
            if (vlcPlayingCurrent) {
                // The player failed while VLC was playing the current track;
                // ignore it (VLC owns playback now).
                return
            }
            // v1.0.3: report the failure (error code + audio format, no file paths).
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "Playback failed: ${error.errorCodeName} (${error.errorCode}): ${error.message}",
                error,
                currentFormatInfo() + mapOf("fallback_active" to fallbackActive)
            )
            if (!fallbackActive) {
                fallbackActive = true
                rebuildPlayerWithFactory()
            }
        }
    }

    /** Audio format of the current track, for playback-error reports (no paths). */
    private fun currentFormatInfo(): Map<String, Any?> {
        return try {
            val format = player?.currentTracks?.groups?.firstOrNull()?.getTrackFormat(0)
                ?: return emptyMap()
            mapOf(
                "mime" to format.sampleMimeType,
                "codecs" to format.codecs,
                "bitrate" to format.bitrate.takeIf { it > 0 },
                "sample_rate" to format.sampleRate.takeIf { it > 0 },
                "channels" to format.channelCount.takeIf { it > 0 }
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Starts [song] on the VLC engine (pauses ExoPlayer first). */
    private fun routeToVlc(song: Song) {
        try {
            player?.pause()
            vlcPlayingCurrent = true
            VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(VolumeBoost.volumePercent, PlayerConnection.sleepFadeFactor))
            VlcEngine.setRate(PlaybackStateStore.loadSpeed(applicationContext))
            VlcEngine.applyEqualizer(EqManager.currentBandsDb(applicationContext))
            VlcEngine.play(song)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "VLC routing failed: ${e.message}",
                e
            )
            vlcPlayingCurrent = false
        }
    }

    /** Reads the track/album ReplayGain tag in the background and applies it. */
    private fun applyReplayGainAsync(song: Song) {
        replayGainCache[song.id]?.let { factor ->
            VolumeBoost.replayGain = factor
            return
        }
        Thread {
            try {
                val paths = com.musicplayer.app.data.TagEditor.filePathsFor(
                    applicationContext, listOf(song.id)
                )
                val path = paths[song.id]
                if (!path.isNullOrBlank()) {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) {
                        val audioFile = AudioFileIO.read(file)
                        val tag = audioFile.tag
                        val trackGain = tag?.getFirst("REPLAYGAIN_TRACK_GAIN")
                            ?.trim()?.removeSuffix(" dB")?.toFloatOrNull()
                        val albumGain = tag?.getFirst("REPLAYGAIN_ALBUM_GAIN")
                            ?.trim()?.removeSuffix(" dB")?.toFloatOrNull()
                        val db = trackGain ?: albumGain
                        if (db != null) {
                            VolumeBoost.setReplayGainDb(db)
                            replayGainCache[song.id] = VolumeBoost.replayGain
                            return@Thread
                        }
                    }
                }
                VolumeBoost.replayGain = 1.0f
            } catch (e: Exception) {
                VolumeBoost.replayGain = 1.0f
            }
        }.start()
    }

    /**
     * Rebuilds the player and retries playback from the same queue and
     * position (v1.0.3 fallback). The media session is rebuilt too, so
     * connected controllers get onDisconnected and reconnect. v1.0.14:
     * the rebuilt player uses the SAME spectrum + gain renderers factory
     * as the normal build — a plain rebuild dropped the gain stage, so
     * the 200% boost silently stopped working after any playback error.
     */
    private fun rebuildPlayerWithFactory() {
        try {
            rebuildPlayerWithFactoryInner()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "Playback fallback rebuild failed: ${e.message}",
                e
            )
        }
    }

    private fun rebuildPlayerWithFactoryInner() {
        val old = player ?: return
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        val position = old.currentPosition
        val shuffle = old.shuffleModeEnabled
        val repeat = old.repeatMode
        old.removeListener(playerListener)
        session?.release()
        old.release()
        releaseVisualizer()
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(buildRenderersFactory())
            .build()
        gainStageActive = true
        val plain = player!!
        plain.addListener(playerListener)
        // v1.0.9: re-attach EQ/Reverb/Visualizer to the NEW player's session
        // (see attachAudioEffects — the session callback never fires).
        attachAudioEffects()
        // v1.0.6: a fresh player defaults to volume 1.0 — restore the
        // user's slider setting (and the VLC volume) or the volume bar
        // silently stops working after a fallback rebuild.
        VolumeBoost.setVolumePercent(PlaybackStateStore.loadVolume(applicationContext))
        // v1.0.8: the restored slider value drives the device volume too.
        SystemVolumeSync.applySliderPercent(VolumeBoost.volumePercent)
        plain.volume = VolumeBoost.playerVolume()
        VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(VolumeBoost.volumePercent, 1f))
        if (items.isNotEmpty()) {
            plain.setMediaItems(items, index, position)
            plain.shuffleModeEnabled = shuffle
            plain.repeatMode = repeat
            plain.prepare()
            plain.play()
        }
        session = MediaLibrarySession.Builder(this, plain, sessionCallback)
            .build()
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                getString(R.string.playback_fallback_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---- Library (Android Auto) --------------------------------------------

    private fun library(): List<Song> {
        val cached = libraryCache
        if (cached != null) return cached
        val loaded = try {
            MusicRepository.loadSongs(applicationContext)
        } catch (e: Exception) {
            emptyList<Song>()
        }
        libraryCache = loaded
        return loaded
    }

    private fun browsable(id: String, title: String, subtitle: String? = null): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun playable(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId("song:${song.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        // -- Transport (VLC-aware) --
        // Media3 1.5 removed the per-command callbacks (onPlay, onPause,
        // onSeekTo, onSkipToNext/Previous, onSetPlaybackSpeed) and replaced
        // them with a single onPlayerCommandRequest gate: returning
        // RESULT_SUCCESS lets the session run the command on the ExoPlayer,
        // any other code blocks it. When VLC owns the current track, the
        // VLC action is performed here and the ExoPlayer command is blocked
        // (otherwise the paused ExoPlayer would start playing the same
        // track a second time, i.e. double audio).
        // Note: unlike the old callbacks, onPlayerCommandRequest receives
        // no seek position and no speed value, so seek-in-track and speed
        // from external controllers are handled by the app UI
        // (VlcEngine.seekTo / VlcEngine.setRate) instead.
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (!VlcEngine.active) {
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
            val exo = player
            when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> {
                    if (VlcEngine.isPlaying) VlcEngine.pause() else VlcEngine.resume()
                }
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    if (exo == null) return SessionResult.RESULT_ERROR_INVALID_STATE
                    if (exo.hasNextMediaItem()) {
                        exo.seekToNextMediaItem()
                        exo.prepare()
                        exo.play()
                    } else {
                        VlcEngine.stop()
                        exo.pause()
                    }
                }
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    if (exo == null) return SessionResult.RESULT_ERROR_INVALID_STATE
                    if (exo.currentPosition > 3000) {
                        VlcEngine.seekTo(0)
                    } else if (exo.hasPreviousMediaItem()) {
                        exo.seekToPreviousMediaItem()
                        exo.prepare()
                        exo.play()
                    } else {
                        VlcEngine.stop()
                    }
                }
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SET_SPEED_AND_PITCH -> {
                    // No position/speed is passed to this callback in
                    // Media3 1.5: the value is applied by the app UI
                    // instead. Block the ExoPlayer command so the two
                    // engines don't desync.
                }
                else -> return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
            return SessionResult.RESULT_ERROR_INVALID_STATE
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mutableListOf<MediaItem>()
            val songs = library()
            for (item in mediaItems) {
                val id = item.mediaId
                when {
                    id.startsWith("song:") -> {
                        val songId = id.removePrefix("song:").toLongOrNull()
                        songs.firstOrNull { it.id == songId }?.let { resolved.add(playable(it)) }
                    }
                    id == ALL_ID -> resolved.addAll(songs.map { playable(it) })
                    id.startsWith("album:") -> {
                        val name = id.removePrefix("album:")
                        resolved.addAll(songs.filter { it.album == name }.map { playable(it) })
                    }
                    id.startsWith("artist:") -> {
                        val name = id.removePrefix("artist:")
                        resolved.addAll(songs.filter { it.artist == name }.map { playable(it) })
                    }
                    id.startsWith("playlist:") -> {
                        val pid = id.removePrefix("playlist:")
                        val playlist = PlaylistStore.load(applicationContext)
                            .firstOrNull { it.id == pid }
                        playlist?.songIds?.mapNotNull { songId ->
                            songs.firstOrNull { it.id == songId }
                        }?.forEach { resolved.add(playable(it)) }
                    }
                    id.startsWith("folder:") -> {
                        val name = id.removePrefix("folder:")
                        resolved.addAll(songs.filter { it.folder == name }.map { playable(it) })
                    }
                    else -> resolved.add(item)
                }
            }
            return Futures.immediateFuture(resolved)
        }

        // -- Browsing --
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browsable(
                ROOT_ID,
                getString(R.string.app_name),
                getString(R.string.library_root_subtitle)
            )
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val songs = library()
            val items: List<MediaItem> = when {
                parentId == ROOT_ID -> listOf(
                    browsable(ALL_ID, getString(R.string.tab_songs), "${songs.size}"),
                    browsable(ALBUMS_ID, getString(R.string.tab_albums)),
                    browsable(ARTISTS_ID, getString(R.string.tab_artists)),
                    browsable(PLAYLISTS_ID, getString(R.string.tab_playlists)),
                    browsable(FOLDERS_ID, getString(R.string.tab_folders))
                )
                parentId == ALL_ID -> songs.map { playable(it) }
                parentId == ALBUMS_ID -> MusicRepository.albumsOf(songs).map { album ->
                    browsable("album:${album.name}", album.name, "${album.songs.size}")
                }
                parentId.startsWith("album:") -> {
                    val name = parentId.removePrefix("album:")
                    songs.filter { it.album == name }.map { playable(it) }
                }
                parentId == ARTISTS_ID -> MusicRepository.artistsOf(songs).map { artist ->
                    browsable("artist:${artist.name}", artist.name, "${artist.songs.size}")
                }
                parentId.startsWith("artist:") -> {
                    val name = parentId.removePrefix("artist:")
                    songs.filter { it.artist == name }.map { playable(it) }
                }
                parentId == PLAYLISTS_ID -> PlaylistStore.load(applicationContext).map { p ->
                    browsable("playlist:${p.id}", p.name, "${p.songIds.size}")
                }
                parentId.startsWith("playlist:") -> {
                    val pid = parentId.removePrefix("playlist:")
                    val playlist = PlaylistStore.load(applicationContext)
                        .firstOrNull { it.id == pid }
                    playlist?.songIds?.mapNotNull { songId ->
                        songs.firstOrNull { it.id == songId }
                    }?.map { playable(it) } ?: emptyList()
                }
                parentId == FOLDERS_ID -> MusicRepository.foldersOf(songs).map { folder ->
                    browsable("folder:${folder.name}", folder.name, "${folder.songs.size}")
                }
                parentId.startsWith("folder:") -> {
                    val name = parentId.removePrefix("folder:")
                    songs.filter { it.folder == name }.map { playable(it) }
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = ((page + 1) * pageSize).coerceAtMost(items.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val songs = library()
            val item: MediaItem? = when {
                mediaId.startsWith("song:") -> {
                    val id = mediaId.removePrefix("song:").toLongOrNull()
                    songs.firstOrNull { it.id == id }?.let { playable(it) }
                }
                mediaId == ROOT_ID -> browsable(ROOT_ID, getString(R.string.app_name))
                else -> null
            }
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "Bad value"))
                )
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val songs = library()
            val results = songs.filter { MusicRepository.matchesQuery(it, MusicRepository.normalizeForSearch(query)) }.map { playable(it) }
            val from = (page * pageSize).coerceAtMost(results.size)
            val to = ((page + 1) * pageSize).coerceAtMost(results.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(results.subList(from, to)), params)
            )
        }
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                    channel.description = getString(R.string.notification_channel_desc)
                    manager.createNotificationChannel(channel)
                }
                // v1.0.13: alarm notifications — high importance so the
                // full-screen ringing intent fires even from Doze / lock.
                if (manager.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
                    val alarmChannel = NotificationChannel(
                        ALARM_CHANNEL_ID,
                        getString(R.string.alarm_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    alarmChannel.description = getString(R.string.alarm_channel_desc)
                    manager.createNotificationChannel(alarmChannel)
                }
            }
        } catch (e: Exception) {
            // Notification channel creation must never break the service.
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification X button: stop playback, dismiss the notification
        // and stop the foreground service (see closeFromNotification).
        if (intent?.action == ACTION_CLOSE_NOTIFICATION) {
            closeFromNotification()
            return super.onStartCommand(intent, flags, startId)
        }
        // Notification buttons (v1.0.14): handled here, directly on the
        // service's own player + VLC engine, so they work even when the
        // app UI is destroyed (the activity's MediaController is dead
        // then — that was the silent failure). VlcActionReceiver stays
        // as a fallback for notifications posted by older versions.
        when (intent?.action) {
            ACTION_NOTIFICATION_PREV -> {
                handleNotificationPrevious()
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_NOTIFICATION_TOGGLE -> {
                handleNotificationToggle()
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_NOTIFICATION_NEXT -> {
                handleNotificationNext()
                return super.onStartCommand(intent, flags, startId)
            }
        }
        // Alarm stop (from the alarm notification or the ringing screen).
        if (intent?.action == AlarmStore.ACTION_STOP_ALARM) {
            stopAlarm()
            return super.onStartCommand(intent, flags, startId)
        }
        // Music alarm: show the alarm notification and start the chosen
        // song, the volume rising gradually when "Volume progressif" is on.
        if (intent?.action == AlarmStore.ACTION_ALARM) {
            val songId = intent.getLongExtra(AlarmStore.EXTRA_SONG_ID, -1L)
            val alarmId = intent.getStringExtra(AlarmStore.EXTRA_ALARM_ID) ?: ""
            val alarmName = intent.getStringExtra(AlarmStore.EXTRA_ALARM_NAME)
                ?: getString(R.string.alarm_default_name)
            val songTitle = intent.getStringExtra(AlarmStore.EXTRA_SONG_TITLE) ?: ""
            val fadeIn = intent.getBooleanExtra(AlarmStore.EXTRA_FADE_IN, true)
            if (songId >= 0) handleAlarm(songId, alarmId, alarmName, songTitle, fadeIn)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Notification X button (v1.0.10): stop playback, dismiss the
     * notification and stop the foreground service — cleanly.
     *
     * How it works, and why each step is needed (verified against the
     * Media3 1.5.1 source):
     * - The notification is ongoing, so it can never be swiped away;
     *   only this path removes it.
     * - `clearMediaItems()` empties the player timeline. Media3's
     *   notification manager removes the notification itself when the
     *   timeline is empty (`shouldShowNotification` returns false) —
     *   no race with a later re-post. (Simply pausing or stopping the
     *   player would NOT remove it: with a non-empty timeline the
     *   manager re-posts the notification on the next update.)
     * - `stopSelf()` ends the service once the app unbinds; while the
     *   app is open and bound, the service simply lingers with no
     *   notification and no playback.
     * - Nothing is erased: the queue itself lives in
     *   PlayerConnection.songs and in the saved resume state
     *   (PlaybackStateStore, written while playing), so pressing play
     *   again in the app restores the queue (see PlayerConnection.play)
     *   and a fresh launch restores queue + position. The in-app
     *   updater and the error reporter run outside this service.
     */
    private fun closeFromNotification() {
        try {
            VlcEngine.stop()
        } catch (e: Exception) {
        }
        PlayerConnection.playOnceThenNext = false
        try {
            player?.pause()
        } catch (e: Exception) {
        }
        try {
            PlayerConnection.lastQueueIndex =
                player?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
            player?.clearMediaItems()
        } catch (e: Exception) {
        }
        try {
            stopSelf()
        } catch (e: Exception) {
        }
    }

    // ---- Notification buttons, handled by the service itself (v1.0.14) ----
    // Same logic as PlayerConnection.next()/previous()/togglePlayPause()
    // (including the 3000 ms rule and VLC-track handling), but driven by
    // the service's own player instead of the activity's MediaController —
    // so the buttons work when the app UI is closed or destroyed. A
    // failure that cannot be executed is reported (TYPE_ERROR) instead
    // of failing silently, as it did before.

    private fun handleNotificationToggle() {
        try {
            if (VlcEngine.active) {
                VlcEngine.toggle()
                return
            }
            val exo = player
            if (exo == null || exo.mediaItemCount == 0) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Notification play/pause ignored: no player or empty queue"
                )
                return
            }
            if (exo.isPlaying) {
                exo.pause()
            } else {
                // After a stop the player is idle (and after the last
                // track it is ended): play() alone does nothing then.
                when (exo.playbackState) {
                    Player.STATE_IDLE -> exo.prepare()
                    Player.STATE_ENDED -> exo.seekTo(0)
                }
                exo.play()
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Notification play/pause failed: ${e.message}",
                e
            )
        }
    }

    private fun handleNotificationNext() {
        try {
            val exo = player
            if (exo == null) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Notification next ignored: no player"
                )
                return
            }
            if (VlcEngine.active) {
                // Move the session queue; the service routes the next
                // track (Media3 or VLC) on the transition.
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                } else {
                    VlcEngine.stop()
                    exo.pause()
                }
                return
            }
            if (exo.mediaItemCount == 0) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Notification next ignored: empty queue"
                )
                return
            }
            if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else exo.seekTo(0)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Notification next failed: ${e.message}",
                e
            )
        }
    }

    private fun handleNotificationPrevious() {
        try {
            val exo = player
            if (exo == null) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Notification previous ignored: no player"
                )
                return
            }
            // The 3000 ms rule: restart the track when past the start,
            // otherwise go to the previous track.
            val position = if (VlcEngine.active) VlcEngine.positionMs else exo.currentPosition
            if (position > 3000) {
                if (VlcEngine.active) VlcEngine.seekTo(0) else exo.seekTo(0)
                return
            }
            if (VlcEngine.active) {
                if (exo.hasPreviousMediaItem()) {
                    exo.seekToPreviousMediaItem()
                    exo.prepare()
                    exo.play()
                } else {
                    VlcEngine.stop()
                }
                return
            }
            if (exo.mediaItemCount == 0) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Notification previous ignored: empty queue"
                )
                return
            }
            exo.seekToPreviousMediaItem()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Notification previous failed: ${e.message}",
                e
            )
        }
    }

    /** Alarm: post the alarm notification, then play the chosen song
     * (library from that song on). With "Volume progressif" the volume
     * starts silent and ramps to the user's level over 30 seconds;
     * without it the song starts at full volume at once. */
    private fun handleAlarm(
        songId: Long,
        alarmId: String,
        alarmName: String,
        songTitle: String,
        fadeIn: Boolean
    ) {
        try {
            val library = MusicRepository.loadSongs(applicationContext)
            val index = library.indexOfFirst { it.id == songId }
            if (index < 0) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Alarm song not found in library (id=$songId)"
                )
                return
            }
            postAlarmNotification(alarmId, alarmName, songTitle)
            val exo = player ?: return
            PlayerConnection.songs = library
            exo.setMediaItems(library.map { PlayerConnection.toMediaItem(it) }, index, 0)
            exo.prepare()
            val targetVolume = VolumeBoost.playerVolume()
            val targetPercent = PlaybackStateStore.loadVolume(applicationContext)
            if (!fadeIn) {
                exo.volume = targetVolume
                VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(targetPercent, 1f))
                SystemVolumeSync.applySliderPercent(targetPercent)
                exo.play()
                return
            }
            exo.volume = 0f
            VlcEngine.setVolume(0)
            exo.play()
            var step = 0
            val ramp = object : Runnable {
                override fun run() {
                    step++
                    val f = (step / 30f).coerceIn(0f, 1f)
                    exo.volume = targetVolume * f
                    VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(targetPercent, f))
                    // v1.0.8: the alarm ramp raises the DEVICE volume too.
                    SystemVolumeSync.applySliderPercent((targetPercent * f).toInt())
                    if (step < 30 && exo.isPlaying) {
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        alarmRamp = null
                        exo.volume = targetVolume
                        VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(targetPercent, 1f))
                        SystemVolumeSync.applySliderPercent(targetPercent)
                    }
                }
            }
            alarmRamp = ramp
            mainHandler.postDelayed(ramp, 1000)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Alarm start failed: ${e.message}",
                e
            )
        }
    }

    /** The alarm notification: high priority, full-screen ringing screen,
     * and a clear Stop action. Posted when the alarm fires, even if the
     * app was closed. */
    private fun postAlarmNotification(alarmId: String, alarmName: String, songTitle: String) {
        try {
            val ringingIntent = Intent(this, AlarmRingingActivity::class.java).apply {
                putExtra(AlarmStore.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmStore.EXTRA_ALARM_NAME, alarmName)
                putExtra(AlarmStore.EXTRA_SONG_TITLE, songTitle)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val ringingPending = PendingIntent.getActivity(
                this, ALARM_NOTIFICATION_ID, ringingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(this, PlaybackService::class.java).apply {
                action = AlarmStore.ACTION_STOP_ALARM
            }
            val stopPending = PendingIntent.getService(
                this, ALARM_NOTIFICATION_ID, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(alarmName)
                .setContentText(songTitle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(ringingPending)
                .setFullScreenIntent(ringingPending, true)
                .addAction(0, getString(R.string.alarm_stop), stopPending)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ALARM_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Alarm notification failed: ${e.message}",
                e
            )
        }
    }

    /** Stop a ringing alarm: halt the ramp, pause playback, remove the
     * alarm notification, tell the ringing screen to close. */
    private fun stopAlarm() {
        try {
            alarmRamp?.let { mainHandler.removeCallbacks(it) }
            alarmRamp = null
        } catch (e: Exception) {
        }
        try {
            VlcEngine.stop()
        } catch (e: Exception) {
        }
        try {
            player?.pause()
        } catch (e: Exception) {
        }
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ALARM_NOTIFICATION_ID)
        } catch (e: Exception) {
        }
        try {
            sendBroadcast(Intent(AlarmStore.ACTION_ALARM_STOPPED))
        } catch (e: Exception) {
        }
        try {
            stopSelf()
        } catch (e: Exception) {
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Media3 1.5: the notification provider is set on the service
        // (not on the session Builder anymore) and must be set before
        // onCreate() returns; it survives session rebuilds.
        setMediaNotificationProvider(ZenoNotificationProvider(this))
        VlcEngine.init(applicationContext)
        VlcEngine.listener = vlcListener
        // v1.0.3: the service must never die at startup. If the spectrum
        // processor / custom renderers factory fails, fall back to a plain
        // player; if even that fails, report it and leave the service
        // without a player (controllers fail gracefully, the app still opens).
        try {
            buildPlayerWithSpectrum()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "PlaybackService init failed, using plain player: ${e.message}",
                e
            )
            try {
                player = ExoPlayer.Builder(this).build()
                // Last-resort plain player: no spectrum, no gain stage
                // (the boost cannot work on this player).
                gainStageActive = false
                player?.addListener(playerListener)
                session = MediaLibrarySession.Builder(this, player!!, sessionCallback)
                    .build()
            } catch (e2: Exception) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "PlaybackService plain player init failed: ${e2.message}",
                    e2
                )
            }
        }
        // v1.0.9: attach EQ/Reverb/Visualizer explicitly — Player.Listener
        // .onAudioSessionIdChanged NEVER fires in Media3 1.5.1 unless the
        // app calls player.setAudioSessionId() itself (the id is generated
        // silently at construction), so relying on that callback left the
        // equalizer, bass boost and reverb attached to nothing: the UI
        // values changed, the sound never did.
        attachAudioEffects()
        // Restore the user's volume and speed.
        try {
            val volume = PlaybackStateStore.loadVolume(applicationContext)
            VolumeBoost.setVolumePercent(volume)
            // v1.0.8: the restored slider value drives the device volume too.
            SystemVolumeSync.applySliderPercent(volume)
            // v1.0.9: balance/mono are live fields read by GainAudioProcessor
            // — restore them at service start, or they silently reset to
            // centre/stereo whenever the service restarts.
            VolumeBoost.balance = PlaybackStateStore.loadBalance(applicationContext)
            VolumeBoost.mono = PlaybackStateStore.loadMono(applicationContext)
            player?.volume = VolumeBoost.playerVolume()
            val speed = PlaybackStateStore.loadSpeed(applicationContext)
            if (speed != 1.0f) {
                player?.setPlaybackParameters(PlaybackParameters(speed))
            }
            val repeat = PlaybackStateStore.loadRepeatMode(applicationContext)
            if (repeat != Player.REPEAT_MODE_OFF) player?.repeatMode = repeat
            player?.shuffleModeEnabled = PlaybackStateStore.loadShuffle(applicationContext)
        } catch (e: Exception) {
        }
        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (e: Exception) {
        }
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            refreshEqOutput()
        } catch (e: Exception) {
        }
        lastTickMs = System.currentTimeMillis()
        mainHandler.post(ticker)
    }

    /**
     * The custom renderers factory (v1.0.14): the audio chain runs through
     * the spectrum processor (live waves) and then the gain processor
     * (200% boost, ReplayGain, balance, mono). Shared by the normal build
     * and the fallback rebuild — the fallback used to build a plain player
     * WITHOUT this factory, so after any playback error the 200% boost
     * was stored (boostGain = 2.0) but never applied: the slider showed
     * 200% while the sound stayed at 100%.
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf<androidx.media3.common.audio.AudioProcessor>(
                            spectrumProcessor, gainProcessor
                        )
                    )
                    .build()
            }
        }
    }

    private fun buildPlayerWithSpectrum() {
        // Media3 1.4.1 has no ExoPlayer.Builder.setAudioProcessors: the audio
        // processors are attached through a DefaultAudioSink built by a custom
        // DefaultRenderersFactory.
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(buildRenderersFactory())
            .build()
        gainStageActive = true
        // Media3 1.4.1 has no ExoPlayer.Builder.setAudioOffloadPreferences:
        // offload is disabled through the track selection parameters, so the
        // PCM always flows through the in-app spectrum processor.
        val exoPlayer = player!!
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    )
                    .build()
            )
            .build()
        player?.addListener(playerListener)
        session = MediaLibrarySession.Builder(this, player!!, sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return session
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        saveResumeState()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
        }
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
        }
        player?.removeListener(playerListener)
        releaseVisualizer()
        VlcEngine.release()
        session?.release()
        player?.release()
        session = null
        player = null
        gainStageActive = null
        super.onDestroy()
    }

    /**
     * Attaches the equalizer, bass boost, reverb and system visualizer to
     * the player's audio session. Must be called EXPLICITLY after every
     * player build/rebuild: Player.Listener.onAudioSessionIdChanged never
     * fires in Media3 1.5.1 (the session id is generated silently at
     * construction and the event only fires from the explicit
     * setAudioSessionId() setter, which the app never calls) — verified
     * against the Media3 1.5.1 source. The listener callback is kept as a
     * backup only.
     */
    private fun attachAudioEffects() {
        try {
            val exo = player ?: return
            val sessionId = exo.audioSessionId
            if (sessionId == 0 ||
                sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
            ) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "EQ attach skipped: no audio session yet",
                    null
                )
                return
            }
            setupVisualizer(sessionId)
            EqManager.attach(applicationContext, sessionId)
            ReverbManager.attach(applicationContext, sessionId)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "EQ attach failed: ${e.message}",
                e
            )
        }
    }

    private fun setupVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) return
        releaseVisualizer()
        try {
            val viz = Visualizer(audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        viz: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int
                    ) {
                        // Waveform not used; FFT bars only.
                    }

                    override fun onFftDataCapture(viz: Visualizer, fft: ByteArray, samplingRate: Int) {
                        // Backup only: VisualizerHub drops this while the
                        // SpectrumAudioProcessor is publishing.
                        VisualizerHub.publishSystem(fft.copyOf())
                    }
                },
                Visualizer.getMaxCaptureRate(), false, true
            )
            viz.enabled = true
            visualizer = viz
        } catch (e: Exception) {
            // Visualizer not available on this device; the in-app processor
            // is the primary source, so playback continues with waves anyway.
            visualizer = null
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.release()
        } catch (e: Exception) {
            // Ignore release errors.
        }
        visualizer = null
    }
}
