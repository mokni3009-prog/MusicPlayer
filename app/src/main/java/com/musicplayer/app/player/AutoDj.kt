package com.musicplayer.app.player

import android.content.Context
import com.musicplayer.app.data.Song

/**
 * v1.0.16 — Auto-DJ (feature 7): when the queue runs out (or is about
 * to), the app keeps the music going by itself, picking songs similar
 * to the one just played — same artist first, then same genre, same
 * album, same category — shuffled within the best-scoring tier so it
 * never repeats the same order. Songs already in the queue are never
 * picked again.
 */
object AutoDj {

    private const val PREFS = "zeno_autodj"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            false
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
        }
    }

    /** Similarity of [candidate] to the [seed] song just played. */
    fun score(seed: Song, candidate: Song): Int {
        var score = 0
        if (candidate.artist.isNotBlank() && candidate.artist == seed.artist) score += 3
        if (candidate.genre.isNotBlank() && candidate.genre == seed.genre) score += 2
        if (candidate.album.isNotBlank() && candidate.album == seed.album) score += 1
        if (candidate.category == seed.category) score += 1
        return score
    }

    /** Picks the next song: best similarity tier, shuffled inside it. */
    fun pickNext(library: List<Song>, seed: Song, playedIds: Set<Long>): Song? {
        val candidates = library.filter { it.id != seed.id && it.id !in playedIds }
        if (candidates.isEmpty()) return null
        val scored = candidates.map { it to score(seed, it) }
        val best = scored.maxOf { it.second }
        val tier = scored.filter { it.second == best }.map { it.first }
        return tier.random()
    }
}
