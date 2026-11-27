package com.musicplayer.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.musicplayer.app.data.Song

/**
 * Shared playback state between the activities and the MediaSession service.
 * Everything runs in the same process, so a simple singleton is enough.
 */
object PlayerConnection {

    var controller: MediaController? = null
    var songs: List<Song> = emptyList()

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
        val c = controller ?: return
        this.songs = songs
        c.setMediaItems(songs.map { toMediaItem(it) }, index, 0)
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
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
}
