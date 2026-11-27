package com.musicplayer.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

object MusicRepository {

    fun loadSongs(context: Context): List<Song> {
        val genreBySongId = loadGenres(context)
        val songs = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown title"
                val artist = cursor.getString(artistCol) ?: "Unknown artist"
                val album = cursor.getString(albumCol) ?: ""
                val duration = cursor.getLong(durCol)
                val contentUri = ContentUris.withAppendedId(uri, id)
                val genre = genreBySongId[id] ?: ""
                songs.add(
                    Song(
                        id, title, artist, album, duration, contentUri,
                        genre, Song.categoryForGenre(genre)
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
}
