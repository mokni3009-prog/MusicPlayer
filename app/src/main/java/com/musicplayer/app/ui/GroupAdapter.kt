package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ItemGroupBinding
import com.musicplayer.app.databinding.ItemPlaylistCardBinding

/**
 * Generic grouped rows for the ALBUMS / ARTISTES / DOSSIERS tabs and for the
 * smart playlists at the top of the PLAYLISTS tab.
 *
 * Two styles:
 * - ROW: a list row with a letter tile (first letter of the title on a
 *   gradient), title + subtitle.
 * - CARD: a full-width gradient card (same look as the playlist cards),
 *   used for albums in the grid.
 */
class GroupAdapter(
    private var items: List<GroupItem>,
    private val onClick: (GroupItem) -> Unit,
    private val onLongClick: ((GroupItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class GroupItem(
        val id: String,
        val title: String,
        val subtitle: String,
        val card: Boolean = false,
        val tag: Any? = null
    )

    companion object {
        private const val VIEW_ROW = 0
        private const val VIEW_CARD = 1
        private val CARD_BGS = intArrayOf(
            R.drawable.bg_playlist_1,
            R.drawable.bg_playlist_2,
            R.drawable.bg_playlist_3,
            R.drawable.bg_playlist_4,
            R.drawable.bg_playlist_5
        )
        private val TILE_BGS = intArrayOf(
            R.drawable.bg_cover_1,
            R.drawable.bg_cover_2,
            R.drawable.bg_cover_3,
            R.drawable.bg_cover_4,
            R.drawable.bg_cover_5,
            R.drawable.bg_cover_6
        )
    }

    inner class RowHolder(val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root)
    inner class CardHolder(val binding: ItemPlaylistCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return if (items[position].card) VIEW_CARD else VIEW_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = if (viewType == VIEW_CARD) {
            CardHolder(ItemPlaylistCardBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemGroupBinding.inflate(inflater, parent, false))
        }
        // Focusable rows: D-pad navigation on Android TV (harmless on touch).
        holder.itemView.isFocusable = true
        return holder
    }

    /** Gold ripple foreground when the RIPPLE motion style is selected. */
    private fun rippleForeground(context: android.content.Context): android.graphics.drawable.Drawable? {
        return if (MotionStyle.effective(context) == MotionStyle.RIPPLE) {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ripple_gold)
        } else {
            null
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is CardHolder) {
            val bg = CARD_BGS[(item.id.hashCode() and Int.MAX_VALUE) % CARD_BGS.size]
            holder.binding.cardRoot.setBackgroundResource(bg)
            holder.binding.playlistName.text = item.title
            holder.binding.playlistSub.text = item.subtitle
            holder.binding.root.foreground = rippleForeground(holder.itemView.context)
            holder.binding.root.setOnClickListener { onClick(item) }
            holder.binding.root.setOnLongClickListener {
                onLongClick?.invoke(item)
                onLongClick != null
            }
        } else if (holder is RowHolder) {
            val tileBg = TILE_BGS[(item.id.hashCode() and Int.MAX_VALUE) % TILE_BGS.size]
            holder.binding.root.setBackgroundResource(R.drawable.bg_card_focus)
            holder.binding.root.foreground = rippleForeground(holder.itemView.context)
            holder.binding.tileView.setBackgroundResource(tileBg)
            holder.binding.tileLetter.text = item.title.firstOrNull()?.toString() ?: "♪"
            holder.binding.groupTitle.text = item.title
            holder.binding.groupSubtitle.text = item.subtitle
            holder.binding.root.setOnClickListener { onClick(item) }
            holder.binding.root.setOnLongClickListener {
                onLongClick?.invoke(item)
                onLongClick != null
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newList: List<GroupItem>) {
        items = newList
        notifyDataSetChanged()
    }
}
