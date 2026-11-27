package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ItemLyricLineBinding
import com.musicplayer.app.player.LyricsManager

/** One synced lyric line. Tap a line to seek to it. The active line is cyan. */
class LyricsAdapter(
    private var lines: List<LyricsManager.LyricLine>,
    private val onLineClick: (LyricsManager.LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var activeIndex = -1

    class ViewHolder(val binding: ItemLyricLineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.binding.lyricText.text = line.text
        val active = position == activeIndex
        holder.binding.lyricText.setTextColor(
            holder.itemView.context.getColor(
                if (active) R.color.zeno_cyan else R.color.zeno_ink2
            )
        )
        holder.binding.lyricText.textSize = if (active) 17f else 15f
        holder.binding.root.setOnClickListener { onLineClick(line) }
    }

    override fun getItemCount(): Int = lines.size

    fun submitList(newLines: List<LyricsManager.LyricLine>) {
        lines = newLines
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun setActive(index: Int) {
        if (index == activeIndex) return
        val old = activeIndex
        activeIndex = index
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(index)
    }
}
