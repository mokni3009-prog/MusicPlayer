package com.musicplayer.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.data.Category
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemEmptyBinding
import com.musicplayer.app.databinding.ItemNpCardBinding
import com.musicplayer.app.databinding.ItemPlaylistCardBinding
import com.musicplayer.app.databinding.ItemSectionBinding
import com.musicplayer.app.databinding.ItemTrackBinding

/** One row of the Zeno Play main screen (library + playlists + now-playing card). */
sealed class ScreenItem {
    data class Section(val title: String, val action: String?, val actionIsCreate: Boolean = false) : ScreenItem()
    data class Track(val song: Song) : ScreenItem()
    data class PlaylistCard(val playlist: Playlist, val gradRes: Int) : ScreenItem()
    object NpCard : ScreenItem()
    data class Empty(val message: String) : ScreenItem()
}

data class NowPlayingState(
    val song: Song?,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val isFavorite: Boolean,
    val volume: Float,
    val durationMs: Long
)

class MainScreenAdapter(
    private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onTrackClick(song: Song, indexInList: Int)
        fun onTrackFavorite(song: Song)
        fun onTrackAddToPlaylist(song: Song)
        fun onTrackPlayNext(song: Song)
        fun onPlaylistClick(playlist: Playlist)
        fun onPlaylistLongPress(playlist: Playlist)
        fun onCreatePlaylist()
        fun onNpPlayPause()
        fun onNpNext()
        fun onNpPrev()
        fun onNpShuffle()
        fun onNpRepeat()
        fun onNpSeekTo(positionMs: Long)
        fun onNpVolume(volume: Float)
        fun onNpFavorite()
        fun onNpOpenFull()
        fun onNpCardBound(holder: NpCardViewHolder)
        fun onNpCardUnbound()
    }

    var tracks: List<Song> = emptyList()
        private set
    var playlists: List<Playlist> = emptyList()
        private set
    var favorites: Set<Long> = emptySet()
        private set
    var currentSongId: Long? = null
        private set
    var isPlaying: Boolean = false
        private set
    var nowPlaying: NowPlayingState? = null
        private set

    private var items: List<ScreenItem> = emptyList()

    fun submitLibrary(tracks: List<Song>, favorites: Set<Long>, currentSongId: Long?, isPlaying: Boolean) {
        this.tracks = tracks
        this.favorites = favorites
        this.currentSongId = currentSongId
        this.isPlaying = isPlaying
        rebuild()
        notifyDataSetChanged()
    }

    fun submitPlaylists(playlists: List<Playlist>) {
        this.playlists = playlists
        rebuild()
        notifyDataSetChanged()
    }

    fun submitNowPlaying(state: NowPlayingState?) {
        nowPlaying = state
        rebuild()
        notifyDataSetChanged()
    }

    private fun rebuild() {
        val list = mutableListOf<ScreenItem>()
        list += ScreenItem.Section("مكتبة الأغاني", "${tracks.size} أغنية")
        if (tracks.isEmpty()) {
            list += ScreenItem.Empty("ما لقينا حتى أغنية هنا.\nجرّب بحث آخر.")
        } else {
            tracks.forEach { list += ScreenItem.Track(it) }
        }
        list += ScreenItem.Section("قوائم التشغيل", "قائمة جديدة", actionIsCreate = true)
        playlists.forEachIndexed { i, p ->
            list += ScreenItem.PlaylistCard(p, PLAYLIST_GRADS[i % PLAYLIST_GRADS.size])
        }
        list += ScreenItem.NpCard
        items = list
    }

    fun npCardPosition(): Int = items.indexOf(ScreenItem.NpCard)

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ScreenItem.Section -> TYPE_SECTION
        is ScreenItem.Track -> TYPE_TRACK
        is ScreenItem.PlaylistCard -> TYPE_PLAYLIST
        is ScreenItem.NpCard -> TYPE_NPCARD
        is ScreenItem.Empty -> TYPE_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionViewHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_TRACK -> TrackViewHolder(ItemTrackBinding.inflate(inflater, parent, false))
            TYPE_PLAYLIST -> PlaylistViewHolder(ItemPlaylistCardBinding.inflate(inflater, parent, false))
            TYPE_NPCARD -> NpCardViewHolder(ItemNpCardBinding.inflate(inflater, parent, false))
            else -> EmptyViewHolder(ItemEmptyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ScreenItem.Section -> (holder as SectionViewHolder).bind(item)
            is ScreenItem.Track -> (holder as TrackViewHolder).bind(item.song)
            is ScreenItem.PlaylistCard -> (holder as PlaylistViewHolder).bind(item)
            is ScreenItem.NpCard -> {
                (holder as NpCardViewHolder).bind(nowPlaying)
                callbacks.onNpCardBound(holder)
            }
            is ScreenItem.Empty -> (holder as EmptyViewHolder).bind(item.message)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is NpCardViewHolder) callbacks.onNpCardUnbound()
    }

    inner class SectionViewHolder(val binding: ItemSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScreenItem.Section) {
            binding.sectionTitle.text = item.title
            binding.sectionAction.text = item.action ?: ""
            binding.sectionAction.setOnClickListener {
                if (item.actionIsCreate) callbacks.onCreatePlaylist()
            }
        }
    }

    inner class TrackViewHolder(val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song) {
            val isCurrent = song.id == currentSongId
            binding.trackRoot.setBackgroundResource(
                if (isCurrent) R.drawable.bg_card_current else R.drawable.bg_card
            )
            binding.coverView.setBackgroundResource(SongAdapter.coverResFor(song))
            binding.playingDot.visibility =
                if (isCurrent && isPlaying) View.VISIBLE else View.GONE
            binding.songTitle.text = song.title
            binding.songSubtitle.text = "${song.artist} • ${categoryLabel(song)}"
            binding.songDuration.text = formatDuration(song.durationMs)
            val fav = favorites.contains(song.id)
            binding.favButton.setImageResource(
                if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart
            )
            binding.favButton.setColorFilter(
                binding.root.context.getColor(
                    if (fav) R.color.zeno_cyan else R.color.zeno_ink3
                )
            )
            binding.favButton.setOnClickListener { callbacks.onTrackFavorite(song) }
            binding.root.setOnClickListener {
                val index = tracks.indexOf(song)
                if (index >= 0) callbacks.onTrackClick(song, index)
            }
            binding.moreButton.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(R.string.add_to_playlist)
                popup.menu.add(R.string.play_next)
                popup.setOnMenuItemClickListener { menuItem ->
                    val addLabel = v.context.getString(R.string.add_to_playlist)
                    if (menuItem.title == addLabel) {
                        callbacks.onTrackAddToPlaylist(song)
                    } else {
                        callbacks.onTrackPlayNext(song)
                    }
                    true
                }
                popup.show()
            }
        }

        private fun categoryLabel(song: Song): String {
            return when (song.category) {
                Category.TUNSI -> "تونسي"
                Category.SHARQI -> "شرقي"
                Category.AJNABI -> "أجنبي"
                else -> song.album.ifBlank { "من جهازي" }
            }
        }
    }

    inner class PlaylistViewHolder(val binding: ItemPlaylistCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScreenItem.PlaylistCard) {
            binding.cardRoot.setBackgroundResource(item.gradRes)
            binding.playlistName.text = item.playlist.name
            binding.playlistSub.text = "${item.playlist.songIds.size} أغنية"
            binding.root.setOnClickListener { callbacks.onPlaylistClick(item.playlist) }
            binding.root.setOnLongClickListener {
                callbacks.onPlaylistLongPress(item.playlist)
                true
            }
        }
    }

    inner class NpCardViewHolder(val binding: ItemNpCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(state: NowPlayingState?) {
            val song = state?.song
            binding.npTitle.text = song?.title ?: binding.root.context.getString(R.string.no_song)
            binding.npArtist.text = song?.artist
                ?: binding.root.context.getString(R.string.from_device)
            binding.npState.text = binding.root.context.getString(
                if (state?.isPlaying == true) R.string.now_playing else R.string.stopped
            )
            binding.npVisualizer.setPlaying(state?.isPlaying == true)
            binding.npPlayPause.setImageResource(
                if (state?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.npShuffle.alpha = if (state?.shuffle == true) 1.0f else 0.4f
            binding.npShuffle.setColorFilter(
                binding.root.context.getColor(
                    if (state?.shuffle == true) R.color.zeno_cyan else R.color.zeno_ink2
                )
            )
            binding.npRepeat.alpha = if ((state?.repeatMode ?: 0) == 0) 0.4f else 1.0f
            binding.npRepeat.setColorFilter(
                binding.root.context.getColor(
                    if ((state?.repeatMode ?: 0) == 0) R.color.zeno_ink2 else R.color.zeno_cyan
                )
            )
            binding.npRepeatBadge.visibility =
                if (state?.repeatMode == 2) View.VISIBLE else View.GONE
            binding.npFav.text = binding.root.context.getString(R.string.favorite)
            binding.npFav.alpha = if (state?.isFavorite == true) 1.0f else 0.7f
            binding.npVolume.progress = ((state?.volume ?: 0.8f) * 100).toInt()
            binding.npSeekBar.max = (state?.durationMs ?: 0L).toInt()
            binding.npTotalTime.text = formatDuration(state?.durationMs ?: 0L)

            binding.npPlayPause.setOnClickListener { callbacks.onNpPlayPause() }
            binding.npNext.setOnClickListener { callbacks.onNpNext() }
            binding.npPrev.setOnClickListener { callbacks.onNpPrev() }
            binding.npShuffle.setOnClickListener { callbacks.onNpShuffle() }
            binding.npRepeat.setOnClickListener { callbacks.onNpRepeat() }
            binding.npFav.setOnClickListener { callbacks.onNpFavorite() }
            binding.npOpenFull.setOnClickListener { callbacks.onNpOpenFull() }
            binding.npSeekBar.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        if (fromUser) callbacks.onNpSeekTo(progress.toLong())
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                }
            )
            binding.npVolume.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        if (fromUser) callbacks.onNpVolume(progress / 100f)
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                }
            )
        }
    }

    inner class EmptyViewHolder(val binding: ItemEmptyBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: String) {
            binding.root.text = message
        }
    }

    companion object {
        const val TYPE_SECTION = 0
        const val TYPE_TRACK = 1
        const val TYPE_PLAYLIST = 2
        const val TYPE_NPCARD = 3
        const val TYPE_EMPTY = 4

        private val PLAYLIST_GRADS = intArrayOf(
            R.drawable.bg_playlist_1,
            R.drawable.bg_playlist_2,
            R.drawable.bg_playlist_3,
            R.drawable.bg_playlist_4,
            R.drawable.bg_playlist_5
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
