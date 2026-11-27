package com.musicplayer.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.FragmentSongsBinding

class SongsFragment : Fragment() {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private var adapter: SongAdapter? = null
    private var pendingSongs: List<Song> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = SongAdapter(
            pendingSongs,
            onClick = { _, position ->
                (activity as? MainActivity)?.playSongAt(adapter?.currentList() ?: emptyList(), position)
            },
            onAddToPlaylist = { song ->
                (activity as? MainActivity)?.showAddToPlaylistDialog(song)
            },
            onPlayNext = { song ->
                (activity as? MainActivity)?.playNext(song)
            }
        )
        binding.songsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.songsRecycler.adapter = adapter
        updateEmptyState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setSongs(songs: List<Song>) {
        pendingSongs = songs
        adapter?.let {
            it.submitList(songs)
            updateEmptyState()
        }
    }

    fun filter(query: String) {
        adapter?.filter(query)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val empty = adapter?.itemCount == 0
        _binding?.let {
            it.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }
}
