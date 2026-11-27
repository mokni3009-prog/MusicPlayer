package com.musicplayer.app.data

import android.content.Context
import com.musicplayer.app.report.ErrorReporter

/** Favorite songs, stored as a set of MediaStore audio ids. */
object FavoritesStore {

    private const val PREFS = "zeno_favorites"
    private const val KEY = "ids"

    fun isFavorite(context: Context, songId: Long): Boolean {
        return try {
            all(context).contains(songId)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites read failed: ${e.message}",
                e
            )
            false
        }
    }

    fun all(context: Context): Set<Long> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet())
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet() ?: emptySet()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites read failed: ${e.message}",
                e
            )
            emptySet()
        }
    }

    fun getFavorites(context: Context): Set<Long> = all(context)

    /** Replaces the whole favorite set (used by backup restore). */
    fun setFavorites(context: Context, songs: List<Song>) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putStringSet(KEY, songs.map { it.id.toString() }.toSet())
                .apply()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites save failed: ${e.message}",
                e
            )
        }
    }

    fun addFavorite(context: Context, songId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = all(context).toMutableSet()
            current.add(songId)
            prefs.edit().putStringSet(KEY, current.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites save failed: ${e.message}",
                e
            )
        }
    }

    fun removeFavorite(context: Context, songId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = all(context).toMutableSet()
            current.remove(songId)
            prefs.edit().putStringSet(KEY, current.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites save failed: ${e.message}",
                e
            )
        }
    }

    fun toggle(context: Context, songId: Long): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = all(context).toMutableSet()
            val nowFavorite = if (current.contains(songId)) {
                current.remove(songId)
                false
            } else {
                current.add(songId)
                true
            }
            prefs.edit().putStringSet(KEY, current.map { it.toString() }.toSet()).apply()
            nowFavorite
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Favorites save failed: ${e.message}",
                e
            )
            false
        }
    }
}
