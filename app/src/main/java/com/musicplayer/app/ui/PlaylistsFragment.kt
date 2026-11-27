package com.musicplayer.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.data.PlaylistStore
import com.musicplayer.app.databinding.FragmentPlaylistsBinding

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private var adapter: PlaylistAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = PlaylistAdapter(
            emptyList(),
            onClick = { playlist ->
                (activity as? MainActivity)?.openPlaylist(playlist)
            },
            onDelete = { playlist ->
                (activity as? MainActivity)?.confirmDeletePlaylist(playlist)
            }
        )
        binding.playlistsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.playlistsRecycler.adapter = adapter
        binding.createPlaylistFab.setOnClickListener {
            (activity as? MainActivity)?.showCreatePlaylistDialog()
        }
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun refresh() {
        if (!isAdded) return
        val lists = PlaylistStore.load(requireContext())
        adapter?.submitList(lists)
        _binding?.let {
            it.emptyText.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
