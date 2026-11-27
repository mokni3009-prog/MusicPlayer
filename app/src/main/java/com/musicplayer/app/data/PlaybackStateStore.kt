package com.musicplayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.musicplayer.app.report.ErrorReporter

/**
 * Persistent playback state: resume position/queue, play counts and
 * listening time (for the smart playlists and the statistics screen).
 *
 * - Resume: the current queue (song ids), index and position are saved
 *   whenever the track or the position changes; on the next launch the
 *   library is restored paused at the same place.
 * - Play counts / last played: updated when a track finishes or is
 *   skipped after 30s of listening.
 * - Listening time: accumulated seconds of actual playback.
 */
object PlaybackStateStore {

    private const val PREFS = "zeno_playback_state"
    private const val KEY_QUEUE_IDS = "queue_ids"
    private const val KEY_QUEUE_INDEX = "queue_index"
    private const val KEY_QUEUE_POSITION = "queue_position"
    private const val KEY_VOLUME = "volume_percent"
    private const val KEY_SPEED = "speed"
    private const val KEY_PLAY_COUNTS = "play_counts"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_LISTEN_SECONDS = "listen_seconds"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE = "shuffle"

    data class SavedQueue(
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long
    )

    fun saveQueue(context: Context, songIds: List<Long>, index: Int, positionMs: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_QUEUE_IDS, JSONArray(songIds).toString())
                .putInt(KEY_QUEUE_INDEX, index)
                .putLong(KEY_QUEUE_POSITION, positionMs)
                .apply()
        } catch (e: Exception) {
            // Persistence is best-effort; never break playback.
        }
    }

    fun loadQueue(context: Context): SavedQueue? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE_IDS, null) ?: return null
            val arr = JSONArray(raw)
            val ids = (0 until arr.length()).map { arr.getLong(it) }
            if (ids.isEmpty()) return null
            SavedQueue(
                ids,
                prefs.getInt(KEY_QUEUE_INDEX, 0),
                prefs.getLong(KEY_QUEUE_POSITION, 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveVolume(context: Context, percent: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VOLUME, percent.coerceIn(0, 200)).apply()
        } catch (e: Exception) {
        }
    }

    fun loadVolume(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VOLUME, 80)
        } catch (e: Exception) {
            80
        }
    }

    fun saveSpeed(context: Context, speed: Float) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_SPEED, speed).apply()
        } catch (e: Exception) {
        }
    }

    fun loadSpeed(context: Context): Float {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_SPEED, 1.0f)
        } catch (e: Exception) {
            1.0f
        }
    }

    fun saveRepeatShuffle(context: Context, repeatMode: Int, shuffle: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_REPEAT_MODE, repeatMode).putBoolean(KEY_SHUFFLE, shuffle).apply()
        } catch (e: Exception) {
        }
    }

    fun loadRepeatMode(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_REPEAT_MODE, 0)
        } catch (e: Exception) {
            0
        }
    }

    fun loadShuffle(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHUFFLE, false)
        } catch (e: Exception) {
            false
        }
    }

    /** Call when a track was listened to (finished or >30s): counts one play. */
    fun recordPlay(context: Context, songId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val counts = readCounts(prefs.getString(KEY_PLAY_COUNTS, null))
            counts[songId] = (counts[songId] ?: 0) + 1
            val last = readLastPlayed(prefs.getString(KEY_LAST_PLAYED, null))
            last[songId] = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_PLAY_COUNTS, JSONObject(counts.mapKeys { it.key.toString() }).toString())
                .putString(KEY_LAST_PLAYED, JSONObject(last.mapKeys { it.key.toString() }).toString())
                .apply()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Play count save failed: ${e.message}",
                e
            )
        }
    }

    fun playCount(context: Context, songId: Long): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            readCounts(prefs.getString(KEY_PLAY_COUNTS, null))[songId] ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun playCounts(context: Context): Map<Long, Int> {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            readCounts(prefs.getString(KEY_PLAY_COUNTS, null))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun lastPlayed(context: Context): Map<Long, Long> {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            readLastPlayed(prefs.getString(KEY_LAST_PLAYED, null))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Adds [seconds] of actual listening time (accumulated while playing). */
    fun addListenSeconds(context: Context, seconds: Long) {
        if (seconds <= 0) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val total = prefs.getLong(KEY_LISTEN_SECONDS, 0L) + seconds
            prefs.edit().putLong(KEY_LISTEN_SECONDS, total).apply()
        } catch (e: Exception) {
        }
    }

    fun totalListenSeconds(context: Context): Long {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LISTEN_SECONDS, 0L)
        } catch (e: Exception) {
            0L
        }
    }

    /** Clears play counts, last-played times and total listening time. */
    fun clearStats(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PLAY_COUNTS)
                .remove(KEY_LAST_PLAYED)
                .remove(KEY_LISTEN_SECONDS)
                .apply()
        } catch (e: Exception) {
        }
    }

    private fun readCounts(raw: String?): MutableMap<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        if (raw.isNullOrBlank()) return out
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k.toLongOrNull() ?: continue] = obj.optInt(k, 0)
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun readLastPlayed(raw: String?): MutableMap<Long, Long> {
        val out = mutableMapOf<Long, Long>()
        if (raw.isNullOrBlank()) return out
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k.toLongOrNull() ?: continue] = obj.optLong(k, 0L)
            }
        } catch (e: Exception) {
        }
        return out
    }
}
