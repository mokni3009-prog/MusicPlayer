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

    /** Max decoded cover dimension (px) — covers are shown at most
     * ~screen width; a 3000x3000 embedded picture decoded at full size
     * is ~36 MB of RAM per bitmap. */
    private const val MAX_DIMENSION = 512

    /** Max bytes read for one cover image (a malformed huge stream
     * must not OOM the app while decoding). */
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024

    // v1.0.16 (M7): the old cache counted ENTRIES (40), not bytes — 40
    // full-res covers could hold over a gigabyte of bitmaps and OOM.
    // Now it counts kilobytes (1/8 of the heap, capped at 16 MB) and
    // every cover is downsampled to at most 512 px.
    private val cache = object : LruCache<String, Bitmap>(maxCacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun maxCacheKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemoryKb / 8).coerceIn(4096, 16384)
    }

    // v1.0.16 (M7): the "no cover" set was unbounded — it grew by one
    // entry per coverless track forever. Cap it; worst case a coverless
    // track is re-probed once after the set is cleared.
    private const val MAX_EMPTY_KEYS = 500
    private val emptyKeys = LinkedHashSet<String>()

    private fun markEmpty(key: String) {
        if (emptyKeys.size >= MAX_EMPTY_KEYS) emptyKeys.clear()
        emptyKeys.add(key)
    }

    fun albumArtUri(albumId: Long): Uri =
        Uri.parse("content://media/external/audio/albumart/$albumId")

    /**
     * Returns the cover for an album (MediaStore art, else the embedded
     * picture of the first track of the album), or null when unknown.
     */
    fun loadAlbumCover(context: Context, albumId: Long, albumSongs: List<Song>): Bitmap? {
        val key = "album:$albumId"
        cache.get(key)?.let { return it }
        // v1.0.16 (F4): a downloaded cover may have landed since the
        // last probe — check the disk cache even for keys marked empty.
        val first = albumSongs.sortedBy { it.fileName }.firstOrNull()
        if (first != null) {
            loadDownloadedCover(context, first)?.let {
                cache.put(key, it)
                emptyKeys.remove(key)
                return it
            }
        }
        if (key in emptyKeys) return null
        // 1. MediaStore album art.
        val fromStore = loadBitmapFromUri(context, albumArtUri(albumId))
        if (fromStore != null) {
            cache.put(key, fromStore)
            return fromStore
        }
        // 2. Embedded picture of the first track of this album.
        if (first != null) {
            val embedded = loadEmbeddedPicture(context, first)
            if (embedded != null) {
                cache.put(key, embedded)
                return embedded
            }
            // 3. Trigger a download (v1.0.16 F4) — the cover is there
            // next time the album is shown.
            CoverFetcher.maybeFetch(context, first)
        }
        markEmpty(key)
        return null
    }

    /** Returns the embedded picture of one track, or null. */
    fun loadTrackCover(context: Context, song: Song): Bitmap? {
        val key = "track:${song.id}"
        cache.get(key)?.let { return it }
        // v1.0.16 (F4): a downloaded cover may have landed since the
        // last probe — check the disk cache even for keys marked empty.
        loadDownloadedCover(context, song)?.let {
            cache.put(key, it)
            emptyKeys.remove(key)
            return it
        }
        if (key in emptyKeys) return null
        val embedded = loadEmbeddedPicture(context, song)
        if (embedded != null) {
            cache.put(key, embedded)
            return embedded
        }
        // v1.0.16 (F4): trigger a download — the cover is there next
        // time the song is shown.
        CoverFetcher.maybeFetch(context, song)
        markEmpty(key)
        return null
    }

    /** A previously downloaded cover from the disk cache, if any. */
    private fun loadDownloadedCover(context: Context, song: Song): Bitmap? {
        return try {
            val file = CoverFetcher.diskCacheFile(context, song.id)
            if (!file.exists() || file.length() <= 0) return null
            decodeSampled(file.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes [bytes] downsampled to at most [MAX_DIMENSION] px on the
     * long side, in RGB_565 (covers have no alpha — halves memory vs
     * ARGB_8888). Returns null for unreadable data.
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val ratio = maxOf(bounds.outWidth, bounds.outHeight) / MAX_DIMENSION.toFloat()
            var sample = 1
            while (sample < ratio) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Bounded read (decodeStream cannot be downsampled
                // without a first bounds pass on the same bytes).
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) return null
                    out.write(buffer, 0, read)
                }
                decodeSampled(out.toByteArray())
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
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) null
            else decodeSampled(bytes)
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
