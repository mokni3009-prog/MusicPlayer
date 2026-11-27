package com.musicplayer.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.app.BuildConfig
import com.musicplayer.app.R
import com.musicplayer.app.data.Category
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityMainBinding
import com.musicplayer.app.player.FavoritesStore
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.VisualizerHub
import com.musicplayer.app.update.ReleaseInfo
import com.musicplayer.app.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Zeno Play main screen: brand header, search, category chips, the song
 * library, playlists grid, the now-playing card and the mini player bar.
 * Single RecyclerView, 2-column grid for playlist cards.
 */
class MainActivity : AppCompatActivity(), MainScreenAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var screenAdapter: MainScreenAdapter

    private var allSongs: List<Song> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var favorites: Set<Long> = emptySet()
    private var selectedCategory: Category = Category.ALL
    private var searchQuery: String = ""

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var npHolder: MainScreenAdapter.NpCardViewHolder? = null
    private var discAnimator: ObjectAnimator? = null

    private var updateCheckedThisLaunch = false
    private var pendingRelease: ReleaseInfo? = null
    private var updateInProgress = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[Manifest.permission.READ_MEDIA_AUDIO] == true ||
            result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (audioGranted) {
            loadSongs()
        } else {
            Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshNowPlaying()
            updateMiniPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNowPlaying()
            refreshLibrary()
            updateMiniPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            refreshNowPlaying()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            refreshNowPlaying()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (PlayerConnection.controller == controller) PlayerConnection.controller = null
            controller.removeListener(playerListener)
            // The service rebuilt its session (playback fallback). Reconnect.
            if (!isFinishing && !isDestroyed) connectController()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        screenAdapter = MainScreenAdapter(this)
        val grid = GridLayoutManager(this, 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (screenAdapter.getItemViewType(position)) {
                    MainScreenAdapter.TYPE_PLAYLIST -> 1
                    else -> 2
                }
            }
        }
        binding.mainRecycler.layoutManager = grid
        binding.mainRecycler.adapter = screenAdapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshLibrary()
            }
        })

        val chipClicks = mapOf(
            binding.chipAll to Category.ALL,
            binding.chipTunsi to Category.TUNSI,
            binding.chipSharqi to Category.SHARQI,
            binding.chipAjnabi to Category.AJNABI,
            binding.chipFav to Category.FAV,
            binding.chipMine to Category.MINE
        )
        chipClicks.forEach { (button, category) ->
            button.setOnClickListener {
                selectedCategory = category
                updateChipSelection()
                refreshLibrary()
            }
        }
        updateChipSelection()

        binding.updateButton.setOnClickListener { checkForUpdate(manual = true) }

        binding.miniPlayPause.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.miniNext.setOnClickListener { PlayerConnection.next() }
        binding.miniPrev.setOnClickListener { PlayerConnection.previous() }
        binding.miniPlayerBar.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        connectController()
        VisualizerHub.listener = { magnitudes ->
            npHolder?.binding?.npVisualizer?.updateMagnitudes(magnitudes)
        }
        handler.post(progressRunnable)
        favorites = FavoritesStore.getFavorites(this)
        playlists = PlaylistStore.load(this)
        refreshLibrary()
        refreshPlaylists()
        refreshNowPlaying()
        // Auto update check once per launch (silent when there is nothing new).
        if (!updateCheckedThisLaunch) {
            updateCheckedThisLaunch = true
            handler.postDelayed({ checkForUpdate(manual = false) }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the "install unknown apps" settings screen: continue the update.
        val pending = pendingRelease
        if (pending != null && packageManager.canRequestPackageInstalls()) {
            pendingRelease = null
            downloadAndInstall(pending)
        }
    }

    override fun onStop() {
        super.onStop()
        VisualizerHub.listener = null
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        discAnimator?.cancel()
        discAnimator = null
        PlayerConnection.controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    // ----- controller -----

    private fun connectController() {
        val existing = PlayerConnection.controller
        if (existing != null) {
            existing.addListener(playerListener)
            refreshNowPlaying()
            updateMiniPlayer()
            return
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                PlayerConnection.controller = c
                c.addListener(playerListener)
                refreshNowPlaying()
                updateMiniPlayer()
            } catch (e: Exception) {
                // Connection failed; playback controls stay hidden until reconnect.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ----- permissions / library -----

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun loadSongs() {
        allSongs = MusicRepository.loadSongs(this)
        favorites = FavoritesStore.getFavorites(this)
        playlists = PlaylistStore.load(this)
        refreshLibrary()
        refreshPlaylists()
        if (allSongs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_music_files), Toast.LENGTH_LONG).show()
        }
    }

    private fun filteredSongs(): List<Song> {
        val q = searchQuery.trim().lowercase()
        return allSongs.filter { song ->
            val categoryOk = when (selectedCategory) {
                Category.ALL -> true
                Category.FAV -> favorites.contains(song.id)
                else -> song.category == selectedCategory
            }
            val queryOk = q.isEmpty() ||
                song.title.lowercase().contains(q) ||
                song.artist.lowercase().contains(q) ||
                song.album.lowercase().contains(q)
            categoryOk && queryOk
        }
    }

    private fun updateChipSelection() {
        val chips = mapOf(
            binding.chipAll to Category.ALL,
            binding.chipTunsi to Category.TUNSI,
            binding.chipSharqi to Category.SHARQI,
            binding.chipAjnabi to Category.AJNABI,
            binding.chipFav to Category.FAV,
            binding.chipMine to Category.MINE
        )
        chips.forEach { (button, category) ->
            val selected = category == selectedCategory
            button.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            button.setTextColor(
                getColor(if (selected) R.color.zeno_on_gradient else R.color.zeno_ink2)
            )
        }
    }

    private fun refreshLibrary() {
        val current = PlayerConnection.currentSong()
        screenAdapter.submitLibrary(
            filteredSongs(),
            favorites,
            current?.id,
            PlayerConnection.isPlaying()
        )
    }

    fun refreshPlaylists() {
        playlists = PlaylistStore.load(this)
        screenAdapter.submitPlaylists(playlists)
    }

    // ----- now playing / mini player -----

    private fun currentState(): NowPlayingState {
        val c = PlayerConnection.controller
        val song = PlayerConnection.currentSong()
        return NowPlayingState(
            song = song,
            isPlaying = c?.isPlaying == true,
            shuffle = c?.shuffleModeEnabled == true,
            repeatMode = c?.repeatMode ?: Player.REPEAT_MODE_OFF,
            isFavorite = song != null && favorites.contains(song.id),
            volume = c?.volume ?: 0.8f,
            durationMs = c?.duration?.takeIf { it > 0 } ?: song?.durationMs ?: 0L
        )
    }

    private fun refreshNowPlaying() {
        screenAdapter.submitNowPlaying(currentState())
        updateNpDiscRotation()
    }

    private fun updateMiniPlayer() {
        val song = PlayerConnection.currentSong()
        if (song == null) {
            binding.miniPlayerBar.visibility = View.GONE
            return
        }
        binding.miniPlayerBar.visibility = View.VISIBLE
        binding.miniTitle.text = song.title
        binding.miniArtist.text = song.artist
        binding.miniPlayPause.setImageResource(
            if (PlayerConnection.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateProgress() {
        val c = PlayerConnection.controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: return
        val position = c.currentPosition
        binding.miniProgress.max = duration.toInt()
        binding.miniProgress.progress = position.toInt()
        npHolder?.binding?.let { b ->
            if (!b.npSeekBar.isPressed) {
                b.npSeekBar.max = duration.toInt()
                b.npSeekBar.progress = position.toInt()
            }
            b.npCurrentTime.text = formatTime(position)
            b.npTotalTime.text = formatTime(duration)
        }
    }

    private fun updateNpDiscRotation() {
        val holder = npHolder ?: return
        val playing = PlayerConnection.isPlaying()
        if (discAnimator == null) {
            discAnimator = ObjectAnimator.ofFloat(holder.binding.discCard, "rotation", 0f, 360f).apply {
                duration = 12000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        val anim = discAnimator!!
        if (playing) {
            if (!anim.isStarted) anim.start() else if (anim.isPaused) anim.resume()
        } else if (anim.isStarted && !anim.isPaused) {
            anim.pause()
        }
    }

    // ----- MainScreenAdapter.Callbacks -----

    override fun onTrackClick(song: Song, indexInList: Int) {
        PlayerConnection.playSongs(filteredSongs(), indexInList)
    }

    override fun onTrackFavorite(song: Song) {
        if (favorites.contains(song.id)) {
            FavoritesStore.removeFavorite(this, song.id)
        } else {
            FavoritesStore.addFavorite(this, song.id)
        }
        favorites = FavoritesStore.getFavorites(this)
        refreshLibrary()
        refreshNowPlaying()
    }

    override fun onTrackAddToPlaylist(song: Song) {
        AddToPlaylistDialog.show(this, song)
    }

    override fun onTrackPlayNext(song: Song) {
        PlayerConnection.playNext(song)
        Toast.makeText(this, getString(R.string.added_next), Toast.LENGTH_SHORT).show()
    }

    override fun onPlaylistClick(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java)
        intent.putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
        startActivity(intent)
    }

    override fun onPlaylistLongPress(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_playlist))
            .setMessage(getString(R.string.delete_playlist_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                PlaylistStore.delete(this, playlist.id)
                refreshPlaylists()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onCreatePlaylist() {
        CreatePlaylistDialog.show(this)
    }

    override fun onNpPlayPause() = PlayerConnection.togglePlayPause()

    override fun onNpNext() = PlayerConnection.next()

    override fun onNpPrev() = PlayerConnection.previous()

    override fun onNpShuffle() {
        PlayerConnection.toggleShuffle()
        refreshNowPlaying()
    }

    override fun onNpRepeat() {
        PlayerConnection.cycleRepeat()
        refreshNowPlaying()
    }

    override fun onNpSeekTo(positionMs: Long) = PlayerConnection.seekTo(positionMs)

    override fun onNpVolume(volume: Float) = PlayerConnection.setVolume(volume)

    override fun onNpFavorite() {
        val song = PlayerConnection.currentSong() ?: return
        if (favorites.contains(song.id)) {
            FavoritesStore.removeFavorite(this, song.id)
        } else {
            FavoritesStore.addFavorite(this, song.id)
        }
        favorites = FavoritesStore.getFavorites(this)
        refreshLibrary()
        refreshNowPlaying()
    }

    override fun onNpOpenFull() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onNpCardBound(holder: MainScreenAdapter.NpCardViewHolder) {
        npHolder = holder
        discAnimator = null
        updateNpDiscRotation()
    }

    override fun onNpCardUnbound() {
        discAnimator?.cancel()
        discAnimator = null
        npHolder = null
    }

    // ----- in-app updates from GitHub -----

    private fun checkForUpdate(manual: Boolean) {
        if (updateInProgress) return
        lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest()
            if (latest == null) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            if (!UpdateChecker.isNewer(latest.tag, BuildConfig.VERSION_NAME)) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_none),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.update_available_title, latest.versionName))
                .setMessage(getString(R.string.update_available_msg, latest.versionName))
                .setPositiveButton(getString(R.string.update_install)) { _, _ ->
                    startUpdate(latest)
                }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
        }
    }

    private fun startUpdate(release: ReleaseInfo) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingRelease = release
            Toast.makeText(
                this,
                getString(R.string.update_allow_install),
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }
        downloadAndInstall(release)
    }

    private fun downloadAndInstall(release: ReleaseInfo) {
        if (updateInProgress) return
        updateInProgress = true
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading, release.versionName))
            .setView(progressBar)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@MainActivity, release.apkUrl) { progress ->
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.progress = progress
                }
            }
            dialog.dismiss()
            updateInProgress = false
            if (file == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_download_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            if (!UpdateChecker.installApk(this@MainActivity, file)) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_install_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
