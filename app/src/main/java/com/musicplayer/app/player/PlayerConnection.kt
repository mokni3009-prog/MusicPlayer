package com.musicplayer.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.musicplayer.app.data.PerSongAudioStore
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.data.Song

/**
 * Shared playback state between the activities and the MediaSession service.
 * Everything runs in the same process, so a simple singleton is enough.
 *
 * A play request made before the controller is connected is kept as a
 * pending play and executed as soon as the controller connects, so tapping
 * a song right after launch never gets lost.
 *
 * Sleep timer, A–B repeat and "play once then next" state live here; the
 * service's ticker enforces them.
 */
object PlayerConnection {

    var controller: MediaController? = null
        set(value) {
            field = value
            if (value != null) {
                pendingPlay?.let { pending ->
                    pendingPlay = null
                    playSongs(pending.first, pending.second)
                }
            }
        }

    var songs: List<Song> = emptyList()

    /** (songs, index) waiting for the controller to connect. */
    private var pendingPlay: Pair<List<Song>, Int>? = null

    /**
     * Queue index remembered when the notification X button clears the
     * player timeline (see PlaybackService.closeFromNotification), so
     * play() can restore the queue at the same song.
     */
    var lastQueueIndex: Int = 0

    /** True when a play was requested but the controller is not connected yet. */
    fun hasPendingPlay(): Boolean = pendingPlay != null

    // ---- Sleep timer ------------------------------------------------------
    const val SLEEP_OFF = 0
    const val SLEEP_MINUTES = 1
    const val SLEEP_SONGS = 2

    @Volatile
    var sleepMode: Int = SLEEP_OFF
    @Volatile
    var sleepEndAtMs: Long = 0L
    @Volatile
    var sleepSongsRemaining: Int = 0
    /** 1.0 = full volume, 0.0 = silent (fade-out in progress). */
    @Volatile
    var sleepFadeFactor: Float = 1.0f

    fun setSleepMinutes(minutes: Int) {
        sleepMode = SLEEP_MINUTES
        sleepEndAtMs = System.currentTimeMillis() + minutes * 60_000L
        sleepSongsRemaining = 0
        sleepFadeFactor = 1.0f
    }

    fun setSleepAfterSongs(count: Int) {
        sleepMode = SLEEP_SONGS
        sleepSongsRemaining = count.coerceAtLeast(1)
        sleepEndAtMs = 0L
        sleepFadeFactor = 1.0f
    }

    fun cancelSleep() {
        sleepMode = SLEEP_OFF
        sleepEndAtMs = 0L
        sleepSongsRemaining = 0
        sleepFadeFactor = 1.0f
    }

