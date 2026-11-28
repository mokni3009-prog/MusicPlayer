package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.databinding.ActivityWrappedBinding
import java.util.Calendar

/**
 * Wrapped — the listening summary for the year (or the month):
 * total plays, total minutes, top songs and top artists, computed from
 * the play-event log (PlaybackStateStore). No account, no network.
 */
class WrappedActivity : FullScreenActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WrappedActivity::class.java))
        }
    }

    private lateinit var binding: ActivityWrappedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWrappedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        refreshPeriodButtons()
        binding.yearButton.setOnClickListener {
            LuxuryPrefs.setWrappedPeriod(this, 0)
            refreshPeriodButtons()
            render()
        }
        binding.monthButton.setOnClickListener {
            LuxuryPrefs.setWrappedPeriod(this, 1)
            refreshPeriodButtons()
            render()
        }
        render()
    }

    private fun refreshPeriodButtons() {
        val yearly = LuxuryPrefs.wrappedPeriod(this) == 0
        binding.yearButton.isSelected = yearly
        binding.monthButton.isSelected = !yearly
    }

    private fun render() {
        val yearly = LuxuryPrefs.wrappedPeriod(this) == 0
        val now = Calendar.getInstance()
        val (startMs, titleRes) = if (yearly) {
            val c = Calendar.getInstance().apply {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            c.timeInMillis to getString(R.string.wrapped_year_title, now.get(Calendar.YEAR))
        } else {
            val c = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val monthName = android.text.format.DateFormat.format("MMMM", now)
            c.timeInMillis to getString(R.string.wrapped_month_title, monthName)
        }
        binding.periodTitle.text = titleRes

        val library = MusicRepository.loadSongs(this)
        val byId = library.associateBy { it.id }
        val events = PlaybackStateStore.playEvents(this)
            .filter { it.timestampMs >= startMs }
        binding.emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.contentLayout.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        if (events.isEmpty()) return

        val totalMinutes = events.sumOf { byId[it.songId]?.durationMs ?: 0L } / 60_000L
        binding.playsValue.text = getString(R.string.wrapped_plays_value, events.size)
        binding.minutesValue.text = getString(R.string.wrapped_minutes_value, totalMinutes)

        val songCounts = events.groupingBy { it.songId }.eachCount()
            .entries.sortedByDescending { it.value }
        val artistCounts = events.groupingBy { byId[it.songId]?.artist ?: getString(R.string.from_device) }
            .eachCount().entries.sortedByDescending { it.value }

        val topSong = songCounts.firstOrNull()
        binding.topSongValue.text = topSong?.let { byId[it.key]?.title } ?: "—"
        val topArtist = artistCounts.firstOrNull()
        binding.topArtistValue.text = topArtist?.key ?: "—"

        binding.topSongsContainer.removeAllViews()
        for ((songId, count) in songCounts.take(5)) {
            val song = byId[songId] ?: continue
            addRow(binding.topSongsContainer, song.title, count)
        }
        binding.topArtistsContainer.removeAllViews()
        for ((artist, count) in artistCounts.take(5)) {
            addRow(binding.topArtistsContainer, artist, count)
        }
    }

    private fun addRow(container: android.view.ViewGroup, title: String, count: Int) {
        val row = TextView(this).apply {
            text = getString(R.string.wrapped_row, title, count)
            setTextColor(getColor(R.color.zeno_ink))
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }
        container.addView(row)
    }
}
