package com.musicplayer.app.data

import android.content.Context
import android.net.Uri
import com.musicplayer.app.report.ErrorReporter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local backup + M3U import/export.
 *
 * Backup (JSON): playlists (with songs), favorites, saved queues, playback
 * state and settings — everything needed to restore the app after a
 * reinstall. Written to a document the user picks (SAF), so it survives
 * uninstalls; restoring matches songs by MediaStore id, falling back to
 * title + artist when the id changed.
 *
 * M3U: export any playlist as a standard .m3u file (absolute file paths
 * from the MediaStore DATA column when available, otherwise the song
 * title). Import reads #EXTM3U/#EXTINF files and matches the tracks in
 * the library by title and artist.
 */
object BackupManager {

    /** One playlist in a backup: resolved songs (ids + title/artist fallback). */
    data class BackupPlaylist(val name: String, val songs: List<Song>)

    data class BackupData(
        val playlists: List<BackupPlaylist>,
        val favorites: Set<Long>,
        val queues: List<QueueStore.SavedQueue>,
        val volumePercent: Int,
        val speed: Float
    )

    fun buildBackup(context: Context, library: List<Song>): BackupData {
        val byId = library.associateBy { it.id }
        val playlists = PlaylistStore.load(context).map { p ->
            BackupPlaylist(
                p.name,
                p.songIds.mapNotNull { byId[it] }
            )
        }
        val favorites = FavoritesStore.getFavorites(context)
        val queues = QueueStore.load(context)
        return BackupData(
            playlists,
            favorites,
            queues,
            PlaybackStateStore.loadVolume(context),
            PlaybackStateStore.loadSpeed(context)
        )
    }

    fun serialize(backup: BackupData): String {
        val obj = JSONObject()
        obj.put("app", "Zeno Play")
        obj.put("version", 1)
        val playlists = JSONArray()
        for (p in backup.playlists) {
            val po = JSONObject()
            po.put("name", p.name)
            val ids = JSONArray()
            for (s in p.songs) ids.put(s.id)
            po.put("songIds", ids)
            // Keep title/artist as a fallback if ids change after restore.
            val songsMeta = JSONArray()
            for (s in p.songs) {
                val so = JSONObject()
                so.put("title", s.title)
                so.put("artist", s.artist)
                songsMeta.put(so)
            }
            po.put("songs", songsMeta)
            playlists.put(po)
        }
        obj.put("playlists", playlists)
        val favs = JSONArray()
        for (id in backup.favorites) favs.put(id)
        obj.put("favorites", favs)
        val queues = JSONArray()
        for (q in backup.queues) {
            val qo = JSONObject()
            qo.put("name", q.name)
            val ids = JSONArray()
            for (id in q.songIds) ids.put(id)
            qo.put("songIds", ids)
            qo.put("index", q.index)
            qo.put("positionMs", q.positionMs)
            queues.put(qo)
        }
        obj.put("queues", queues)
        obj.put("volumePercent", backup.volumePercent)
        obj.put("speed", backup.speed.toDouble())
        return obj.toString()
    }

