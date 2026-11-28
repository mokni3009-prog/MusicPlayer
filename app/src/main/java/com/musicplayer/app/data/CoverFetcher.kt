package com.musicplayer.app.data

import android.content.Context
import com.musicplayer.app.report.ErrorReporter
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v1.0.16 — auto-download of missing covers (feature 4).
 *
 * When a song/album has no embedded cover and no MediaStore art, the
 * cover is fetched automatically from the internet and cached on the
 * device, so the next time the song is shown its cover is there.
 *
 * Sources (public, no key, no account):
 * 1. MusicBrainz (release search by artist + album) → Cover Art
 *    Archive front image (250 px).
 * 2. Fallback: iTunes Search (album search) → artwork URL.
 *
 * Privacy: only the artist + album (+ track title for the fallback)
 * are sent, to look up a cover image. No listening data, no account,
 * no identifier is sent anywhere. The user can switch it off in
 * settings ("Téléchargement auto des pochettes").
 */
object CoverFetcher {

    private const val PREFS = "zeno_covers"
    private const val KEY_AUTO_DOWNLOAD = "auto_download"

    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /** In-flight fetches, keyed by song id — one fetch per song. */
    private val fetching = mutableSetOf<Long>()

    fun isAutoDownloadEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_DOWNLOAD, true)
        } catch (e: Exception) {
            true
        }
    }

    fun setAutoDownloadEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
        } catch (e: Exception) {
        }
    }

    /** Disk cache file for a song's downloaded cover. */
    fun diskCacheFile(context: Context, songId: Long): File {
        val dir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }
        return File(dir, "$songId.jpg")
    }

    /** True when a downloaded cover is cached for this song. */
    fun hasCachedCover(context: Context, songId: Long): Boolean {
        return try {
            val file = diskCacheFile(context, songId)
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts a background fetch for [song]'s cover (no-op when disabled,
     * already cached, or already fetching). [onDone] runs on the calling
     * thread's handler... no — it runs on the fetch thread; the caller
     * must post to the main thread if it touches the UI.
     */
    fun maybeFetch(context: Context, song: Song, onDone: ((Boolean) -> Unit)? = null) {
        if (!isAutoDownloadEnabled(context)) return
        if (song.artist.isBlank() && song.album.isBlank()) return
        synchronized(fetching) {
            if (song.id in fetching) return
            fetching.add(song.id)
        }
        if (hasCachedCover(context, song.id)) {
            synchronized(fetching) { fetching.remove(song.id) }
            onDone?.invoke(true)
            return
        }
        val appContext = context.applicationContext
        Thread {
            var ok = false
            try {
                ok = fetchAndSave(appContext, song)
            } catch (e: Exception) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_NETWORK,
                    "Cover download failed for ${song.title}: ${e.message}"
                )
            } finally {
                synchronized(fetching) { fetching.remove(song.id) }
            }
            try {
                onDone?.invoke(ok)
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun fetchAndSave(context: Context, song: Song): Boolean {
        // 1. MusicBrainz → Cover Art Archive.
        val mbid = musicBrainzReleaseId(song)
        if (mbid != null) {
            val bytes = downloadBytes("https://coverartarchive.org/release/$mbid/front-250")
            if (bytes != null && saveCover(context, song.id, bytes)) return true
        }
        // 2. Fallback: iTunes album search.
        val artUrl = itunesArtworkUrl(song)
        if (artUrl != null) {
            val bytes = downloadBytes(artUrl)
            if (bytes != null && saveCover(context, song.id, bytes)) return true
        }
        return false
    }

    private fun saveCover(context: Context, songId: Long, bytes: ByteArray): Boolean {
        return try {
            val file = diskCacheFile(context, songId)
            file.writeBytes(bytes)
            file.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** MusicBrainz release MBID for an artist + album (null when unknown). */
    private fun musicBrainzReleaseId(song: Song): String? {
        var conn: HttpURLConnection? = null
        return try {
            val query = buildString {
                if (song.artist.isNotBlank()) append("artist:\"${song.artist}\" ")
                if (song.album.isNotBlank()) append("release:\"${song.album}\"")
            }.trim()
            if (query.isBlank()) return null
            val url = URL(
                "https://musicbrainz.org/ws/2/release/?query=" +
                    URLEncoder.encode(query, "UTF-8") + "&fmt=json&limit=3"
            )
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ZenoPlay/1.0.16 (music player)")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONObject(body).optJSONArray("releases") ?: return null
            for (i in 0 until releases.length()) {
                val id = releases.getJSONObject(i).optString("id", "")
                if (id.isNotBlank()) return id
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** iTunes artwork URL (600 px) for an artist + album (null when unknown). */
    private fun itunesArtworkUrl(song: Song): String? {
        var conn: HttpURLConnection? = null
        return try {
            val term = listOf(song.artist, song.album, song.title)
                .filter { it.isNotBlank() }.joinToString(" ")
            if (term.isBlank()) return null
            val url = URL(
                "https://itunes.apple.com/search?term=" +
                    URLEncoder.encode(term, "UTF-8") +
                    "&entity=album&limit=5"
            )
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val art = results.getJSONObject(i).optString("artworkUrl100", "")
                if (art.isNotBlank()) {
                    return art.replace("100x100bb", "600x600bb")
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** Downloads a URL (following redirects) into memory, capped. */
    private fun downloadBytes(urlString: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ZenoPlay/1.0.16 (music player)")
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            if (conn.responseCode !in 200..299) return null
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return null
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }
}
