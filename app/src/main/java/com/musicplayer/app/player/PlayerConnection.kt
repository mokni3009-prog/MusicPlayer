package com.musicplayer.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.musicplayer.app.data.Song

/**
 * Shared playback state between the activities and the MediaSession service.
 * Everything runs in the same process, so a simple singleton is enough.
 *
 * A play request made before the controller is connected is kept as a
 * pending play and executed as soon as the controller connects, so tapping
 * a song right after launch never gets lost.
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

    /** True when a play was requested but the controller is not connected yet. */
    fun hasPendingPlay(): Boolean = pendingPlay != null

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
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0)
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
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

    fun isPlaying(): Boolean = controller?.isPlaying == true
}
