package com.musicplayer.app.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
import com.musicplayer.app.data.FavoritesStore
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityTvMainBinding
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android TV main screen (landscape, D-pad remote).
 *
 * Left menu: All songs / Albums / Artists / Playlists / Folders / Favorites.
 * Right: the list (songs or groups) + a now-playing bar with transport.
 * The full portrait PlayerActivity is NOT opened on TV; playback is
 * controlled from the now-playing bar. Volume keys on the remote drive
 * the system volume; the stored 0-200% boost still applies in the player.
 */
class TvMainActivity : FullScreenActivity() {

    private enum class ViewMode { ALL, ALBUMS, ARTISTS, PLAYLISTS, FOLDERS, FAVORITES, FILTERED }

    private lateinit var binding: ActivityTvMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var allSongs: List<Song> = emptyList()
    private var mode: ViewMode = ViewMode.ALL
    private var filteredTitle: String = ""
    private var filteredSongs: List<Song> = emptyList()
    private lateinit var songAdapter: SongAdapter
    private lateinit var groupAdapter: GroupAdapter

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateTransport()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying()
            refreshSongState()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (PlayerConnection.controller == controller) PlayerConnection.controller = null
            controller.removeListener(playerListener)
            if (!isFinishing && !isDestroyed) connectController()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvRecycler.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(
            emptyList(),
            onClick = { song, index -> PlayerConnection.playSongs(filteredOrAll(), index) },
            onAddToPlaylist = { song -> AddToPlaylistDialog.show(this, song) },
            onPlayNext = { song -> PlayerConnection.playNext(song) },
            favorites = emptySet(),
            onFavorite = { song -> toggleFavorite(song) }
        )
        groupAdapter = GroupAdapter(emptyList(), onClick = { item -> onGroupClick(item) })

        binding.menuAll.setOnClickListener { showAll() }
        binding.menuAlbums.setOnClickListener { showAlbums() }
        binding.menuArtists.setOnClickListener { showArtists() }
        binding.menuPlaylists.setOnClickListener { showPlaylists() }
        binding.menuFolders.setOnClickListener { showFolders() }
        binding.menuFavorites.setOnClickListener { showFavorites() }

