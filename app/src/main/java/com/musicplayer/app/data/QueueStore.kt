package com.musicplayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.musicplayer.app.report.ErrorReporter
import java.util.UUID

/**
 * Multiple independent queues (Musicolet-style): up to 20 saved queues,
 * each with its own name, song list, current index and position — so the
 * user can switch between queues and resume each one where it stopped.
 *
 * The "current" queue is always saved automatically; named queues are
 * created explicitly from the full player screen.
 */
object QueueStore {

    private const val FILE_NAME = "queues.json"
    const val MAX_QUEUES = 20

    data class SavedQueue(
        val id: String,
        val name: String,
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long,
        val updatedAt: Long
    )

    fun load(context: Context): MutableList<SavedQueue> {
        val result = mutableListOf<SavedQueue>()
        try {
            val text = context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ids = mutableListOf<Long>()
                val idsArr = obj.optJSONArray("songIds") ?: JSONArray()
                for (j in 0 until idsArr.length()) ids.add(idsArr.getLong(j))
                result.add(
                    SavedQueue(
                        obj.optString("id", UUID.randomUUID().toString()),
                        obj.optString("name", "File"),
                        ids,
                        obj.optInt("index", 0),
                        obj.optLong("positionMs", 0L),
                        obj.optLong("updatedAt", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            // No file yet: start empty (a missing file on first run is normal).
            if (context.getFileStreamPath(FILE_NAME).exists()) {
                ErrorReporter.log(
                    ErrorReporter.TYPE_DATABASE,
                    "Queues file unreadable: ${e.message}",
                    e
                )
            }
        }
        return result
    }

    fun save(context: Context, queues: List<SavedQueue>) {
        try {
            val arr = JSONArray()
            for (q in queues) {
                val obj = JSONObject()
                obj.put("id", q.id)
                obj.put("name", q.name)
                val ids = JSONArray()
                for (id in q.songIds) ids.put(id)
                obj.put("songIds", ids)
                obj.put("index", q.index)
                obj.put("positionMs", q.positionMs)
                obj.put("updatedAt", q.updatedAt)
                arr.put(obj)
            }
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use {
                it.write(arr.toString())
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_DATABASE,
                "Queues save failed: ${e.message}",
                e
            )
        }
    }

    /** Saves (or updates) the queue with [id]; drops the oldest queues past the cap. */
    fun upsert(context: Context, queue: SavedQueue) {
        val lists = load(context)
        lists.removeAll { it.id == queue.id }
        lists.add(0, queue)
        val trimmed = lists.take(MAX_QUEUES)
        save(context, trimmed)
    }

    fun create(context: Context, name: String, songIds: List<Long>, index: Int, positionMs: Long): SavedQueue {
        val queue = SavedQueue(
            UUID.randomUUID().toString(), name, songIds, index, positionMs,
            System.currentTimeMillis()
        )
        upsert(context, queue)
        return queue
    }

    fun delete(context: Context, queueId: String) {
        val lists = load(context)
        lists.removeAll { it.id == queueId }
        save(context, lists)
    }
}
