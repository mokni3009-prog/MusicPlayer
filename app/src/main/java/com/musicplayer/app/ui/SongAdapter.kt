package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemSongBinding

class SongAdapter(
    songs: List<Song>,
    private val onClick: (Song, Int) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onPlayNext: (Song) -> Unit,
    private val onRemove: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    private var fullList: List<Song> = songs
    private var songs: List<Song> = songs

    inner class ViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.binding.songTitle.text = song.title
        holder.binding.songSubtitle.text = "${song.artist} • ${song.album}"
        holder.binding.songDuration.text = formatDuration(song.durationMs)
        holder.binding.root.setOnClickListener { onClick(song, position) }
        holder.binding.moreButton.setOnClickListener { v ->
            val popup = PopupMenu(v.context, v)
            if (onRemove != null) {
                popup.menu.add("Remove from playlist")
            } else {
                popup.menu.add("Add to playlist")
            }
            popup.menu.add("Play next")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Add to playlist" -> onAddToPlaylist(song)
                    "Remove from playlist" -> onRemove?.invoke(song)
                    "Play next" -> onPlayNext(song)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = songs.size

    fun submitList(newList: List<Song>) {
        fullList = newList
        songs = newList
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        songs = if (q.isEmpty()) {
            fullList
        } else {
            fullList.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
