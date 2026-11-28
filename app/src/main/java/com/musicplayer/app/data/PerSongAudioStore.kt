package com.musicplayer.app.data

import android.content.Context
import org.json.JSONObject

/**
 * Per-song audio settings — every song remembers its own equalizer
 * (bands + bass + preset name) and playback speed.
 *
 * Songs without a saved entry use the global settings (EqManager's
 * per-output settings and PlaybackStateStore's speed).
 */
object PerSongAudioStore {

    private const val PREFS = "zeno_per_song_audio"

    data class SongAudio(
        val bandsDb: FloatArray?,
        val bass: Int?,
        val preset: String?,
        val speed: Float?
    )

    fun get(context: Context, songId: Long): SongAudio? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(songId.toString(), null) ?: return null
            val obj = JSONObject(raw)
            val bands = if (obj.has("bands")) {
                val arr = obj.getJSONArray("bands")
                FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() }
            } else null
            SongAudio(
                bandsDb = bands,
                bass = if (obj.has("bass")) obj.optInt("bass") else null,
                preset = if (obj.has("preset")) obj.optString("preset") else null,
                speed = if (obj.has("speed")) obj.optDouble("speed").toFloat() else null
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Saves the EQ part (bands + bass + preset) for one song. */
    fun saveEq(
        context: Context,
        songId: Long,
        bandsDb: FloatArray,
        bass: Int,
        preset: String?
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = get(context, songId)
            val obj = JSONObject()
            val arr = org.json.JSONArray()
            for (b in bandsDb) arr.put(b.toDouble())
            obj.put("bands", arr)
            obj.put("bass", bass)
            if (preset != null) obj.put("preset", preset)
            if (existing?.speed != null) obj.put("speed", existing.speed.toDouble())
            prefs.edit().putString(songId.toString(), obj.toString()).apply()
        } catch (e: Exception) {
        }
    }

    /** Saves the playback speed for one song. */
    fun saveSpeed(context: Context, songId: Long, speed: Float) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = get(context, songId)
            val obj = JSONObject()
            if (existing?.bandsDb != null) {
                val arr = org.json.JSONArray()
                for (b in existing.bandsDb) arr.put(b.toDouble())
                obj.put("bands", arr)
            }
            if (existing?.bass != null) obj.put("bass", existing.bass)
            if (existing?.preset != null) obj.put("preset", existing.preset)
            obj.put("speed", speed.toDouble())
            prefs.edit().putString(songId.toString(), obj.toString()).apply()
        } catch (e: Exception) {
        }
    }
}
