package com.musicplayer.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.app.BuildConfig
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
import com.musicplayer.app.data.BackupManager
import com.musicplayer.app.data.FavoritesStore
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityMainBinding
import com.musicplayer.app.player.LyricsManager
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.VolumeBoost
import com.musicplayer.app.report.ErrorReporter
import com.musicplayer.app.update.ReleaseInfo
import com.musicplayer.app.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Zeno Play main screen (v1.0.4): brand header with the app name on ONE
 * line, 6 tabs (CHANSONS / ALBUMS / ARTISTES / PLAYLISTS / DOSSIERS /
 * PARAMÈTRES), the song library, the mini player, and the settings tab
 * (language, formats, volume boost, notification preview, lyrics,
 * equalizer, tags, backup, battery, statistics, AMOLED, Android Auto,
 * privacy, about, updates).
 *
 * No category chips and no formats line in the library (removed by order).
 * All containers are match_parent / 0dp + weight — no fixed screen sizes.
 * Force full screen (immersive) via [FullScreenActivity].
 */
class MainActivity : FullScreenActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class Tab(val labelRes: Int) {
        SONGS(R.string.tab_songs),
        ALBUMS(R.string.tab_albums),
        ARTISTS(R.string.tab_artists),
        PLAYLISTS(R.string.tab_playlists),
        FOLDERS(R.string.tab_folders),
        SETTINGS(R.string.tab_settings)
    }

    private sealed class Filter {
        object None : Filter()
        data class Album(val name: String) : Filter()
        data class Artist(val name: String) : Filter()
        data class Folder(val name: String) : Filter()
        data class Playlist(val playlist: Playlist) : Filter()
        data class Smart(val type: SmartType) : Filter()
        object Favorites : Filter()
    }

    private enum class SmartType {
        ALL, MOST_PLAYED, RECENTLY_ADDED, RECENTLY_PLAYED
    }

    private var allSongs: List<Song> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var favorites: Set<Long> = emptySet()
    private var currentTab: Tab = Tab.SONGS
    private var currentFilter: Filter = Filter.None
    private var searchQuery: String = ""

    private lateinit var songAdapter: SongAdapter
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var smartAdapter: GroupAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private val tabViews = mutableListOf<Pair<Tab, View>>()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var controllerReady = false
    private var libraryReady = false
    private var restoreDone = false

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
            // v1.0.3: report denied audio permission so we know the user
            // sees an empty library because of a permission, not a bug.
            ErrorReporter.log(
                ErrorReporter.TYPE_PERMISSION,
                "Audio permission denied by user (library will be empty)"
            )
            Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            var displayName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) displayName = cursor.getString(idx)
            }
            val name = displayName ?: "Audio"
            val title = name.substringBeforeLast('.', name)
            val song = Song(
                id = -System.currentTimeMillis(),
                title = title,
                artist = getString(R.string.from_device),
                album = "",
                durationMs = 0L,
                uri = uri,
                fileName = name,
                folder = "",
                albumId = -1L,
                dateAdded = System.currentTimeMillis() / 1000,
                sizeBytes = 0L
            )
            allSongs = allSongs + song
            PlayerConnection.playSongs(allSongs, allSongs.indexOf(song))
            refreshContent()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_LIBRARY,
                "Picked audio file failed: ${e.message}",
                e
            )
        }
    }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val backup = BackupManager.buildBackup(this, allSongs)
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(BackupManager.serialize(backup).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.backup_exported), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Backup export failed: ${e.message}",
                e
            )
            Toast.makeText(this, getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() } ?: return@registerForActivityResult
            val result = BackupManager.restore(this, text, allSongs)
            if (result.ok) {
                playlists = PlaylistStore.load(this)
                favorites = FavoritesStore.getFavorites(this)
                refreshContent()
                Toast.makeText(this, getString(R.string.backup_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Backup import failed: ${e.message}",
                e
            )
            Toast.makeText(this, getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private var m3uExportPlaylist: Playlist? = null
    private val createM3uLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val playlist = m3uExportPlaylist ?: return@registerForActivityResult
        try {
            val songs = playlist.songIds.mapNotNull { id -> allSongs.firstOrNull { it.id == id } }
            val ok = BackupManager.exportM3u(this, playlist, songs, uri)
            Toast.makeText(
                this,
                getString(if (ok) R.string.m3u_exported else R.string.m3u_failed),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.m3u_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private val openM3uLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            var displayName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) displayName = cursor.getString(idx)
            }
            val songs = BackupManager.importM3u(this, uri, allSongs)
            if (songs.isEmpty()) {
                Toast.makeText(this, getString(R.string.m3u_failed), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val name = displayName?.substringBeforeLast('.', displayName) ?: "M3U"
            val lists = PlaylistStore.load(this)
            lists.add(
                Playlist(
                    java.util.UUID.randomUUID().toString(),
                    name,
                    songs.map { it.id }.toMutableList()
                )
            )
            PlaylistStore.save(this, lists)
            playlists = lists
            refreshContent()
            Toast.makeText(
                this,
                getString(R.string.m3u_imported, songs.size),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "M3U import failed: ${e.message}",
                e
            )
            Toast.makeText(this, getString(R.string.m3u_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshContent()
            updateMiniPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshContent()
            updateMiniPlayer()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (PlayerConnection.controller == controller) PlayerConnection.controller = null
            controller.removeListener(playerListener)
            controllerReady = false
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

    // ----- lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        songAdapter = SongAdapter(
            songs = emptyList(),
            onClick = { song, index -> PlayerConnection.playSongs(filteredSongs(), index) },
            onAddToPlaylist = { song -> AddToPlaylistDialog.show(this, song) },
            onPlayNext = { song ->
                PlayerConnection.playNext(song)
                Toast.makeText(this, getString(R.string.added_next), Toast.LENGTH_SHORT).show()
            },
            onRemove = null,
            onFavorite = { song -> toggleFavorite(song) },
            onEditTags = { song -> openTagEditor(song) },
            onCreateRingtone = { song -> openRingtone(song) }
        )
        groupAdapter = GroupAdapter(emptyList(), onClick = { item -> onGroupClick(item) })
        smartAdapter = GroupAdapter(emptyList(), onClick = { item -> onGroupClick(item) })
        playlistAdapter = PlaylistAdapter(
            emptyList(),
            onClick = { playlist -> openPlaylist(playlist) },
            onDelete = { playlist -> confirmDeletePlaylist(playlist) }
        )
        binding.mainRecycler.layoutManager = LinearLayoutManager(this)
        binding.mainRecycler.adapter = songAdapter

        buildTabs()
        buildFormatChips()
        wireSettings()

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshContent()
            }
        })
        binding.addButton.setOnClickListener { pickAudioLauncher.launch("audio/*") }
        binding.filterClear.setOnClickListener {
            currentFilter = Filter.None
            refreshContent()
        }
        binding.updateButton.setOnClickListener { checkForUpdate(manual = true) }
        binding.miniPlayPause.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.miniNext.setOnClickListener { PlayerConnection.next() }
        binding.miniPrev.setOnClickListener { PlayerConnection.previous() }
        binding.miniOpen.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        connectController()
        handler.post(progressRunnable)
        favorites = FavoritesStore.getFavorites(this)
        playlists = PlaylistStore.load(this)
        refreshContent()
        refreshBatteryStatus()
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
        refreshBatteryStatus()
    }

    override fun onStop() {
        super.onStop()
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
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
            controllerReady = true
            tryRestore()
            refreshContent()
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
                controllerReady = true
                tryRestore()
                refreshContent()
                updateMiniPlayer()
            } catch (e: Exception) {
                // Connection failed; playback controls stay hidden until reconnect.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Restores the last queue (paused) once the controller and library are ready. */
    private fun tryRestore() {
        if (restoreDone || !controllerReady || !libraryReady) return
        restoreDone = true
        try {
            val c = PlayerConnection.controller ?: return
            val hadItems = c.mediaItemCount > 0
            PlayerConnection.restoreSavedQueue(this, allSongs)
            if (!hadItems && c.mediaItemCount > 0) {
                Toast.makeText(this, getString(R.string.restore_queue_toast), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Resume is best-effort; a failure must never break the launch.
        }
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
        // v1.0.3: a library-load failure must never kill the launch.
        try {
            allSongs = MusicRepository.loadSongs(this)
            favorites = FavoritesStore.getFavorites(this)
            playlists = PlaylistStore.load(this)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_LIBRARY,
                "Library load failed in MainActivity: ${e.message}",
                e
            )
            allSongs = emptyList()
        }
        libraryReady = true
        refreshContent()
        tryRestore()
        if (allSongs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_music_files), Toast.LENGTH_LONG).show()
        } else {
            maybeShowBatteryDialog()
        }
    }

    // ----- tabs -----

    private fun buildTabs() {
        binding.tabsRow.removeAllViews()
        tabViews.clear()
        for (tab in Tab.values()) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), 0)
                isClickable = true
                isFocusable = true
            }
            val label = TextView(this).apply {
                text = getString(tab.labelRes)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val underline = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
                ).apply { topMargin = dp(7) }
                setBackgroundResource(R.drawable.bg_tab_underline)
            }
            container.addView(label)
            container.addView(underline)
            container.setOnClickListener { setTab(tab) }
            binding.tabsRow.addView(container)
            tabViews.add(tab to container)
        }
        updateTabSelection()
    }

    private fun updateTabSelection() {
        for ((tab, container) in tabViews) {
            val selected = tab == currentTab
            val label = (container as LinearLayout).getChildAt(0) as TextView
            val underline = container.getChildAt(1)
            label.setTextColor(
                getColor(if (selected) R.color.zeno_gold else R.color.zeno_ink2)
            )
            underline.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun setTab(tab: Tab) {
        currentTab = tab
        updateTabSelection()
        val isSettings = tab == Tab.SETTINGS
        binding.mainRecycler.visibility = if (isSettings) View.GONE else View.VISIBLE
        binding.settingsScroll.visibility = if (isSettings) View.VISIBLE else View.GONE
        binding.searchRow.visibility = if (tab == Tab.SONGS) View.VISIBLE else View.GONE
        binding.counterText.visibility = if (tab == Tab.SONGS) View.VISIBLE else View.GONE
        binding.filterBanner.visibility =
            if (tab == Tab.SONGS && currentFilter != Filter.None) View.VISIBLE else View.GONE
        binding.mainRecycler.layoutManager = when (tab) {
            Tab.ALBUMS -> GridLayoutManager(this, if (AppTheme.isTablet(this)) 3 else 2)
            else -> LinearLayoutManager(this)
        }
        refreshContent()
    }

    // ----- content -----

    private fun filteredSongs(): List<Song> {
        val q = searchQuery.trim()
        return allSongs.filter { song ->
            val filterOk = when (val f = currentFilter) {
                is Filter.None -> true
                is Filter.Album -> song.album == f.name
                is Filter.Artist -> song.artist == f.name
                is Filter.Folder -> song.folder == f.name
                is Filter.Playlist -> f.playlist.songIds.contains(song.id)
                is Filter.Smart -> when (f.type) {
                    SmartType.ALL -> true
                    SmartType.MOST_PLAYED -> PlayerConnection.mostPlayed(this, allSongs).contains(song)
                    SmartType.RECENTLY_ADDED -> PlayerConnection.recentlyAdded(allSongs).contains(song)
                    SmartType.RECENTLY_PLAYED -> PlayerConnection.recentlyPlayed(this, allSongs).contains(song)
                }
                is Filter.Favorites -> favorites.contains(song.id)
            }
            val queryOk = q.isEmpty() || song.matchesQuery(q)
            filterOk && queryOk
        }
    }

    private fun refreshContent() {
        if (!::songAdapter.isInitialized) return
        val current = PlayerConnection.currentSong()
        when (currentTab) {
            Tab.SONGS -> {
                val list = filteredSongs()
                binding.mainRecycler.adapter = songAdapter
                songAdapter.submitList(list)
                songAdapter.submitState(favorites, current?.id, PlayerConnection.isPlaying())
                binding.counterText.text = getString(R.string.songs_count, list.size)
            }
            Tab.ALBUMS -> {
                binding.mainRecycler.adapter = groupAdapter
                val items = MusicRepository.albumsOf(allSongs).map { album ->
                    GroupAdapter.GroupItem(
                        id = "album:${album.name}",
                        title = album.name,
                        subtitle = "${album.artist} · ${getString(R.string.songs_count, album.songs.size)}",
                        card = true,
                        tag = Filter.Album(album.name)
                    )
                }
                groupAdapter.submitList(items)
            }
            Tab.ARTISTS -> {
                binding.mainRecycler.adapter = groupAdapter
                val items = MusicRepository.artistsOf(allSongs).map { artist ->
                    GroupAdapter.GroupItem(
                        id = "artist:${artist.name}",
                        title = artist.name,
                        subtitle = getString(R.string.songs_count, artist.songs.size),
                        tag = Filter.Artist(artist.name)
                    )
                }
                groupAdapter.submitList(items)
            }
            Tab.PLAYLISTS -> {
                val smartItems = listOf(
                    GroupAdapter.GroupItem(
                        "smart:all", getString(R.string.all_songs),
                        getString(R.string.all_library_sub), tag = Filter.Smart(SmartType.ALL)
                    ),
                    GroupAdapter.GroupItem(
                        "smart:most", getString(R.string.most_played),
                        getString(R.string.smart_playlist_sub),
                        tag = Filter.Smart(SmartType.MOST_PLAYED)
                    ),
                    GroupAdapter.GroupItem(
                        "smart:recent", getString(R.string.recently_added),
                        getString(R.string.smart_playlist_sub),
                        tag = Filter.Smart(SmartType.RECENTLY_ADDED)
                    ),
                    GroupAdapter.GroupItem(
                        "smart:played", getString(R.string.recently_played),
                        getString(R.string.smart_playlist_sub),
                        tag = Filter.Smart(SmartType.RECENTLY_PLAYED)
                    ),
                    GroupAdapter.GroupItem(
                        "smart:fav", getString(R.string.filter_favorites),
                        getString(R.string.favorites_sub), tag = Filter.Favorites
                    ),
                    GroupAdapter.GroupItem(
                        "create", getString(R.string.create_playlist), "",
                        tag = "create"
                    )
                )
                smartAdapter.submitList(smartItems)
                playlistAdapter.submitList(playlists)
                binding.mainRecycler.adapter = ConcatAdapter(smartAdapter, playlistAdapter)
            }
            Tab.FOLDERS -> {
                binding.mainRecycler.adapter = groupAdapter
                val items = MusicRepository.foldersOf(allSongs).map { folder ->
                    GroupAdapter.GroupItem(
                        id = "folder:${folder.name}",
                        title = folder.name,
                        subtitle = getString(R.string.songs_count, folder.songs.size),
                        tag = Filter.Folder(folder.name)
                    )
                }
                groupAdapter.submitList(items)
            }
            Tab.SETTINGS -> {
                // Static settings content; nothing to refresh here except
                // the notification preview, handled by updateMiniPlayer().
            }
        }
        updateFilterBanner()
        updateMiniPlayer()
    }

    private fun updateFilterBanner() {
        val label = when (val f = currentFilter) {
            is Filter.None -> null
            is Filter.Album -> getString(R.string.filter_album, f.name)
            is Filter.Artist -> getString(R.string.filter_artist, f.name)
            is Filter.Folder -> getString(R.string.filter_folder, f.name)
            is Filter.Playlist -> f.playlist.name
            is Filter.Smart -> when (f.type) {
                SmartType.ALL -> getString(R.string.all_songs)
                SmartType.MOST_PLAYED -> getString(R.string.most_played)
                SmartType.RECENTLY_ADDED -> getString(R.string.recently_added)
                SmartType.RECENTLY_PLAYED -> getString(R.string.recently_played)
            }
            is Filter.Favorites -> getString(R.string.filter_favorites)
        }
        binding.filterBanner.visibility =
            if (currentTab == Tab.SONGS && label != null) View.VISIBLE else View.GONE
        if (label != null) binding.filterLabel.text = label
    }

    private fun onGroupClick(item: GroupAdapter.GroupItem) {
        when (val tag = item.tag) {
            "create" -> CreatePlaylistDialog.show(this)
            is Filter -> {
                currentFilter = tag
                setTab(Tab.SONGS)
            }
        }
    }

    private fun openPlaylist(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java)
        intent.putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
        startActivity(intent)
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
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

    fun refreshPlaylists() {
        playlists = PlaylistStore.load(this)
        if (currentTab == Tab.PLAYLISTS) refreshContent()
    }

    private fun toggleFavorite(song: Song) {
        if (favorites.contains(song.id)) {
            FavoritesStore.removeFavorite(this, song.id)
        } else {
            FavoritesStore.addFavorite(this, song.id)
        }
        favorites = FavoritesStore.getFavorites(this)
        refreshContent()
    }

    private fun openTagEditor(song: Song) {
        val intent = Intent(this, TagEditActivity::class.java)
        intent.putExtra(TagEditActivity.EXTRA_SONG_IDS, longArrayOf(song.id))
        startActivity(intent)
    }

    private fun openRingtone(song: Song) {
        val intent = Intent(this, RingtoneActivity::class.java)
        intent.putExtra(RingtoneActivity.EXTRA_SONG_ID, song.id)
        startActivity(intent)
    }

    // ----- mini player -----

    private fun updateMiniPlayer() {
        val song = PlayerConnection.currentSong()
        if (song == null) {
            binding.miniPlayerBar.visibility = View.GONE
            binding.notifTitle.text = getString(R.string.no_song)
            binding.notifArtist.text = ""
            return
        }
        binding.miniPlayerBar.visibility = View.VISIBLE
        binding.miniTitle.text = song.title
        binding.miniArtist.text = song.artist
        binding.miniPlayPause.setImageResource(
            if (PlayerConnection.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
        val index = PlayerConnection.currentIndex()
        val total = PlayerConnection.songs.size
        binding.miniPosition.text =
            if (index >= 0 && total > 0) "${index + 1}/$total" else ""
        // Hide prev/next on narrow screens (the reference keeps only play).
        val narrow = resources.configuration.screenWidthDp < 560
        binding.miniPrev.visibility = if (narrow) View.GONE else View.VISIBLE
        binding.miniNext.visibility = if (narrow) View.GONE else View.VISIBLE
        // Notification preview in the settings tab.
        binding.notifTitle.text = song.title
        binding.notifArtist.text = song.artist
        binding.notifPlayPause.setImageResource(
            if (PlayerConnection.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
        // Real cover (background thread, cached); fallback = the Z logo
        // without any square (the transparent mark).
        binding.miniCover.tag = song.id
        Thread {
            try {
                val bitmap = AlbumArtLoader.loadTrackCover(this, song)
                runOnUiThread {
                    if (binding.miniCover.tag != song.id) return@runOnUiThread
                    if (bitmap != null) {
                        binding.miniCover.setImageBitmap(bitmap)
                        binding.notifCover.setImageBitmap(bitmap)
                    } else {
                        binding.miniCover.setImageResource(R.drawable.zeno_logo_mark)
                        binding.notifCover.setImageResource(R.drawable.zeno_logo_mark)
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
        updateProgress()
    }

    private fun updateProgress() {
        val duration = PlayerConnection.durationMs()
        val position = PlayerConnection.currentPositionMs()
        if (duration > 0) {
            binding.miniProgress.max = 1000
            binding.miniProgress.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        } else {
            binding.miniProgress.progress = 0
        }
    }

    // ----- settings -----

    private fun buildFormatChips() {
        val formats = listOf(
            "MP3", "AAC", "M4A", "FLAC", "WAV", "OGG", "OPUS",
            "WMA", "AIFF", "ALAC", "AMR", "MIDI"
        )
        val grid = binding.formatsGrid
        grid.removeAllViews()
        for (format in formats) {
            val chip = TextView(this).apply {
                text = format
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.zeno_ink2))
                setBackgroundResource(R.drawable.bg_chip)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            val params = GridLayout.LayoutParams().apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(chip, params)
        }
    }

    private fun wireSettings() {
        updateLanguageSelection()
        binding.langFrBtn.setOnClickListener { changeLanguage(LocaleHelper.LANG_FR) }
        binding.langArBtn.setOnClickListener { changeLanguage(LocaleHelper.LANG_AR) }
        binding.langEnBtn.setOnClickListener { changeLanguage(LocaleHelper.LANG_EN) }

        // Volume boost 0..200%.
        val volume = PlaybackStateStore.loadVolume(this)
        binding.volumeSeek.progress = volume
        updateVolumeUi(volume)
        binding.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                PlayerConnection.setVolumePercent(progress, this@MainActivity)
                updateVolumeUi(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Notification preview buttons (functional).
        binding.notifPrev.setOnClickListener { PlayerConnection.previous() }
        binding.notifPlayPause.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.notifNext.setOnClickListener { PlayerConnection.next() }
        binding.notifClose.setOnClickListener { PlayerConnection.pause() }

        // Lyrics auto-fetch (opt-in, OFF by default).
        binding.lyricsSwitch.isChecked = LyricsManager.isAutoFetchEnabled(this)
        binding.lyricsSwitch.setOnCheckedChangeListener { _, checked ->
            LyricsManager.setAutoFetchEnabled(this, checked)
        }

        binding.eqOpenBtn.setOnClickListener {
            startActivity(Intent(this, EqActivity::class.java))
        }
        binding.tagsOpenBtn.setOnClickListener {
            startActivity(Intent(this, TagEditActivity::class.java))
        }
        binding.statsOpenBtn.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.backupExportBtn.setOnClickListener {
            createBackupLauncher.launch("zeno-play-backup.json")
        }
        binding.backupImportBtn.setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json"))
        }
        binding.m3uExportBtn.setOnClickListener { pickPlaylistForM3uExport() }
        binding.m3uImportBtn.setOnClickListener {
            openM3uLauncher.launch(arrayOf("*/*"))
        }

        binding.batteryBtn.setOnClickListener { requestBatteryExemption() }
        binding.updateCheckBtn.setOnClickListener { checkForUpdate(manual = true) }

        // Contact us on WhatsApp (report a problem / suggest a feature).
        binding.contactBtn.setOnClickListener { openWhatsAppChat() }

        // AMOLED black theme.
        binding.amoledSwitch.isChecked = AppTheme.isAmoled(this)
        binding.amoledSwitch.setOnCheckedChangeListener { _, checked ->
            AppTheme.setAmoled(this, checked)
            recreate()
        }
    }

    private fun updateLanguageSelection() {
        val lang = LocaleHelper.savedLanguage(this)
        val buttons = mapOf(
            binding.langFrBtn to LocaleHelper.LANG_FR,
            binding.langArBtn to LocaleHelper.LANG_AR,
            binding.langEnBtn to LocaleHelper.LANG_EN
        )
        buttons.forEach { (button, code) ->
            val selected = code == lang
            button.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            button.setTextColor(
                getColor(if (selected) R.color.zeno_on_gradient else R.color.zeno_ink2)
            )
        }
    }

    private fun changeLanguage(lang: String) {
        if (lang == LocaleHelper.savedLanguage(this)) return
        LocaleHelper.setLanguage(this, lang)
        recreate()
    }

    private fun updateVolumeUi(percent: Int) {
        binding.volumeValue.text = "$percent%"
        if (percent > 100) {
            binding.boostBadge.visibility = View.VISIBLE
            binding.boostBadge.text = getString(R.string.boost_badge, percent - 100)
        } else {
            binding.boostBadge.visibility = View.GONE
        }
    }

    // ----- Contact us (WhatsApp) -----
    // Opens a WhatsApp chat with the developer (report a problem or suggest
    // a feature). If WhatsApp is not installed, the link opens in the browser.
    private fun openWhatsAppChat() {
        val url = "https://wa.me/21624835518"
        try {
            val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
            }
            startActivity(whatsappIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.contact_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickPlaylistForM3uExport() {
        if (playlists.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_playlists), Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.m3u_export))
            .setItems(names) { _, which ->
                m3uExportPlaylist = playlists[which]
                createM3uLauncher.launch("${playlists[which].name}.m3u")
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ----- battery optimization -----

    private fun isBatteryExempt(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshBatteryStatus() {
        if (!::binding.isInitialized) return
        binding.batteryStatus.text = getString(
            if (isBatteryExempt()) R.string.battery_exempted else R.string.battery_not_exempted
        )
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open the battery settings list.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }

    private fun maybeShowBatteryDialog() {
        try {
            val prefs = getSharedPreferences("zeno_onboarding", MODE_PRIVATE)
            if (prefs.getBoolean("battery_asked", false)) return
            if (isBatteryExempt()) return
            prefs.edit().putBoolean("battery_asked", true).apply()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_ask_title))
                .setMessage(getString(R.string.battery_ask_msg))
                .setPositiveButton(getString(R.string.battery_ask_button)) { _, _ ->
                    requestBatteryExemption()
                }
                .setNegativeButton(getString(R.string.battery_ask_later), null)
                .show()
        } catch (e: Exception) {
            // The guidance dialog must never break the launch.
        }
    }

    // ----- in-app updates from GitHub -----

    private fun checkForUpdate(manual: Boolean) {
        if (updateInProgress) return
        lifecycleScope.launch {
            try {
                val latest = UpdateChecker.fetchLatest()
                if (latest == null) {
                    if (manual) {
                        ErrorReporter.log(
                            ErrorReporter.TYPE_UPDATE,
                            "Manual update check failed (no response)"
                        )
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
            } catch (e: Exception) {
                // The update dialog / flow must never crash the app.
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update check flow failed: ${e.message}",
                    e
                )
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
        val progressBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
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
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update download failed for ${release.tag}"
                )
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_download_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            if (!UpdateChecker.installApk(this@MainActivity, file)) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update install failed for ${release.tag}"
                )
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_install_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
