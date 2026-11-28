package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.data.HistoryStore
import com.musicplayer.app.player.PlayerConnection
import java.text.DateFormat
import java.util.Date

/**
 * v1.0.16 — listening history screen (feature 6): what was played and
 * when. Tapping an entry plays that song again (from the start);
 * the clear button wipes the history.
 */
class HistoryActivity : FullScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.historyBackButton)?.setOnClickListener { finish() }
        findViewById<View>(R.id.historyClearButton)?.setOnClickListener {
            HistoryStore.clear(this)
            loadHistory()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
        val recycler = findViewById<RecyclerView>(R.id.historyRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        loadHistory()
    }

    private fun loadHistory() {
        val entries = HistoryStore.load(this)
        val recycler = findViewById<RecyclerView>(R.id.historyRecycler)
        val empty = findViewById<View>(R.id.historyEmpty)
        recycler.adapter = HistoryAdapter(entries) { entry ->
            val library = PlayerConnection.songs
            val index = library.indexOfFirst { it.id == entry.songId }
            if (index < 0) {
                Toast.makeText(this, R.string.history_song_missing, Toast.LENGTH_SHORT).show()
                return@HistoryAdapter
            }
            PlayerConnection.playSongs(library, index)
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, HistoryActivity::class.java)
        }
    }

    private class HistoryAdapter(
        private val entries: List<HistoryStore.HistoryEntry>,
        private val onClick: (HistoryStore.HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.historyTitle)
            val subtitle: TextView = view.findViewById(R.id.historySubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.title.text = entry.title.ifBlank { holder.itemView.context.getString(R.string.unknown_title) }
            val whenText = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT
            ).format(Date(entry.playedAt))
            holder.subtitle.text = if (entry.artist.isBlank()) {
                whenText
            } else {
                "${entry.artist} • $whenText"
            }
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }
}
