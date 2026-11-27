package com.musicplayer.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.tabs.TabLayoutMediator
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityMainBinding
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private var songs: List<Song> = emptyList()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

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
            updateMiniPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateMiniPlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.tab_songs) else getString(R.string.tab_playlists)
        }.attach()

        binding.miniPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.miniNext.setOnClickListener {
            controller?.seekToNext()
        }
        binding.miniPlayerBar.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                pagerAdapter.getSongsFragment()?.filter(newText ?: "")
                return true
            }
        })

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                PlayerConnection.controller = controller
                controller?.addListener(playerListener)
                updateMiniPlayer()
            } catch (e: Exception) {
                // Connection failed; playback controls stay hidden until reconnect.
            }
        }, ContextCompat.getMainExecutor(this))
    }

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
        songs = MusicRepository.loadSongs(this)
        pagerAdapter.getSongsFragment()?.setSongs(songs)
        if (songs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_music_files), Toast.LENGTH_LONG).show()
        }
    }

    fun playSongAt(list: List<Song>, position: Int) {
        PlayerConnection.playSongs(list, position)
    }

    fun playNext(song: Song) {
        PlayerConnection.playNext(song)
    }

    fun showAddToPlaylistDialog(song: Song) {
        AddToPlaylistDialog.show(this, song)
    }

    fun showCreatePlaylistDialog() {
        CreatePlaylistDialog.show(this)
    }

    fun refreshPlaylists() {
        pagerAdapter.getPlaylistsFragment()?.refresh()
    }

    fun openPlaylist(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java)
        intent.putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
        startActivity(intent)
    }

    fun confirmDeletePlaylist(playlist: Playlist) {
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

    private fun updateMiniPlayer() {
        val c = controller ?: return
        val metadata = c.currentMediaItem?.mediaMetadata
        if (metadata == null) {
            binding.miniPlayerBar.visibility = View.GONE
            return
        }
        binding.miniPlayerBar.visibility = View.VISIBLE
        binding.miniTitle.text = metadata.title?.toString() ?: ""
        binding.miniArtist.text = metadata.artist?.toString() ?: ""
        binding.miniPlayPause.setImageResource(
            if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }
}
