package com.musicplayer.app.player

import android.content.Context
import com.musicplayer.app.BuildConfig
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
        private set

    /** Called by the player's onMetadata listener (v1.0.16 M4): stores
     * the current track's embedded lyrics, or null to clear them on a
     * track change. Before v1.0.16 this field was never written — the
     * embedded-lyrics path was dead code and embedded lyrics never
     * displayed. */
    fun onMetadataLyrics(lyrics: String?) {
        embeddedPlainLyrics = lyrics?.trim()?.ifBlank { null }
    }

    /**
     * Extracts embedded lyrics from a Media3 [Metadata] block:
     * - ID3 USLT — Media3 does NOT decode USLT into its own entry; it
     *   arrives as a raw [BinaryFrame] (encoding byte + language +
     *   description + text), parsed here.
     * - Vorbis comment LYRICS (FLAC / OGG) as a [VorbisComment].
     * Returns null when the block holds no lyrics.
     */
    fun extractFromMetadata(metadata: androidx.media3.common.Metadata): String? {
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            when (entry) {
                is androidx.media3.extractor.metadata.id3.TextInformationFrame -> {
                    if (entry.id == "USLT" && entry.value.isNotBlank()) return entry.value
                }
                is androidx.media3.extractor.metadata.id3.BinaryFrame -> {
                    if (entry.id == "USLT") {
                        parseUslt(entry.data)?.let { return it }
                    }
                }
                is androidx.media3.extractor.metadata.vorbis.VorbisComment -> {
                    if (entry.key.equals("LYRICS", ignoreCase = true) &&
                        entry.value.isNotBlank()
                    ) return entry.value
                }
            }
        }
        return null
    }

    /** Parses a raw USLT frame body: encoding + 3-byte language +
     * null-terminated description + lyrics text. */
    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt()
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val wideTerminator = encoding == 1 || encoding == 2
        var pos = 4 // skip the 3-byte language code
        if (wideTerminator) {
            while (pos + 1 < data.size &&
                !(data[pos] == 0.toByte() && data[pos + 1] == 0.toByte())
            ) pos += 2
            pos += 2
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos += 1
        }
        if (pos >= data.size) return null
        return String(data, pos, data.size - pos, charset).trim().ifBlank { null }
    }

    /**
     * Reads embedded lyrics straight from the file's tags with
     * jaudiotagger (USLT for MP3, LYRICS for Vorbis, ©lyr for MP4) —
     * the fallback when the player's metadata has not arrived yet.
     * Reads through a temp copy of the content Uri (scoped storage).
     */
    fun readEmbeddedLyrics(context: Context, song: Song): String? {
        val tmp = com.musicplayer.app.data.TagEditor.copyUriToTempFile(
            context, song.uri, ".${song.extension.ifBlank { "bin" }}"
        ) ?: return null
        return try {
            val tag = org.jaudiotagger.audio.AudioFileIO.read(tmp).tag ?: return null
            tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS).ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

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
        // 3. Embedded lyrics (plain text): live from the player's
        // metadata (onMetadata), else read from the file's tags
        // directly — v1.0.16 (M4): the metadata field used to be
        // dead code, so embedded lyrics never displayed.
        val embedded = embeddedPlainLyrics
            ?: readEmbeddedLyrics(context, song)
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
        // v1.0.16 (M3): the .lrc file lives next to the audio file, but
        // on Android 10+ there is no direct file access to it — the old
        // code read it through the MediaStore DATA column, which is
        // empty on 10+. Two paths that still work:
        // 1. file:// Uris (picked files): read the sibling directly.
        // 2. MediaStore.Files: find the .lrc by display name and read
        //    it through its content Uri.
        // (On Android 13+ a store .lrc may be unreadable — .lrc is not
        // a media type — and the online/embedded path is used instead.)
        val stem = song.fileName.substringBeforeLast('.', song.fileName)
        if (stem.isBlank()) return null
        val lrcName = "$stem.lrc"
        try {
            if (song.uri.scheme == "file") {
                val path = song.uri.path ?: return null
                val lrcPath = path.substringBeforeLast('.', "") + ".lrc"
                val file = File(lrcPath)
                if (file.exists()) return file.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
        }
        return try {
            var result: String? = null
            context.contentResolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(android.provider.MediaStore.Files.FileColumns._ID),
                "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
                arrayOf(lrcName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = android.net.Uri.withAppendedPath(
                        android.provider.MediaStore.Files.getContentUri("external"),
                        id.toString()
                    )
                    result = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
            }
            result
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
        // v1.0.16 (m6): the connection is disconnected in a finally, and
        // the input stream is closed via use {} — the old code leaked
        // both on every fetch.
        var conn: HttpURLConnection? = null
        return try {
            val artist = URLEncoder.encode(song.artist, "UTF-8")
            val track = URLEncoder.encode(song.title, "UTF-8")
            val durationSec = (song.durationMs / 1000).toInt()
            val url = URL(
                "https://lrclib.net/api/get?artist_name=$artist&track_name=$track" +
                    "&duration=$durationSec"
            )
            conn = url.openConnection() as HttpURLConnection
            // v1.0.16 (m6): the User-Agent was hardcoded to 1.0.4.
            conn.setRequestProperty("User-Agent", "ZenoPlay/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
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
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }
}