    /** Remaining sleep time in ms (minutes mode), -1 when not applicable. */
    fun sleepRemainingMs(): Long {
        if (sleepMode != SLEEP_MINUTES) return -1L
        return (sleepEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // ---- A–B repeat -------------------------------------------------------
    @Volatile
    var abEnabled: Boolean = false
    @Volatile
    var abStartMs: Long = 0L
    @Volatile
    var abEndMs: Long = 0L

    fun setA(positionMs: Long) {
        abStartMs = positionMs
        abEnabled = abEndMs > abStartMs
    }

    fun setB(positionMs: Long) {
        abEndMs = positionMs
        abEnabled = abEndMs > abStartMs
    }

    fun clearAB() {
        abEnabled = false
        abStartMs = 0L
        abEndMs = 0L
    }

    /** "Play once then next": stop after the current track instead of repeating. */
    @Volatile
    var playOnceThenNext: Boolean = false

    // ---- Media items ------------------------------------------------------

    fun toMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
    }

    fun playSongs(songs: List<Song>, index: Int) {
        val c = controller
        if (c == null) {
            pendingPlay = songs to index
            return
        }
        this.songs = songs
        clearAB()
        c.setMediaItems(songs.map { toMediaItem(it) }, index, 0)
        c.prepare()
        c.play()
    }

    /** Plays a single song, replacing the queue. */
    fun playSong(song: Song, queue: List<Song>) {
        val index = queue.indexOf(song).takeIf { it >= 0 } ?: 0
        val list = if (queue.contains(song)) queue else listOf(song) + queue
        playSongs(list, list.indexOf(song).takeIf { it >= 0 } ?: index)
    }

    fun playNext(song: Song) {
        val c = controller ?: run {
            pendingPlay = listOf(song) to 0
            return
        }
        val item = toMediaItem(song)
        if (c.mediaItemCount == 0) {
            playSongs(listOf(song), 0)
            return
        }
        val nextIndex = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(nextIndex, item)
        val mutable = songs.toMutableList()
        mutable.add(nextIndex.coerceAtMost(mutable.size), song)
        songs = mutable
    }

    fun togglePlayPause() {
        if (VlcEngine.active) {
            VlcEngine.toggle()
            return
        }
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        if (VlcEngine.active) {
            VlcEngine.pause()
            return
        }
        controller?.pause()
    }

    fun play() {
        if (VlcEngine.active) {
            VlcEngine.resume()
            return
        }
        val c = controller ?: return
        if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
            // The queue was cleared by a notification close: restore it
            // at the remembered song (the saved queue in
            // PlaybackStateStore covers a fresh launch instead).
            val index = lastQueueIndex.coerceIn(0, songs.size - 1)
            c.setMediaItems(songs.map { toMediaItem(it) }, index, 0)
            c.prepare()
            c.play()
            return
        }
        // After a notification close the player is stopped (STATE_IDLE):
        // play() alone does nothing until the player is prepared again.
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun next() {
        val c = controller ?: return
        if (VlcEngine.active) {
            // Move the session queue; the service routes the next track
            // (Media3 or VLC) on the transition.
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            } else {
                VlcEngine.stop()
                c.pause()
            }
            return
        }
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0)
    }

    fun previous() {
        val c = controller ?: return
        val position = if (VlcEngine.active) VlcEngine.positionMs else c.currentPosition
        if (position > 3000) {
            seekTo(0)
            return
        }
        if (VlcEngine.active) {
            if (c.hasPreviousMediaItem()) {
                c.seekToPreviousMediaItem()
                c.prepare()
                c.play()
            } else {
                VlcEngine.stop()
            }
            return
        }
        c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        if (VlcEngine.active) {
            VlcEngine.seekTo(positionMs)
            return
        }
        controller?.seekTo(positionMs)
    }

    /** Volume 0..200 (100 = normal, above 100 = BOOST with limiter). */
    fun setVolumePercent(percent: Int, context: Context? = null) {
        VolumeBoost.setVolumePercent(percent)
        // v1.0.8: the 0..100% slider drives the DEVICE volume (STREAM_MUSIC);
        // above 100% the device stays at max and the boost is internal.
        SystemVolumeSync.applySliderPercent(percent)
        controller?.volume = VolumeBoost.playerVolume() * sleepFadeFactor
        VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(percent, sleepFadeFactor))
        context?.let { PlaybackStateStore.saveVolume(it, percent) }
    }

    // ---- Volume sync with the device (v1.0.8) ---------------------------

    /** UI listeners refreshed when the DEVICE volume changes externally. */
    private val volumeListeners = mutableSetOf<(Int) -> Unit>()

    fun addVolumeListener(listener: (Int) -> Unit) {
        volumeListeners.add(listener)
    }

    fun removeVolumeListener(listener: (Int) -> Unit) {
        volumeListeners.remove(listener)
    }

    /**
     * The device volume changed outside the app (hardware keys,
     * notification, Android Auto). Adopt it as the new slider value —
     * internal players + saved value + UI follow, but the system volume is
     * NOT written back (that would loop: observer -> set -> observer ...).
     * Runs on the main thread (called from SystemVolumeSync's observer).
     */
    fun onSystemVolumeChanged(percent: Int) {
        VolumeBoost.setVolumePercent(percent)
        controller?.volume = VolumeBoost.playerVolume() * sleepFadeFactor
        VlcEngine.setVolume(VolumeBoost.vlcVolumePercent(percent, sleepFadeFactor))
        SystemVolumeSync.context()?.let { PlaybackStateStore.saveVolume(it, percent) }
        volumeListeners.toList().forEach { it(percent) }
    }

    fun setSpeed(speed: Float, context: Context? = null) {
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        VlcEngine.setRate(speed)
        context?.let {
            PlaybackStateStore.saveSpeed(it, speed)
            // Every song remembers its own speed.
            currentSong()?.let { song -> PerSongAudioStore.saveSpeed(it, song.id, speed) }
        }
    }

    fun toggleShuffle(): Boolean {
        val c = controller ?: return false
        val newValue = !c.shuffleModeEnabled
        c.shuffleModeEnabled = newValue
        return newValue
    }

    fun cycleRepeat(): Int {
        val c = controller ?: return Player.REPEAT_MODE_OFF
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        return next
    }

    fun currentSong(): Song? {
        val c = controller ?: return null
        val index = c.currentMediaItemIndex
        if (index < 0) return null
        return songs.getOrNull(index)
            ?: c.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                songs.firstOrNull { it.id == id }
            }
    }

    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: -1

    fun isPlaying(): Boolean {
        if (VlcEngine.active) return VlcEngine.isPlaying
        return controller?.isPlaying == true
    }

    fun currentPositionMs(): Long {
        if (VlcEngine.active) {
            val pos = VlcEngine.positionMs
            if (pos >= 0) return pos
        }
        return controller?.currentPosition ?: 0L
    }

    fun durationMs(): Long {
        if (VlcEngine.active) {
            val dur = VlcEngine.durationMs
            if (dur > 0) return dur
        }
        return controller?.duration ?: currentSong()?.durationMs ?: 0L
    }

    /**
     * Restores the saved queue (from the last session) paused at the saved
     * position. Called once after the controller connects and the library
     * is loaded; does nothing when there is no saved queue.
     */
    fun restoreSavedQueue(context: Context, library: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val saved = PlaybackStateStore.loadQueue(context) ?: return
        val byId = library.associateBy { it.id }
        val queue = saved.songIds.mapNotNull { byId[it] }
        if (queue.isEmpty()) return
        val index = saved.index.coerceIn(0, queue.size - 1)
        songs = queue
        c.setMediaItems(queue.map { toMediaItem(it) }, index, saved.positionMs)
        c.prepare()
        // Restored paused: the user resumes with one tap.
    }

    // ---- Smart playlists --------------------------------------------------

    /** Most played songs (by play count), most first. */
    fun mostPlayed(context: Context, library: List<Song>, limit: Int = 50): List<Song> {
        val counts = PlaybackStateStore.playCounts(context)
        return library.filter { (counts[it.id] ?: 0) > 0 }
            .sortedByDescending { counts[it.id] ?: 0 }
            .take(limit)
    }

    /** Recently added songs (by MediaStore date added), newest first. */
    fun recentlyAdded(library: List<Song>, limit: Int = 50): List<Song> {
        return library.sortedByDescending { it.dateAdded }.take(limit)
    }

    /** Recently played songs (by last-played time), newest first. */
    fun recentlyPlayed(context: Context, library: List<Song>, limit: Int = 50): List<Song> {
        val last = PlaybackStateStore.lastPlayed(context)
        return library.filter { (last[it.id] ?: 0L) > 0L }
            .sortedByDescending { last[it.id] ?: 0L }
            .take(limit)
    }
}
