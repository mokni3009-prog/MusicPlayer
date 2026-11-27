package com.musicplayer.app.data

import android.net.Uri

enum class Category {
    ALL, TUNSI, SHARQI, AJNABI, FAV, MINE
}

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val genre: String = "",
    val category: Category = Category.MINE
) {
    companion object {
        /** Maps a MediaStore genre string to a library category. */
        fun categoryForGenre(genre: String): Category {
            val g = genre.lowercase()
            if (g.isBlank()) return Category.MINE
            return when {
                g.contains("tunis") || g.contains("rai") || g.contains("raï") -> Category.TUNSI
                g.contains("arab") || g.contains("orient") || g.contains("tarab") ||
                    g.contains("sharqi") || g.contains("khalij") || g.contains("egypt") -> Category.SHARQI
                else -> Category.AJNABI
            }
        }
    }
}
