package com.musicplayer.app.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(private val activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return if (position == 0) SongsFragment() else PlaylistsFragment()
    }

    fun getSongsFragment(): SongsFragment? {
        return activity.supportFragmentManager.findFragmentByTag("f0") as? SongsFragment
    }

    fun getPlaylistsFragment(): PlaylistsFragment? {
        return activity.supportFragmentManager.findFragmentByTag("f1") as? PlaylistsFragment
    }
}
