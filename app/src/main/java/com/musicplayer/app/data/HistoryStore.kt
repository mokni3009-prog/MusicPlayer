package com.musicplayer.app.data

import android.content.Context
import com.musicplayer.app.report.ErrorReporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.0.16 — listening history (feature 6).
 *
 * Every track that starts playing is recorded here (song id + title +
 * artist + when it started), newest first, capped at 200 entries.
 * The file lives in the app's private storage — it never leaves the
 * device and is not sent anywhere. The HistoryActivity shows it and
 * lets the user resume any entry; the existing PlaybackStateStore
 * already resumes the very last track + position on launch.
 */
object HistoryStore {

    private const val FILE_NAME = "history.json"
    private const val MAX_ENTRIES = 200

    data class HistoryEntry(
        val songId: Long,
        val title: String,
        val artist: String,
        val playedAt: Long
    )

    private fun file(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    /** Records a track start (newest first; same song twice in a row
     * replaces the previous entry instead of duplicating it). */
    fun record(context: Context, song: Song) {
        try {
            val entries = load(context).toMutableList()
            entries.removeAll { it.songId == song.id }
            entries.add(
                0,
                HistoryEntry(song.id, song.title, song.artist, System.currentTimeMillis())
            )
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
            save(context, entries)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "History record failed: ${e.message}"
            )
        }
    }

    fun load(context: Context): List<HistoryEntry> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val result = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    HistoryEntry(
                        o.optLong("songId", -1L),
                        o.optString("title", ""),
                        o.optString("artist", ""),
                        o.optLong("playedAt", 0L)
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
        }
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("songId", e.songId)
            o.put("title", e.title)
            o.put("artist", e.artist)
            o.put("playedAt", e.playedAt)
            arr.put(o)
        }
        file(context).writeText(arr.toString())
    }
}
