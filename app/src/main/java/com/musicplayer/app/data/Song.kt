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
    val category: Category = Category.MINE,
    /** MediaStore DISPLAY_NAME (e.g. "song.ape") — used for format detection. */
    val fileName: String = "",
    /** Parent folder name (e.g. "Download") — used by the DOSSIERS tab. */
    val folder: String = "",
    /** Album id from MediaStore — used to load the album cover. */
    val albumId: Long = 0L,
    /** Date the file was added (seconds since epoch) — "recently added". */
    val dateAdded: Long = 0L,
    /** File size in bytes — used to validate the VLC cache copy. */
    val sizeBytes: Long = 0L
) {
    /** Lower-case file extension without the dot, e.g. "mp3", "ape", "". */
    val extension: String
        get() {
            val name = fileName.substringAfterLast('.', "")
            return name.lowercase()
        }

    companion object {
        /**
         * Audio formats Media3/ExoPlayer cannot decode on Android. These
         tracks are played with the libVLC fallback engine instead.
         TAK is NOT in this set on purpose: even VLC cannot read it.
         */
        val VLC_ONLY_EXTENSIONS = setOf(
            "ape", "dsf", "dff", "wv", "mpc", "wma", "spx",
            "xm", "mod", "s3m", "it", "mo3", "mtm", "umx",
            "ac3", "eac3", "dts"
        )

        /** True when this song needs the VLC fallback engine. */
        fun needsVlc(song: Song): Boolean = song.extension in VLC_ONLY_EXTENSIONS

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
