package com.musicplayer.app.ui

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song

object AddToPlaylistDialog {

    fun show(activity: Activity, song: Song) {
        val playlists = PlaylistStore.load(activity)
        val names = playlists.map { it.name }.toMutableList()
        names.add("New playlist...")
        AlertDialog.Builder(activity)
            .setTitle("Add to playlist")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    CreatePlaylistDialog.show(activity) { playlist ->
                        addSong(activity, playlist.id, song)
                    }
                } else {
                    addSong(activity, playlists[which].id, song)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSong(activity: Activity, playlistId: String, song: Song) {
        val lists = PlaylistStore.load(activity)
        val playlist = lists.find { it.id == playlistId } ?: return
        if (playlist.songIds.contains(song.id)) {
            Toast.makeText(activity, "Already in this playlist", Toast.LENGTH_SHORT).show()
            return
        }
        playlist.songIds.add(song.id)
        PlaylistStore.save(activity, lists)
        Toast.makeText(activity, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
    }
}
