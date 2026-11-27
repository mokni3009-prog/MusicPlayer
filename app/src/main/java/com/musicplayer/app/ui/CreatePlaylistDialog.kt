package com.musicplayer.app.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.databinding.DialogCreatePlaylistBinding

object CreatePlaylistDialog {

    fun show(activity: Activity, onCreated: ((Playlist) -> Unit)? = null) {
        val binding = DialogCreatePlaylistBinding.inflate(activity.layoutInflater)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(com.musicplayer.app.R.string.create_playlist))
            .setView(binding.root)
            .setPositiveButton(activity.getString(com.musicplayer.app.R.string.create)) { _, _ ->
                val name = binding.playlistNameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = PlaylistStore.create(activity, name)
                    onCreated?.invoke(playlist)
                    (activity as? MainActivity)?.refreshPlaylists()
                }
            }
            .setNegativeButton(activity.getString(com.musicplayer.app.R.string.cancel), null)
            .show()
    }
}
