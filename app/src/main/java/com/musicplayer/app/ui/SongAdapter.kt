package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemSongBinding

/**
 * Song rows (CHANSONS tab, playlist details): square cover (real album art
 * when available, else a gradient tile), title, "duration · artist",
 * favorite heart and a ⋮ menu (French): add to playlist / play next /
 * edit tags / create a ringtone.
 *
 * The currently playing row is highlighted with the animated mini-equalizer
 * state (gold background) and the heart shows the favorite state.
 */
class SongAdapter(
    songs: List<Song>,
    private val onClick: (Song, Int) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onPlayNext: (Song) -> Unit,
    private val onRemove: ((Song) -> Unit)? = null,
    private var favorites: Set<Long> = emptySet(),
    private var currentId: Long? = null,
    private var isPlaying: Boolean = false,
    private val onFavorite: ((Song) -> Unit)? = null,
    private val onEditTags: ((Song) -> Unit)? = null,
    private val onCreateRingtone: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    private var fullList: List<Song> = songs
    private var songs: List<Song> = songs

    inner class ViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Focusable rows: D-pad navigation on Android TV (harmless on touch).
        binding.root.isFocusable = true
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        val b = holder.binding
        b.songTitle.text = song.title
        b.songSubtitle.text = "${song.artist} • ${song.album}"
        b.songDuration.text = formatDuration(song.durationMs)
        // Current row: highlighted background.
        val isCurrent = song.id == currentId
        b.root.setBackgroundResource(
            if (isCurrent) R.drawable.bg_card_current else R.drawable.bg_card_focus
        )
        // Gold ripple on tap when the RIPPLE motion style is selected.
        b.root.foreground =
            if (MotionStyle.effective(holder.itemView.context) == MotionStyle.RIPPLE) {
                androidx.core.content.ContextCompat.getDrawable(
                    holder.itemView.context, R.drawable.ripple_gold
                )
            } else {
                null
            }
        b.songTitle.setTextColor(
            holder.itemView.context.getColor(
                if (isCurrent) R.color.zeno_gold else R.color.zeno_ink
            )
        )
        // Favorite heart.
        val isFav = favorites.contains(song.id)
        b.favButton.setImageResource(
            if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        b.favButton.setColorFilter(
            holder.itemView.context.getColor(
                if (isFav) R.color.zeno_cyan else R.color.zeno_ink3
            )
        )
        b.favButton.setOnClickListener { onFavorite?.invoke(song) }
        // Real album art (background thread, cached); fallback = the Z logo
        // without any square (the transparent mark on the gradient tile).
        b.coverView.setBackgroundResource(coverResFor(song))
        b.coverImage.setImageResource(R.drawable.zeno_logo_mark)
        b.coverIcon.visibility = View.GONE
        b.coverImage.tag = song.id
        Thread {
            try {
                val bitmap = AlbumArtLoader.loadTrackCover(holder.itemView.context, song)
                if (bitmap != null && b.coverImage.tag == song.id) {
                    b.coverImage.post {
                        b.coverImage.setImageBitmap(bitmap)
                        b.coverIcon.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // Cover loading must never break the list.
            }
        }.start()
        b.root.setOnClickListener { onClick(song, position) }
        b.moreButton.setOnClickListener { v ->
            val ctx = v.context
            val popup = PopupMenu(ctx, v)
            if (onRemove != null) {
                popup.menu.add(ctx.getString(R.string.remove_from_playlist))
            } else {
                popup.menu.add(ctx.getString(R.string.add_to_playlist))
            }
            popup.menu.add(ctx.getString(R.string.play_next))
            if (onEditTags != null) popup.menu.add(ctx.getString(R.string.edit_tags_menu))
            if (onCreateRingtone != null) popup.menu.add(ctx.getString(R.string.create_ringtone_menu))
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    ctx.getString(R.string.add_to_playlist) -> onAddToPlaylist(song)
                    ctx.getString(R.string.remove_from_playlist) -> onRemove?.invoke(song)
                    ctx.getString(R.string.play_next) -> onPlayNext(song)
                    ctx.getString(R.string.edit_tags_menu) -> onEditTags?.invoke(song)
                    ctx.getString(R.string.create_ringtone_menu) -> onCreateRingtone?.invoke(song)
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

    /** Updates favorites / current-track state without rebuilding the list. */
    fun submitState(favorites: Set<Long>, currentId: Long?, isPlaying: Boolean) {
        this.favorites = favorites
        this.currentId = currentId
        this.isPlaying = isPlaying
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim()
        songs = if (q.isEmpty()) {
            fullList
        } else {
            fullList.filter { it.matchesQuery(q) }
        }
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    companion object {
        private val COVERS = intArrayOf(
            R.drawable.bg_cover_1,
            R.drawable.bg_cover_2,
            R.drawable.bg_cover_3,
            R.drawable.bg_cover_4,
            R.drawable.bg_cover_5,
            R.drawable.bg_cover_6
        )

        fun coverResFor(song: Song): Int {
            val idx = ((song.id % COVERS.size) + COVERS.size).toInt() % COVERS.size
            return COVERS[idx]
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
