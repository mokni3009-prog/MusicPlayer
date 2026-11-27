package com.musicplayer.app.player

import android.content.Context
import com.musicplayer.app.data.Song
import com.musicplayer.app.report.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Synchronised lyrics (LRC) for the full player screen.
 *
 * Sources, in order:
 * 1. A `.lrc` file next to the audio file (same name) — offline.
 * 2. The embedded ID3 lyrics, when Media3 reports them through
 *    [onMetadataLyrics] (USLT frames).
 * 3. Automatic online fetch from LRCLIB (free public API, no key) —
 *    only when the user enabled it in settings (opt-in, OFF by default),
 *    or when the user taps "search online". Results are cached offline
 *    so the lyrics stay available without internet afterwards.
 *
 * Tapping a lyric line seeks the player to that line's timestamp.
 */
object LyricsManager {

    data class LyricLine(val timeMs: Long, val text: String)
    data class Lyrics(val lines: List<LyricLine>, val plainText: String?, val instrumental: Boolean)

    private const val PREFS = "zeno_lyrics"
    private const val KEY_AUTO_FETCH = "auto_fetch"

    fun isAutoFetchEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_FETCH, false)
        } catch (e: Exception) {
            false
        }
    }

    fun setAutoFetchEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_FETCH, enabled).apply()
        } catch (e: Exception) {
        }
    }

    /** Embedded ID3 lyrics reported by Media3 (USLT), as plain text. */
    @Volatile
    var embeddedPlainLyrics: String? = null

    /** Parses LRC text ("[mm:ss.xx]line") into timed lines. */
    fun parseLrc(lrcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var offsetMs = 0L
        val timeRe = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        for (raw in lrcText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[offset:")) {
                offsetMs = line.removePrefix("[offset:").removeSuffix("]")
                    .toLongOrNull() ?: 0L
                continue
            }
            val matches = timeRe.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substringAfterLast(']').trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    1 -> fracRaw.toLongOrNull()?.times(100) ?: 0L
                    2 -> fracRaw.toLongOrNull()?.times(10) ?: 0L
                    3 -> fracRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                lines.add(LyricLine((min * 60 + sec) * 1000 + fracMs + offsetMs, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Loads the lyrics for [song]: local .lrc file first, then embedded
     * lyrics, then (only when allowed) the LRCLIB online fetch.
     * Returns null when nothing is available.
     */
    suspend fun loadLyrics(
        context: Context,
        song: Song,
        allowOnline: Boolean
    ): Lyrics? = withContext(Dispatchers.IO) {
        // 1. Local .lrc file next to the audio file.
        try {
            val lrcText = readLocalLrc(context, song)
            if (!lrcText.isNullOrBlank()) {
                val lines = parseLrc(lrcText)
                if (lines.isNotEmpty()) return@withContext Lyrics(lines, null, false)
            }
        } catch (e: Exception) {
            // Ignore; try the next source.
        }
        // 2. Cached LRCLIB result (offline).
        try {
            val cached = readCache(context, song)
            if (cached != null) return@withContext cached
        } catch (e: Exception) {
        }
        // 3. Embedded ID3 lyrics (plain text).
        val embedded = embeddedPlainLyrics
        if (!embedded.isNullOrBlank()) {
            return@withContext Lyrics(emptyList(), embedded, false)
        }
        // 4. Online fetch (opt-in only).
        if (!allowOnline) return@withContext null
        return@withContext fetchFromLrclib(context, song)
    }

    /** Forces an online LRCLIB fetch (user tapped "search online"). */
    suspend fun fetchOnline(context: Context, song: Song): Lyrics? =
        withContext(Dispatchers.IO) {
            fetchFromLrclib(context, song)
        }

    private fun readLocalLrc(context: Context, song: Song): String? {
        // The .lrc file lives next to the audio file; the path comes from
        // the MediaStore DATA column (may be unavailable on Android 10+).
        val path = try {
            var result: String? = null
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                "${android.provider.MediaStore.Audio.Media._ID} = ?",
                arrayOf(song.id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0)
                }
            }
            result
        } catch (e: Exception) {
            null
        } ?: return null
        if (path.isBlank()) return null
        val lrcPath = path.substringBeforeLast('.', "") + ".lrc"
        return try {
            File(lrcPath).takeIf { it.exists() }?.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFile(context: Context, song: Song): File {
        val dir = File(context.filesDir, "lyrics").apply { if (!exists()) mkdirs() }
        return File(dir, "${song.id}.json")
    }

    private fun readCache(context: Context, song: Song): Lyrics? {
        return try {
            val file = cacheFile(context, song)
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val instrumental = obj.optBoolean("instrumental", false)
            if (instrumental) return Lyrics(emptyList(), null, true)
            val synced = obj.optString("syncedLyrics", "")
            if (synced.isNotBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return Lyrics(lines, null, false)
            }
            val plain = obj.optString("plainLyrics", "")
            if (plain.isNotBlank()) return Lyrics(emptyList(), plain, false)
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromLrclib(context: Context, song: Song): Lyrics? {
        return try {
            val artist = URLEncoder.encode(song.artist, "UTF-8")
            val track = URLEncoder.encode(song.title, "UTF-8")
            val durationSec = (song.durationMs / 1000).toInt()
            val url = URL(
                "https://lrclib.net/api/get?artist_name=$artist&track_name=$track" +
                    "&duration=$durationSec"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ZenoPlay/1.0.4")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(body)
            // Cache the raw response for offline use.
            try {
                cacheFile(context, song).writeText(body)
            } catch (e: Exception) {
                // Cache failure is not fatal.
            }
            if (obj.optBoolean("instrumental", false)) {
                return Lyrics(emptyList(), null, true)
            }
            val synced = obj.optString("syncedLyrics", "")
            if (synced.isNotBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return Lyrics(lines, null, false)
            }
            val plain = obj.optString("plainLyrics", "")
            if (plain.isNotBlank()) return Lyrics(emptyList(), plain, false)
            null
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_NETWORK,
                "Lyrics fetch failed: ${e.message}",
                e
            )
            null
        }
    }
}
