package com.musicplayer.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityPlaylistDetailBinding
import com.musicplayer.app.player.PlayerConnection

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var adapter: SongAdapter
    private var playlistId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID) ?: ""
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SongAdapter(
            emptyList(),
            onClick = { _, position ->
                PlayerConnection.playSongs(adapter.currentList(), position)
            },
            onAddToPlaylist = { song ->
                AddToPlaylistDialog.show(this, song)
            },
            onPlayNext = { song ->
                PlayerConnection.playNext(song)
            },
            onRemove = { song ->
                removeSong(song)
            }
        )
        binding.songsRecycler.layoutManager = LinearLayoutManager(this)
        binding.songsRecycler.adapter = adapter
        binding.addSongsButton.setOnClickListener { showAddSongsDialog() }
        loadSongs()
    }

    override fun onResume() {
        super.onResume()
        loadSongs()
    }

    private fun loadSongs() {
        val playlist = PlaylistStore.load(this).find { it.id == playlistId } ?: run {
            finish()
            return
        }
        supportActionBar?.title = playlist.name
        val all = MusicRepository.loadSongs(this)
        val playlistSongs = playlist.songIds.mapNotNull { id -> all.find { it.id == id } }
        adapter.submitList(playlistSongs)
        binding.emptyText.visibility = if (playlistSongs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removeSong(song: Song) {
        val lists = PlaylistStore.load(this)
        val playlist = lists.find { it.id == playlistId } ?: return
        playlist.songIds.remove(song.id)
        PlaylistStore.save(this, lists)
        loadSongs()
    }

    private fun showAddSongsDialog() {
        val all = MusicRepository.loadSongs(this)
        val playlist = PlaylistStore.load(this).find { it.id == playlistId } ?: return
        val titles = all.map { "${it.title} — ${it.artist}" }.toTypedArray()
        val checked = all.map { playlist.songIds.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Add songs")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val lists = PlaylistStore.load(this)
                val p = lists.find { it.id == playlistId } ?: return@setPositiveButton
                p.songIds.clear()
                for (i in all.indices) {
                    if (checked[i]) p.songIds.add(all[i].id)
                }
                PlaylistStore.save(this, lists)
                loadSongs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