    fun restore(context: Context, jsonText: String, library: List<Song>): RestoreResult {
        var playlistsRestored = 0
        var songsMatched = 0
        try {
            val obj = JSONObject(jsonText)
            if (obj.optString("app", "") != "Zeno Play") {
                return RestoreResult(false, 0, 0, "Invalid backup file")
            }
            val playlistsArr = obj.optJSONArray("playlists") ?: JSONArray()
            val current = PlaylistStore.load(context).toMutableList()
            for (i in 0 until playlistsArr.length()) {
                val po = playlistsArr.getJSONObject(i)
                val name = po.optString("name", "Playlist")
                val idsArr = po.optJSONArray("songIds") ?: JSONArray()
                val ids = (0 until idsArr.length()).map { idsArr.getLong(it) }
                val songs = resolveSongs(library, ids, po.optJSONArray("songs"))
                val existing = current.firstOrNull { it.name == name }
                val playlist = Playlist(
                    existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name,
                    songs.map { it.id }.toMutableList()
                )
                if (existing != null) {
                    current[current.indexOf(existing)] = playlist
                } else {
                    current.add(playlist)
                }
                playlistsRestored++
                songsMatched += songs.size
            }
            PlaylistStore.save(context, current)
            // Favorites.
            val favsArr = obj.optJSONArray("favorites") ?: JSONArray()
            val favIds = (0 until favsArr.length()).map { favsArr.getLong(it) }
            val favSongs = favIds.mapNotNull { id -> library.firstOrNull { it.id == id } }
            FavoritesStore.setFavorites(context, favSongs)
            // Queues.
            val queuesArr = obj.optJSONArray("queues") ?: JSONArray()
            val queues = mutableListOf<QueueStore.SavedQueue>()
            for (i in 0 until queuesArr.length()) {
                val qo = queuesArr.getJSONObject(i)
                val idsArr = qo.optJSONArray("songIds") ?: JSONArray()
                val ids = (0 until idsArr.length()).map { idsArr.getLong(it) }
                    .filter { id -> library.any { it.id == id } }
                queues.add(
                    QueueStore.SavedQueue(
                        java.util.UUID.randomUUID().toString(),
                        qo.optString("name", "File"),
                        ids,
                        qo.optInt("index", 0),
                        qo.optLong("positionMs", 0L),
                        System.currentTimeMillis()
                    )
                )
            }
            if (queues.isNotEmpty()) QueueStore.save(context, queues)
            // Settings.
            PlaybackStateStore.saveVolume(context, obj.optInt("volumePercent", 80))
            PlaybackStateStore.saveSpeed(context, obj.optDouble("speed", 1.0).toFloat())
            return RestoreResult(true, playlistsRestored, songsMatched, null)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Backup restore failed: ${e.message}",
                e
            )
            return RestoreResult(false, playlistsRestored, songsMatched, e.message)
        }
    }

    data class RestoreResult(
        val ok: Boolean,
        val playlists: Int,
        val songs: Int,
        val error: String?
    )

    private fun resolveSongs(
        library: List<Song>,
        ids: List<Long>,
        meta: JSONArray?
    ): List<Song> {
        val byId = library.associateBy { it.id }
        val result = mutableListOf<Song>()
        for (id in ids) {
            byId[id]?.let { result.add(it) }
        }
        // Fall back to title + artist matching for ids that no longer exist.
        if (meta != null && result.size < ids.size) {
            for (i in 0 until meta.length()) {
                val mo = meta.getJSONObject(i)
                val title = mo.optString("title", "")
                val artist = mo.optString("artist", "")
                if (title.isBlank()) continue
                val match = library.firstOrNull {
                    it.title.equals(title, ignoreCase = true) &&
                        it.artist.equals(artist, ignoreCase = true)
                }
                if (match != null && match !in result) result.add(match)
            }
        }
        return result
    }

    /** Writes an M3U file for [playlist] (with its resolved [songs]) to the given document URI. */
    fun exportM3u(context: Context, playlist: Playlist, songs: List<Song>, uri: Uri): Boolean {
        return try {
            val lines = StringBuilder()
            lines.append("#EXTM3U\n")
            lines.append("#PLAYLIST:${playlist.name}\n")
            val paths = filePaths(context, songs.map { it.id })
            for (song in songs) {
                lines.append("#EXTINF:${song.durationMs / 1000},${song.artist} - ${song.title}\n")
                val path = paths[song.id]
                lines.append(if (!path.isNullOrBlank()) path else "${song.title}.${song.extension}")
                lines.append("\n")
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(lines.toString().toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "M3U export failed: ${e.message}",
                e
            )
            false
        }
    }

    /** Reads an M3U file and returns the matching library songs. */
    fun importM3u(context: Context, uri: Uri, library: List<Song>): List<Song> {
        val matched = mutableListOf<Song>()
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() } ?: return emptyList()
            val byPath = mutableMapOf<String, Song>()
            val paths = filePaths(context, library.map { it.id })
            for ((id, path) in paths) {
                if (path.isNotBlank()) byPath[path] = library.first { it.id == id }
            }
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                // Match by exact file path first, then by file name.
                val song = byPath[trimmed]
                    ?: byPath.entries.firstOrNull { it.key.endsWith("/" + trimmed) }?.value
                    ?: library.firstOrNull {
                        trimmed.substringAfterLast('/').equals(it.fileName, ignoreCase = true)
                    }
                if (song != null && song !in matched) matched.add(song)
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "M3U import failed: ${e.message}",
                e
            )
        }
        return matched
    }

    private fun filePaths(context: Context, ids: List<Long>): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        if (ids.isEmpty()) return result
        try {
            val placeholders = ids.joinToString(",") { "?" }
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    android.provider.MediaStore.Audio.Media._ID,
                    android.provider.MediaStore.Audio.Media.DATA
                ),
                "${android.provider.MediaStore.Audio.Media._ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media._ID)
                val dataCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    if (idCol >= 0 && dataCol >= 0) {
                        result[cursor.getLong(idCol)] = cursor.getString(dataCol) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            // DATA column is restricted on newer Android; export falls back
            // to the file name.
        }
        return result
    }
}
