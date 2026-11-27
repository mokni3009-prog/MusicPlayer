package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.databinding.ActivityStatsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listening statistics — total listening time, play counts, top artists
 * and top songs. All data stays on the device (privacy-first).
 */
class StatsActivity : FullScreenActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, StatsActivity::class.java))
        }
    }

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.resetButton.setOnClickListener {
            PlaybackStateStore.clearStats(this)
            loadStats()
        }
        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val library = MusicRepository.loadSongs(this@StatsActivity)
                val store = PlaybackStateStore
                val totalSeconds = store.totalListenSeconds(this@StatsActivity)
                val counts = store.playCounts(this@StatsActivity)
                val byArtist = HashMap<String, Long>()
                val bySong = ArrayList<Pair<String, Long>>()
                for (song in library) {
                    val c = counts[song.id] ?: 0L
                    if (c > 0) {
                        byArtist[song.artist] = (byArtist[song.artist] ?: 0L) + c
                        bySong.add(song.title to c)
                    }
                }
                val topArtists = byArtist.entries.sortedByDescending { it.value }.take(5)
                val topSongs = bySong.sortedByDescending { it.second }.take(5)
                Triple(totalSeconds, topArtists, topSongs)
            }
            val (totalSeconds, topArtists, topSongs) = stats
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            binding.totalTimeValue.text = getString(R.string.stats_hours_minutes, hours, minutes)
            binding.totalPlaysValue.text = stats.second.sumOf { it.value }.toString()

            binding.topArtistsText.text = if (topArtists.isEmpty()) {
                getString(R.string.stats_no_data)
            } else {
                topArtists.mapIndexed { i, (artist, count) ->
                    "${i + 1}. $artist — ${getString(R.string.stats_plays, count)}"
                }.joinToString("\n")
            }
            binding.topSongsText.text = if (topSongs.isEmpty()) {
                getString(R.string.stats_no_data)
            } else {
                topSongs.mapIndexed { i, (title, count) ->
                    "${i + 1}. $title — ${getString(R.string.stats_plays, count)}"
                }.joinToString("\n")
            }
        }
    }
}
