package com.musicplayer.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.musicplayer.app.data.Song
import com.musicplayer.app.report.ErrorReporter
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * libVLC fallback engine (secondary player).
 *
 * Media3/ExoPlayer stays the primary engine. When a song uses a format
 * Media3 cannot decode (APE, DSD, WavPack, MPC, tracker, WMA, … — see
 * [Song.VLC_ONLY_EXTENSIONS]) or when Media3 fails on a track, playback
 * switches to VLC automatically, with no user action.
 *
 * VLC plays from a local file, so the track is copied once from its
 * MediaStore content URI into the app cache (reused when the file size
 * matches). libVLC cannot feed the real spectrum processor, so the
 * visualizer shows its animated simulated waves while a VLC track plays
 * (see VisualizerView).
 *
 * All state changes are reported to [listener] (set by PlaybackService)
 * so the service can update the notification and the UI state.
 */
object VlcEngine {

    interface Listener {
        fun onVlcStarted(song: Song)
        fun onVlcPaused(song: Song)
        fun onVlcStopped()
        fun onVlcCompleted(song: Song)
        fun onVlcError(song: Song, message: String)
    }

    var listener: Listener? = null

    private var appContext: Context? = null
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var preparedFile: File? = null

    /** v1.0.16 (M9): generation of the last play() — stop()/release()
     * bump it so a pending file copy cannot start a released player. */
    @Volatile
    private var playGeneration = 0

    companion object {
        /** v1.0.16 (M9): the VLC cache used to grow forever (one file
         * per song, never deleted). Keep at most 20 files / 200 MB. */
        private const val VLC_CACHE_MAX_FILES = 20
        private const val VLC_CACHE_MAX_BYTES = 200L * 1024L * 1024L
    }

