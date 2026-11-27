package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemQueueBinding

/** "Up next" queue rows in the full player screen. */
class QueueAdapter(
    private var songs: List<Song>,
    private val onClick: (Song) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemQueueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.binding.songTitle.text = song.title
        holder.binding.songArtist.text = song.artist
        holder.binding.songDuration.text = formatDuration(song.durationMs)
        holder.binding.coverView.setBackgroundResource(SongAdapter.coverResFor(song))
        holder.binding.root.setOnClickListener { onClick(song) }
    }

    override fun getItemCount(): Int = songs.size

    fun submitList(newList: List<Song>) {
        songs = newList
        notifyDataSetChanged()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