        binding.npPrev.setOnClickListener { PlayerConnection.previous() }
        binding.npPlayPause.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.npNext.setOnClickListener { PlayerConnection.next() }
        binding.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        lifecycleScope.launch {
            allSongs = withContext(Dispatchers.IO) { MusicRepository.loadSongs(this@TvMainActivity) }
            showAll()
            binding.tvRecycler.requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        connectController()
        handler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(updateRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Back from a filtered song list returns to the parent list.
        if (keyCode == KeyEvent.KEYCODE_BACK && mode == ViewMode.FILTERED) {
            showAll()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun connectController() {
        val existing = PlayerConnection.controller
        if (existing != null) {
            existing.addListener(playerListener)
            updateNowPlaying()
            updateTransport()
            return
        }
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        future.addListener({
            try {
                val c = future.get()
                PlayerConnection.controller = c
                c.addListener(playerListener)
                updateNowPlaying()
                updateTransport()
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun filteredOrAll(): List<Song> {
        return if (mode == ViewMode.FILTERED) filteredSongs else allSongs
    }

    // ----- Views -----

    private fun showAll() {
        mode = ViewMode.ALL
        binding.viewTitle.text = getString(R.string.tv_menu_all)
        binding.viewCount.text = getString(R.string.tv_count_songs, allSongs.size)
        binding.tvRecycler.adapter = songAdapter
        songAdapter.submitList(allSongs)
        refreshSongState()
    }

    private fun showAlbums() {
        mode = ViewMode.ALBUMS
        binding.viewTitle.text = getString(R.string.tv_menu_albums)
        val groups = MusicRepository.albumsOf(allSongs)
        binding.viewCount.text = getString(R.string.tv_count_songs, allSongs.size)
        binding.tvRecycler.adapter = groupAdapter
        groupAdapter.submitList(groups.map {
            GroupAdapter.GroupItem(
                it.name, it.name,
                getString(R.string.tv_count_songs, it.songs.size),
                tag = it.songs
            )
        })
    }

    private fun showArtists() {
        mode = ViewMode.ARTISTS
        binding.viewTitle.text = getString(R.string.tv_menu_artists)
        val groups = MusicRepository.artistsOf(allSongs)
        binding.viewCount.text = getString(R.string.tv_count_songs, allSongs.size)
        binding.tvRecycler.adapter = groupAdapter
        groupAdapter.submitList(groups.map {
            GroupAdapter.GroupItem(
                it.name, it.name,
                getString(R.string.tv_count_songs, it.songs.size),
                tag = it.songs
            )
        })
    }

    private fun showPlaylists() {
        mode = ViewMode.PLAYLISTS
        binding.viewTitle.text = getString(R.string.tv_menu_playlists)
        val lists = PlaylistStore.load(this)
        binding.viewCount.text = getString(R.string.tv_count_playlists, lists.size)
        binding.tvRecycler.adapter = groupAdapter
        // v1.0.16 (m27): the song lookup used to be find() inside the
        // map (O(n^2) per playlist) — index once.
        val byId = allSongs.associateBy { it.id }
        groupAdapter.submitList(lists.map { playlist ->
            val songs = playlist.songIds.mapNotNull { id -> byId[id] }
            GroupAdapter.GroupItem(
                playlist.id.toString(), playlist.name,
                getString(R.string.tv_count_songs, songs.size),
                tag = songs
            )
        })
    }

    private fun showFolders() {
        mode = ViewMode.FOLDERS
        binding.viewTitle.text = getString(R.string.tv_menu_folders)
        val groups = MusicRepository.foldersOf(allSongs)
        binding.viewCount.text = getString(R.string.tv_count_songs, allSongs.size)
        binding.tvRecycler.adapter = groupAdapter
        groupAdapter.submitList(groups.map {
            GroupAdapter.GroupItem(
                it.name, it.name,
                getString(R.string.tv_count_songs, it.songs.size),
                tag = it.songs
            )
        })
    }

    private fun showFavorites() {
        mode = ViewMode.FAVORITES
        binding.viewTitle.text = getString(R.string.tv_menu_favorites)
        val favIds = FavoritesStore.getFavorites(this)
        val favs = allSongs.filter { it.id in favIds }
        binding.viewCount.text = getString(R.string.tv_count_songs, favs.size)
        binding.tvRecycler.adapter = songAdapter
        songAdapter.submitList(favs)
        refreshSongState()
    }

    private fun onGroupClick(item: GroupAdapter.GroupItem) {
        @Suppress("UNCHECKED_CAST")
        val songs = (item.tag as? List<Song>) ?: return
        mode = ViewMode.FILTERED
        filteredTitle = item.title
        filteredSongs = songs
        binding.viewTitle.text = item.title
        binding.viewCount.text = getString(R.string.tv_count_songs, songs.size)
        binding.tvRecycler.adapter = songAdapter
        songAdapter.submitList(songs)
        refreshSongState()
    }

    private fun toggleFavorite(song: Song) {
        if (FavoritesStore.isFavorite(this, song.id)) {
            FavoritesStore.removeFavorite(this, song.id)
        } else {
            FavoritesStore.addFavorite(this, song.id)
        }
        refreshSongState()
        if (mode == ViewMode.FAVORITES) showFavorites()
    }

    private fun refreshSongState() {
        if (!::songAdapter.isInitialized) return
        songAdapter.submitState(
            FavoritesStore.getFavorites(this),
            PlayerConnection.currentSong()?.id,
            PlayerConnection.isPlaying()
        )
    }

    // ----- Now playing bar -----

    private fun updateNowPlaying() {
        val song = PlayerConnection.currentSong()
        binding.npTitle.text = song?.title ?: getString(R.string.no_song)
        binding.npTitle.isSelected = true
        binding.npArtist.text = song?.artist ?: getString(R.string.from_device)
        val duration = PlayerConnection.durationMs()
        binding.npSeek.max = duration.toInt()
        binding.npTotal.text = formatTime(duration)
        if (song != null) {
            binding.npCover.tag = song.id
            Thread {
                try {
                    val bitmap = AlbumArtLoader.loadTrackCover(this, song)
                    runOnUiThread {
                        if (binding.npCover.tag != song.id) return@runOnUiThread
                        if (bitmap != null) {
                            binding.npCover.setImageBitmap(bitmap)
                        } else {
                            binding.npCover.setImageResource(R.drawable.zeno_logo_mark)
                        }
                    }
                } catch (e: Exception) {
                }
            }.start()
        } else {
            binding.npCover.setImageResource(R.drawable.zeno_logo_mark)
        }
        updateTransport()
    }

    private fun updateTransport() {
        val playing = PlayerConnection.isPlaying()
        binding.npPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateProgress() {
        val duration = PlayerConnection.durationMs()
        val position = PlayerConnection.currentPositionMs()
        if (duration > 0 && !binding.npSeek.isPressed) {
            binding.npSeek.progress = position.toInt()
        }
        binding.npCurrent.text = formatTime(position)
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
