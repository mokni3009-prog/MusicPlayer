package com.musicplayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PlaylistStore {

    private const val FILE_NAME = "playlists.json"

    fun load(context: Context): MutableList<Playlist> {
        val result = mutableListOf<Playlist>()
        try {
            val text = context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ids = mutableListOf<Long>()
                val idsArr = obj.optJSONArray("songIds") ?: JSONArray()
                for (j in 0 until idsArr.length()) {
                    ids.add(idsArr.getLong(j))
                }
                result.add(Playlist(obj.getString("id"), obj.getString("name"), ids))
            }
        } catch (e: Exception) {
            // No file yet, or unreadable: start with an empty list.
        }
        return result
    }

    fun save(context: Context, playlists: List<Playlist>) {
        try {
            val arr = JSONArray()
            for (p in playlists) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                val ids = JSONArray()
                for (id in p.songIds) {
                    ids.put(id)
                }
                obj.put("songIds", ids)
                arr.put(obj)
            }
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use {
                it.write(arr.toString())
            }
        } catch (e: Exception) {
            // Ignore save errors in this version.
        }
    }

    fun create(context: Context, name: String): Playlist {
        val lists = load(context)
        val playlist = Playlist(UUID.randomUUID().toString(), name)
        lists.add(playlist)
        save(context, lists)
        return playlist
    }

    fun delete(context: Context, playlistId: String) {
        val lists = load(context)
        lists.removeAll { it.id == playlistId }
        save(context, lists)
    }
}
