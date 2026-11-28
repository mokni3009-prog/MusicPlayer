package com.musicplayer.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.musicplayer.app.report.ErrorReporter
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Batch tag editing with jaudiotagger: title, artist, album, genre, year
 * and cover art, applied to one or several files at once.
 *
 * v1.0.16 (M3): the editor used to work on java.io.File paths from the
 * MediaStore DATA column — on Android 10+ that column is empty or the
 * file is not writable, so tag editing (and the ringtone cutter and
 * ReplayGain, which used the same lookup) silently failed. The flow is
 * now content-Uri based: the file is copied to a temp file, edited
 * there with jaudiotagger, and written back through the content
 * resolver. The caller must obtain write access first on API 30+
 * (MediaStore.createWriteRequest) — when the write-back throws a
 * SecurityException the result reports [TagEditResult.needsPermission]
 * so the caller can request the grant and retry.
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
        val failed: List<String>,
        /** True when at least one write failed with a SecurityException
         * (missing write grant) — the caller can request it and retry. */
        val needsPermission: Boolean = false,
        /** Uris of the songs that failed with a SecurityException. */
        val failedUris: List<Uri> = emptyList()
    )

    /** When the last tag edit succeeded (ms) — MainActivity reloads the
     * library on resume when this is newer than its last load, so the new
     * title shows without restarting the app. */
    @Volatile
    var lastSuccessfulEditMs: Long = 0L

    /**
     * Copies a content Uri to a temp file in the app cache, for libraries
     * that only work on java.io.File (jaudiotagger, MediaExtractor…).
     * Returns null when the content cannot be read. The caller MUST
     * delete the returned file when done.
     */
    fun copyUriToTempFile(context: Context, uri: Uri, suffix: String): File? {
        return try {
            val tmp = File.createTempFile("zeno-", suffix, context.cacheDir)
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                } != null
            } catch (e: Exception) {
                false
            }
            if (!copied) {
                tmp.delete()
                null
            } else {
                tmp
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Reads the current tags of one song (for pre-fill). Null when unreadable. */
    fun readTags(context: Context, song: Song): TagChanges? {
        val tmp = copyUriToTempFile(context, song.uri, ".${song.extension.ifBlank { "bin" }}")
            ?: return null
        return try {
            val tag = AudioFileIO.read(tmp).tag ?: return null
            TagChanges(
                title = tag.getFirst(FieldKey.TITLE).ifBlank { null },
                artist = tag.getFirst(FieldKey.ARTIST).ifBlank { null },
                album = tag.getFirst(FieldKey.ALBUM).ifBlank { null },
                genre = tag.getFirst(FieldKey.GENRE).ifBlank { null },
                year = tag.getFirst(FieldKey.YEAR).ifBlank { null }
            )
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Applies [changes] to the files of [songs] (same values for all —
     * batch editing). Runs on the caller's thread; call from a background
     * thread.
     */
    fun applyTags(context: Context, songs: List<Song>, changes: TagChanges): TagEditResult {
        var updated = 0
        val failed = mutableListOf<String>()
        var needsPermission = false
        val failedUris = mutableListOf<Uri>()
        for (song in songs) {
            val tmp = copyUriToTempFile(
                context, song.uri, ".${song.extension.ifBlank { "bin" }}"
            )
            if (tmp == null) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Tag edit failed for ${song.title}: cannot read file",
                    null
                )
                failed.add(song.title)
                continue
            }
            try {
                val audioFile = AudioFileIO.read(tmp)
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
                    val coverTmp = writeTempCover(changes.coverBytes)
                    try {
                        val artwork = ArtworkFactory.createArtworkFromFile(coverTmp)
                        tag.deleteArtworkField()
                        tag.setField(artwork)
                    } finally {
                        // v1.0.16 (m10): the temp cover file was never
                        // deleted — one stray file per cover edit.
                        coverTmp.delete()
                    }
                }
                audioFile.commit()
                // Write the edited file back through the content resolver
                // (works on Android 10+ with a write grant, and on older
                // versions with WRITE_EXTERNAL_STORAGE).
                try {
                    context.contentResolver.openOutputStream(song.uri, "wt")?.use { out ->
                        tmp.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("output stream failed")
                } catch (e: SecurityException) {
                    // Missing write grant — report it so the caller can
                    // request it (MediaStore.createWriteRequest) and retry.
                    needsPermission = true
                    failedUris.add(song.uri)
                    ErrorReporter.log(
                        ErrorReporter.TYPE_ERROR,
                        "Tag edit failed for ${song.title}: no write access",
                        e
                    )
                    failed.add(song.title)
                    continue
                }
                // Update the MediaStore row so the library shows the new
                // tags without waiting for a rescan.
                try {
                    val values = ContentValues().apply {
                        changes.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
                        changes.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                        changes.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    }
                    if (values.size() > 0) {
                        context.contentResolver.update(song.uri, values, null, null)
                    }
                } catch (e: Exception) {
                    // Best-effort: the file itself was already updated.
                }
                lastSuccessfulEditMs = System.currentTimeMillis()
                updated++
            } catch (e: Exception) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_ERROR,
                    "Tag edit failed for ${song.title}: ${e.message}",
                    e
                )
                failed.add(song.title)
            } finally {
                tmp.delete()
            }
        }
        return TagEditResult(updated, failed, needsPermission, failedUris)
    }

    private fun writeTempCover(bytes: ByteArray): File {
        val tmp = File.createTempFile("cover", ".jpg")
        tmp.writeBytes(bytes)
        return tmp
    }
}
