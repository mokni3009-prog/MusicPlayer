package com.musicplayer.app.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.musicplayer.app.report.ErrorReporter
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * Batch tag editing with jaudiotagger: title, artist, album, genre, year
 * and cover art, applied to one or several files at once.
 *
 * On Android 10+ the app needs write access to each file: the caller must
 * obtain it first (MediaStore.createWriteRequest on API 30+); below API 30
 * the file is written directly. The [filePath] for a song comes from the
 * MediaStore DATA column and may be empty on Android 10+ — in that case
 * the song is skipped and reported as failed (never silently dropped).
 */
object TagEditor {

    data class TagChanges(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: String? = null,
        /** JPEG/PNG bytes for a new cover, or null to leave it unchanged. */
        val coverBytes: ByteArray? = null,
        val removeCover: Boolean = false
    )

    data class TagEditResult(
        val updated: Int,
        val failed: List<String>
    )

    /** When the last tag edit succeeded (ms) — MainActivity reloads the
     * library on resume when this is newer than its last load, so the new
     * title shows without restarting the app. */
    @Volatile
    var lastSuccessfulEditMs: Long = 0L

    /** Returns the absolute file paths for the given song ids. */
    fun filePathsFor(context: Context, songIds: List<Long>): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        if (songIds.isEmpty()) return result
        try {
            val placeholders = songIds.joinToString(",") { "?" }
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media._ID} IN ($placeholders)",
                songIds.map { it.toString() }.toTypedArray(),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    if (idCol >= 0 && dataCol >= 0) {
                        result[cursor.getLong(idCol)] = cursor.getString(dataCol) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Tag editor path lookup failed: ${e.message}",
                e
            )
        }
        return result
    }

    /**
     * Applies [changes] to the files of [songs] (same values for all —
     * batch editing). Runs on the caller's thread; call from a background
     * thread.
     */
    fun applyTags(songs: List<Song>, paths: Map<Long, String>, changes: TagChanges): TagEditResult {
        var updated = 0
        val failed = mutableListOf<String>()
        for (song in songs) {
            val path = paths[song.id]
            if (path.isNullOrBlank()) {
                // v1.0.9: this used to fail SILENTLY (no report) — log it
                // so a future failure is visible on the server.
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Tag edit failed for ${song.title}: no file path (scoped storage)",
                    null
                )
                failed.add(song.title)
                continue
            }
            try {
                val file = File(path)
                if (!file.exists() || !file.canWrite()) {
                    // v1.0.9: this used to fail SILENTLY (no report). The
                    // usual cause on Android 9 is the missing
                    // WRITE_EXTERNAL_STORAGE permission — TagEditActivity
                    // requests it before saving now. On Android 10+ the
                    // file needs a MediaStore write request instead.
                    ErrorReporter.log(
                        ErrorReporter.TYPE_ERROR,
                        "Tag edit failed for ${song.title}: file not writable",
                        null
                    )
                    failed.add(song.title)
                    continue
                }
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tagOrCreateAndSetDefault
                changes.title?.let { tag.setField(FieldKey.TITLE, it) }
                changes.artist?.let { tag.setField(FieldKey.ARTIST, it) }
                changes.album?.let { tag.setField(FieldKey.ALBUM, it) }
                changes.genre?.let { tag.setField(FieldKey.GENRE, it) }
                // v1.0.9: YEAR must be a 4-digit year. Some files store a
                // full date instead (e.g. 20200125 = 2020-01-25), which
                // jaudiotagger rejects on ID3v2.3 tags — keep the year part.
                changes.year?.let { raw ->
                    val digits = raw.filter { it.isDigit() }
                    val year = if (digits.length >= 4) digits.take(4) else null
                    if (year != null) tag.setField(FieldKey.YEAR, year)
                }
                if (changes.removeCover) {
                    tag.deleteArtworkField()
                } else if (changes.coverBytes != null) {
                    val artwork = ArtworkFactory.createArtworkFromFile(
                        writeTempCover(changes.coverBytes)
                    )
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }
                audioFile.commit()
                lastSuccessfulEditMs = System.currentTimeMillis()
                updated++
            } catch (e: Exception) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Tag edit failed for ${song.title}: ${e.message}",
                    e
                )
                failed.add(song.title)
            }
        }
        return TagEditResult(updated, failed)
    }

    private fun writeTempCover(bytes: ByteArray): File {
        val tmp = File.createTempFile("cover", ".jpg")
        tmp.writeBytes(bytes)
        return tmp
    }
}
