package com.musicplayer.app.data

import android.content.ContentUris
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import android.provider.MediaStore
import com.musicplayer.app.report.ErrorReporter
import java.text.Normalizer

object MusicRepository {

    fun loadSongs(context: Context): List<Song> {
        return try {
            loadSongsInner(context)
        } catch (e: Exception) {
            // Library scan must never crash the app: report it and show an empty library.
            ErrorReporter.log(
                ErrorReporter.TYPE_LIBRARY,
                "Library scan failed: ${e.message}",
                e
            )
            emptyList()
        }
    }

    private fun loadSongsInner(context: Context): List<Song> {
        val genreBySongId = loadGenres(context)
        return try {
            querySongs(context, genreBySongId, buildProjection())
        } catch (e: SQLiteException) {
            // A column may be missing on this device / Android version
            // (e.g. bucket_display_name before API 29): retry with only the
            // core columns so the library still loads instead of coming back
            // empty. If even that fails, loadSongs() reports it.
            querySongs(context, genreBySongId, MINIMAL_PROJECTION)
        }
    }

    /**
     * Columns for the library scan. bucket_display_name exists only from
     * API 29 (Android 10): asking for it on Android 9 and below makes the
     * whole query fail with "no such column: bucket_display_name" and the
     * library comes back empty — so it is requested only where it exists.
     * The folder name comes from that column when present, and from the
     * file path (_data) when it is not.
     */
    private fun buildProjection(): Array<String> {
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        }
        return projection.toTypedArray()
    }

    /** Core columns only — every one of them exists since API 1. */
    private val MINIMAL_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION
    )

    private fun querySongs(
        context: Context,
        genreBySongId: Map<Long, String>,
        projection: Array<String>
    ): List<Song> {
        val songs = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val bucketCol = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown title"
                val artist = cursor.getString(artistCol) ?: "Unknown artist"
                val album = cursor.getString(albumCol) ?: ""
                val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                val duration = cursor.getLong(durCol)
                val contentUri = ContentUris.withAppendedId(uri, id)
                val genre = genreBySongId[id] ?: ""
                val fileName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                // Parent folder (DOSSIERS tab): the MediaStore bucket name
                // when the column exists (API 29+), otherwise derived from
                // the file path (_data) — both ways in the same code.
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val folder = when {
                    bucketCol >= 0 -> (cursor.getString(bucketCol) ?: "").ifBlank { "Stockage" }
                    !dataPath.isNullOrBlank() -> {
                        val parent = dataPath.substringBeforeLast('/', "")
                        parent.substringAfterLast('/', "").ifBlank { "Stockage" }
                    }
                    else -> "Stockage"
                }
                val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                songs.add(
                    Song(
                        id, title, artist, album, duration, contentUri,
                        genre, Song.categoryForGenre(genre),
                        fileName, folder, albumId, dateAdded, sizeBytes
                    )
                )
            }
        }
        return songs
    }

    /** Returns audioId -> genre name, from the MediaStore genre tables. */
    private fun loadGenres(context: Context): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        val resolver = context.contentResolver
        val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        try {
            resolver.query(
                genresUri,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (cursor.moveToNext()) {
                    val genreId = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    try {
                        resolver.query(
                            membersUri,
                            arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                            null, null, null
                        )?.use { members ->
                            val audioCol = members.getColumnIndexOrThrow(
                                MediaStore.Audio.Genres.Members.AUDIO_ID
                            )
                            while (members.moveToNext()) {
                                val audioId = members.getLong(audioCol)
                                result.putIfAbsent(audioId, name)
                            }
                        }
                    } catch (e: Exception) {
                        // Genre members unreadable on this device; ignore.
                    }
                }
            }
        } catch (e: Exception) {
            // Genres unreadable on this device; every song stays uncategorized.
        }
        return result
    }

    /**
     * Bilingual search normalisation: Latin accents are stripped
     * ("Édith" matches "edith") while Arabic text is left untouched,
     * so the search finds titles in both languages.
     */
    fun normalizeForSearch(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(ch)
            }
        }
        return sb.toString().lowercase()
    }

    fun matchesQuery(song: Song, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        return normalizeForSearch(song.title).contains(normalizedQuery) ||
            normalizeForSearch(song.artist).contains(normalizedQuery) ||
            normalizeForSearch(song.album).contains(normalizedQuery) ||
            normalizeForSearch(song.fileName).contains(normalizedQuery)
    }

    /** Groups songs by album (album name + artist), for the ALBUMS tab. */
    data class AlbumGroup(val name: String, val artist: String, val songs: List<Song>)

    fun albumsOf(songs: List<Song>): List<AlbumGroup> {
        return songs.groupBy { it.album.ifBlank { "Album inconnu" } to it.artist }
            .map { (key, list) -> AlbumGroup(key.first, key.second, list) }
            .sortedBy { it.name.lowercase() }
    }

    /** Groups songs by artist, for the ARTISTES tab. */
    data class ArtistGroup(val name: String, val songs: List<Song>)

    fun artistsOf(songs: List<Song>): List<ArtistGroup> {
        return songs.groupBy { it.artist.ifBlank { "Artiste inconnu" } }
            .map { (name, list) -> ArtistGroup(name, list) }
            .sortedBy { it.name.lowercase() }
    }

    /** Groups songs by parent folder, for the DOSSIERS tab. */
    data class FolderGroup(val name: String, val songs: List<Song>)

    fun foldersOf(songs: List<Song>): List<FolderGroup> {
        return songs.groupBy { it.folder.ifBlank { "Stockage" } }
            .map { (name, list) -> FolderGroup(name, list) }
            .sortedBy { it.name.lowercase() }
    }
}
