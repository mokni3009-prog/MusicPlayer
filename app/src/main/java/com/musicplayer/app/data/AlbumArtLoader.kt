package com.musicplayer.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.musicplayer.app.report.ErrorReporter

/**
 * Album cover loading with a strict rule: the cover of an album is the
 * cover EMBEDDED in the album's own files (or the MediaStore album art),
 * never a random track's cover applied to the whole album.
 *
 * Strategy per album: try the MediaStore album-art URI first; if it is
 * missing, take the embedded picture from the FIRST track of that album
 * (sorted by file name for determinism). Per-track covers use the
 * embedded picture of that track only.
 *
 * Results are cached (in-memory LRU) so lists scroll smoothly.
 */
object AlbumArtLoader {

    private const val CACHE_SIZE = 40
    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)
    private val emptyKeys = HashSet<String>()

    fun albumArtUri(albumId: Long): Uri =
        Uri.parse("content://media/external/audio/albumart/$albumId")

    /**
     * Returns the cover for an album (MediaStore art, else the embedded
     * picture of the first track of the album), or null when unknown.
     */
    fun loadAlbumCover(context: Context, albumId: Long, albumSongs: List<Song>): Bitmap? {
        val key = "album:$albumId"
        cache.get(key)?.let { return it }
        if (key in emptyKeys) return null
        // 1. MediaStore album art.
        val fromStore = loadBitmapFromUri(context, albumArtUri(albumId))
        if (fromStore != null) {
            cache.put(key, fromStore)
            return fromStore
        }
        // 2. Embedded picture of the first track of this album.
        val first = albumSongs.sortedBy { it.fileName }.firstOrNull()
        if (first != null) {
            val embedded = loadEmbeddedPicture(context, first)
            if (embedded != null) {
                cache.put(key, embedded)
                return embedded
            }
        }
        emptyKeys.add(key)
        return null
    }

    /** Returns the embedded picture of one track, or null. */
    fun loadTrackCover(context: Context, song: Song): Bitmap? {
        val key = "track:${song.id}"
        cache.get(key)?.let { return it }
        if (key in emptyKeys) return null
        val embedded = loadEmbeddedPicture(context, song)
        if (embedded != null) {
            cache.put(key, embedded)
            return embedded
        }
        emptyKeys.add(key)
        return null
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadEmbeddedPicture(context: Context, song: Song): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, song.uri)
            val bytes = retriever.embeddedPicture
            if (bytes == null || bytes.isEmpty()) null
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Clears the cache (after a cover edit, so the new art shows). */
    fun clear() {
        cache.evictAll()
        emptyKeys.clear()
    }
}
