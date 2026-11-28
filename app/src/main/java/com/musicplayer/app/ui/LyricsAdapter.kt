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

    var activeIndex = -1
        private set
    private var query: String = ""
    private var matched: Set<Int> = emptySet()
    /** Karaoke word-by-word mode: the active line colours word by word
     * as it plays (progress 0..1 from [wordProgress]). */
    var karaokeWordMode: Boolean = false
    private var wordProgress = 0f

    class ViewHolder(val binding: ItemLyricLineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        val active = position == activeIndex
        val isMatch = position in matched
        if (active && karaokeWordMode && wordProgress > 0f) {
            // Word-by-word karaoke: colour the words already sung.
            val words = line.text.split(' ')
            val sung = (words.size * wordProgress).toInt().coerceIn(0, words.size)
            val ctx = holder.itemView.context
            val spannable = android.text.SpannableString(line.text)
            var start = 0
            for (i in words.indices) {
                val end = start + words[i].length
                val color = if (i < sung) ctx.getColor(R.color.zeno_cyan)
                else ctx.getColor(R.color.zeno_ink2)
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                start = end + 1
            }
            holder.binding.lyricText.text = spannable
        } else {
            holder.binding.lyricText.text = line.text
        }
        holder.binding.lyricText.setTextColor(
            holder.itemView.context.getColor(
                when {
                    isMatch -> R.color.zeno_gold
                    active -> R.color.zeno_cyan
                    else -> R.color.zeno_ink2
                }
            )
        )
        holder.binding.lyricText.textSize = if (active || isMatch) 17f else 15f
        holder.binding.lyricText.setTypeface(
            null,
            if (isMatch) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
        )
        holder.binding.root.setOnClickListener { onLineClick(line) }
    }

    override fun getItemCount(): Int = lines.size

    fun submitList(newLines: List<LyricsManager.LyricLine>) {
        lines = newLines
        activeIndex = -1
        applyQuery()
        notifyDataSetChanged()
    }

    /** Searches the lyrics: matching lines turn gold + bold. */
    fun setQuery(q: String) {
        query = q.trim()
        applyQuery()
        notifyDataSetChanged()
    }

    private fun applyQuery() {
        if (query.isEmpty()) {
            matched = emptySet()
            return
        }
        val norm = com.musicplayer.app.data.MusicRepository.normalizeForSearch(query)
        matched = lines.indices.filter { i ->
            com.musicplayer.app.data.MusicRepository.normalizeForSearch(lines[i].text)
                .contains(norm)
        }.toSet()
    }

    /** First matching line, or -1 when there is no match. */
    fun firstMatch(): Int = matched.minOrNull() ?: -1

    /** Next match after [after] (wraps around), or -1. */
    fun nextMatch(after: Int): Int {
        if (matched.isEmpty()) return -1
        val sorted = matched.sorted()
        return sorted.firstOrNull { it > after } ?: sorted.first()
    }

    fun matchCount(): Int = matched.size

    fun setActive(index: Int, wordProgress: Float = 0f) {
        val progressChanged = index == activeIndex &&
            kotlin.math.abs(wordProgress - this.wordProgress) > 0.12f
        if (index == activeIndex && !progressChanged) return
        this.wordProgress = wordProgress.coerceIn(0f, 1f)
        val old = activeIndex
        activeIndex = index
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(index)
    }
}