    @Volatile
    var active: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Lazily creates the LibVLC instance (audio only, no video). */
    fun init(context: Context) {
        if (libVlc != null) return
        appContext = context.applicationContext
        try {
            val options = ArrayList<String>().apply {
                add("--no-video")
                add("--no-stats")
                add("--audio-resampler=soxr")
            }
            libVlc = LibVLC(appContext, options)
            mediaPlayer = MediaPlayer(libVlc).apply {
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.EndReached -> {
                            val song = currentSong
                            if (song != null) {
                                mainHandler.post { listener?.onVlcCompleted(song) }
                            }
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            val song = currentSong
                            if (song != null) {
                                mainHandler.post {
                                    listener?.onVlcError(song, "VLC playback error")
                                }
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            val song = currentSong
                            if (song != null) {
                                mainHandler.post { listener?.onVlcStarted(song) }
                            }
                        }
                        MediaPlayer.Event.Paused -> {
                            val song = currentSong
                            if (song != null) {
                                mainHandler.post { listener?.onVlcPaused(song) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "VLC engine init failed: ${e.message}",
                e
            )
            libVlc = null
            mediaPlayer = null
        }
    }

    /** True when VLC currently has a track loaded (playing or paused). */
    fun hasTrack(): Boolean = active && currentSong != null

    fun currentSong(): Song? = currentSong

    val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    /** Position in ms (-1 when unknown). */
    val positionMs: Long
        get() = try {
            mediaPlayer?.time ?: -1L
        } catch (e: Exception) {
            -1L
        }

    /** Duration in ms (-1 when unknown). */
    val durationMs: Long
        get() = try {
            val len = mediaPlayer?.length ?: -1L
            if (len > 0) len else currentSong?.durationMs ?: -1L
        } catch (e: Exception) {
            currentSong?.durationMs ?: -1L
        }

    /**
     * Plays [song]: copies its content to a cache file (reused when the
     * cached copy has the same size), then starts VLC on that file.
     * Runs the copy on a background thread; playback starts when ready.
     */
    fun play(song: Song) {
        val ctx = appContext ?: return
        init(ctx)
        val player = mediaPlayer ?: run {
            listener?.onVlcError(song, "VLC engine unavailable")
            return
        }
        currentSong = song
        active = true
        // v1.0.16 (M9): generation token — stop()/release() bump it, so
        // a slow file copy that finishes after the user stopped (or
        // after the engine was released) can no longer start playback
        // on a released player.
        val generation = ++playGeneration
        Thread {
            try {
                val file = prepareFile(ctx, song)
                if (file == null) {
                    mainHandler.post { listener?.onVlcError(song, "Cannot read file") }
                    return@Thread
                }
                mainHandler.post {
                    if (generation != playGeneration) return@post
                    if (mediaPlayer !== player || libVlc == null) return@post
                    if (currentSong?.id != song.id) return@post
                    try {
                        val lib = libVlc ?: return@post
                        val media = Media(lib, file.absolutePath)
                        // Mono mode (VLC has no per-channel balance): the
                        // stereo-mode option mixes both channels together.
                        // It applies from the next VLC track on.
                        if (VolumeBoost.mono) media.addOption(":stereo-mode=mono")
                        player.media = media
                        media.release()
                        player.play()
                    } catch (e: Exception) {
                        ErrorReporter.log(
                            ErrorReporter.TYPE_PLAYBACK,
                            "VLC play failed: ${e.message}",
                            e
                        )
                        listener?.onVlcError(song, e.message ?: "VLC error")
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_PLAYBACK,
                    "VLC prepare failed: ${e.message}",
                    e
                )
                mainHandler.post { listener?.onVlcError(song, e.message ?: "VLC error") }
            }
        }.start()
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun resume() {
        try {
            mediaPlayer?.play()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun toggle() {
        if (isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.time = positionMs.coerceAtLeast(0L)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    /** Volume 0..200 (100 = normal level). */
    fun setVolume(percent: Int) {
        try {
            mediaPlayer?.setVolume(percent.coerceIn(0, 200))
        } catch (e: Exception) {
            // Ignore.
        }
    }

    /** Playback speed 0.5..2.0. */
    fun setRate(speed: Float) {
        try {
            mediaPlayer?.setRate(speed.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            // Ignore.
        }
    }

    /** Applies the equalizer bands (5 band levels in dB) to VLC's 10-band EQ.
     * Bass boost (0..100) raises the two lowest VLC bands (60 / 170 Hz).
     * Call this AFTER the media starts playing too (see
     * PlaybackService.vlcListener.onVlcStarted): an equalizer set before
     * any input exists can be dropped by libVLC. */
    fun applyEqualizer(bandsDb: FloatArray?, bass0to100: Int = 0) {
        try {
            val player = mediaPlayer ?: return
            if (bandsDb == null) {
                player.setEqualizer(null)
                return
            }
            val eq = org.videolan.libvlc.MediaPlayer.Equalizer.create()
            // VLC bands (Hz): 60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000.
            // Map our 5 bands onto them (nearest neighbours, smooth blend).
            val map = intArrayOf(0, 2, 4, 6, 8)
            for (i in bandsDb.indices) {
                if (i >= map.size) break
                eq.setAmp(map[i], bandsDb[i])
            }
            // Bass boost: raise the two lowest bands (60 Hz fully, 170 Hz half).
            val bassDb = (bass0to100.coerceIn(0, 100) / 100f) * 6f
            if (bassDb > 0f) {
                val low0 = (bandsDb.getOrElse(0) { 0f } + bassDb).coerceIn(-20f, 20f)
                eq.setAmp(0, low0)
                eq.setAmp(1, (bassDb * 0.5f).coerceIn(-20f, 20f))
            }
            player.setEqualizer(eq)
        } catch (e: Exception) {
            // EQ on VLC is best-effort; never break playback over it.
        }
    }

    fun stop() {
        // v1.0.16 (M9): invalidate any pending play() copy so it cannot
        // start playback after the stop.
        playGeneration++
        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
            // Ignore.
        }
        active = false
        currentSong = null
        mainHandler.post { listener?.onVlcStopped() }
    }

    fun release() {
        playGeneration++
        try {
            stop()
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            libVlc?.release()
        } catch (e: Exception) {
            // Ignore.
        }
        mediaPlayer = null
        libVlc = null
    }

    /**
     * Returns a local file for [song]: reuses the cached copy when it
     * exists with the same size, otherwise copies the content URI.
     * Prunes the cache afterwards (v1.0.16 M9: it used to grow forever).
     */
    private fun prepareFile(context: Context, song: Song): File? {
        val dir = File(context.cacheDir, "vlc").apply { if (!exists()) mkdirs() }
        val ext = song.extension.ifBlank { "bin" }
        val file = File(dir, "${song.id}.$ext")
        if (file.exists() && song.sizeBytes > 0 && file.length() == song.sizeBytes) {
            preparedFile = file
            pruneCache(dir, file)
            return file
        }
        return try {
            context.contentResolver.openInputStream(song.uri)?.use { input ->
                FileOutputStream(file).use { out ->
                    input.copyTo(out)
                }
            } ?: return null
            preparedFile = file
            pruneCache(dir, file)
            file
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_PLAYBACK,
                "VLC file copy failed: ${e.message}",
                e
            )
            null
        }
    }

    /** Deletes the oldest cache files until the cap is met (never the
     * file currently being played). Runs on the play() worker thread. */
    private fun pruneCache(dir: File, keep: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile && it != keep }
                ?.sortedByDescending { it.lastModified() } ?: return
            var totalBytes = keep.length()
            var kept = 0
            for (f in files) {
                totalBytes += f.length()
                kept++
                if (kept >= VLC_CACHE_MAX_FILES || totalBytes > VLC_CACHE_MAX_BYTES) {
                    try {
                        f.delete()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            // Cache pruning is best-effort; never break playback over it.
        }
    }
}
