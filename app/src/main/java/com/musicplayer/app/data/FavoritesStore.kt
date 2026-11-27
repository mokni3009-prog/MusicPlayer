package com.musicplayer.app.data

import android.content.Context

/** Favorite songs, stored as a set of MediaStore audio ids. */
object FavoritesStore {

    private const val PREFS = "zeno_favorites"
    private const val KEY = "ids"

    fun isFavorite(context: Context, songId: Long): Boolean {
        return all(context).contains(songId)
    }

    fun all(context: Context): Set<Long> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun toggle(context: Context, songId: Long): Boolean {
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
        return nowFavorite
    }
}
