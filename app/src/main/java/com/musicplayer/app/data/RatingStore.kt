package com.musicplayer.app.data

import android.content.Context

/**
 * v1.0.16 — star ratings (feature 5): 1–5 stars per song, stored in
 * SharedPreferences. Ratings are included in the backup (see
 * BackupManager) and drive the "4★ and up" smart playlist.
 */
object RatingStore {

    private const val PREFS = "zeno_ratings"

    /** 0 = not rated. */
    fun getRating(context: Context, songId: Long): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(songId.toString(), 0).coerceIn(0, 5)
        } catch (e: Exception) {
            0
        }
    }

    /** Sets the rating (1–5); 0 clears it. */
    fun setRating(context: Context, songId: Long, stars: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(songId.toString(), stars.coerceIn(0, 5)).apply()
        } catch (e: Exception) {
        }
    }

    fun getAll(context: Context): Map<Long, Int> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
                .mapNotNull { (k, v) ->
                    val id = k.toLongOrNull() ?: return@mapNotNull null
                    val stars = (v as? Int) ?: return@mapNotNull null
                    if (stars in 1..5) id to stars else null
                }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setAll(context: Context, ratings: Map<Long, Int>) {
        try {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            editor.clear()
            for ((id, stars) in ratings) {
                if (stars in 1..5) editor.putInt(id.toString(), stars)
            }
            editor.apply()
        } catch (e: Exception) {
        }
    }
}
