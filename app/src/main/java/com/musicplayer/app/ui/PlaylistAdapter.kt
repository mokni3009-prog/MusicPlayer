package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    playlists: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private var playlists: List<Playlist> = playlists

    inner class ViewHolder(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.binding.playlistName.text = playlist.name
        holder.binding.playlistCount.text = "${playlist.songIds.size} songs"
        holder.binding.root.setOnClickListener { onClick(playlist) }
        holder.binding.root.setOnLongClickListener {
            onDelete(playlist)
            true
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun submitList(newList: List<Playlist>) {
        playlists = newList
        notifyDataSetChanged()
    }
}
