package com.musicplayer.app.data

data class Playlist(
    val id: String,
    val name: String,
    val songIds: MutableList<Long> = mutableListOf()
)
