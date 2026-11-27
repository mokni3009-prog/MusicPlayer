package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Song
import com.musicplayer.app.data.TagEditor
import com.musicplayer.app.databinding.ActivityTagEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Batch tag editor — title, artist, album, genre, year for one or several
 * songs. jaudiotagger writes the tags back into the audio files.
 * Files whose path is not reachable (scoped storage) are reported as
 * failed, never silently dropped.
 */
class TagEditActivity : FullScreenActivity() {

    companion object {
        const val EXTRA_SONG_IDS = "song_ids"

        fun start(context: Context, songIds: List<Long>) {
            val intent = Intent(context, TagEditActivity::class.java)
            intent.putExtra(EXTRA_SONG_IDS, songIds.toLongArray())
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityTagEditBinding
    private var songIds: LongArray = longArrayOf()
    private var songs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        songIds = intent.getLongArrayExtra(EXTRA_SONG_IDS) ?: longArrayOf()
        binding.backButton.setOnClickListener { finish() }
        binding.countLabel.text = getString(R.string.tag_edit_count, songIds.size)

        lifecycleScope.launch {
            songs = withContext(Dispatchers.IO) {
                MusicRepository.loadSongs(this@TagEditActivity)
                    .filter { it.id in songIds.toSet() }
            }
            if (songIds.size == 1 && songs.isNotEmpty()) {
                // Pre-fill with the song's current tags.
                val tags = withContext(Dispatchers.IO) { readTags(songIds[0]) }
                if (tags != null) {
                    binding.titleInput.setText(tags.title)
                    binding.artistInput.setText(tags.artist)
                    binding.albumInput.setText(tags.album)
                    binding.genreInput.setText(tags.genre)
                    if (!tags.year.isNullOrBlank()) binding.yearInput.setText(tags.year)
                } else {
                    val song = songs[0]
                    binding.titleInput.setText(song.title)
                    binding.artistInput.setText(song.artist)
                    binding.albumInput.setText(song.album)
                }
            }
        }

        binding.saveButton.setOnClickListener { save() }
    }

    /** Reads the current tags of one song (for pre-fill). Null when unreadable. */
    private fun readTags(songId: Long): TagEditor.TagChanges? {
        return try {
            val paths = TagEditor.filePathsFor(this, listOf(songId))
            val path = paths[songId] ?: return null
            val file = File(path)
            if (!file.exists()) return null
            val tag = AudioFileIO.read(file).tag ?: return null
            TagEditor.TagChanges(
                title = tag.getFirst(FieldKey.TITLE).ifBlank { null },
                artist = tag.getFirst(FieldKey.ARTIST).ifBlank { null },
                album = tag.getFirst(FieldKey.ALBUM).ifBlank { null },
                genre = tag.getFirst(FieldKey.GENRE).ifBlank { null },
                year = tag.getFirst(FieldKey.YEAR).ifBlank { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun save() {
        val title = binding.titleInput.text.toString().trim().ifEmpty { null }
        val artist = binding.artistInput.text.toString().trim().ifEmpty { null }
        val album = binding.albumInput.text.toString().trim().ifEmpty { null }
        val genre = binding.genreInput.text.toString().trim().ifEmpty { null }
        val year = binding.yearInput.text.toString().trim().ifEmpty { null }

        binding.saveButton.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.tag_edit_saving)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val paths = TagEditor.filePathsFor(this@TagEditActivity, songIds.toList())
                TagEditor.applyTags(
                    songs,
                    paths,
                    TagEditor.TagChanges(title, artist, album, genre, year)
                )
            }
            binding.saveButton.isEnabled = true
            when {
                result.failed.isEmpty() -> {
                    Toast.makeText(
                        this@TagEditActivity,
                        getString(R.string.tag_edit_done, result.updated),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                result.updated > 0 -> {
                    binding.statusText.text = getString(
                        R.string.tag_edit_partial, result.updated, result.failed.size
                    )
                }
                else -> {
                    binding.statusText.text = getString(R.string.tag_edit_failed_all)
                }
            }
        }
    }
}
