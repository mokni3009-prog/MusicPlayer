package com.musicplayer.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Song
import com.musicplayer.app.data.TagEditor
import com.musicplayer.app.databinding.ActivityTagEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            context.startActivity(intent(context, songIds))
        }

        fun intent(context: Context, songIds: List<Long>): Intent {
            val intent = Intent(context, TagEditActivity::class.java)
            intent.putExtra(EXTRA_SONG_IDS, songIds.toLongArray())
            return intent
        }
    }

    private lateinit var binding: ActivityTagEditBinding
    private var songIds: LongArray = longArrayOf()
    private var songs: List<Song> = emptyList()

    /** Pending save while a write-permission request is in flight. */
    private var pendingChanges: TagEditor.TagChanges? = null

    /** True once a write grant was already requested for this save, so a
     * denied grant does not loop forever (v1.0.16 M3). */
    private var permissionRetryDone = false

    /** Android 9 and below: WRITE_EXTERNAL_STORAGE, requested just in time. */
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingChanges?.let { doSave(it) }
        } else {
            binding.saveButton.isEnabled = true
            binding.statusText.text = getString(R.string.tag_edit_need_permission)
        }
        pendingChanges = null
    }

    /** Android 10+: MediaStore write request (system consent dialog). */
    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingChanges?.let { doSave(it) }
        } else {
            binding.saveButton.isEnabled = true
            binding.statusText.text = getString(R.string.tag_edit_need_permission)
        }
        pendingChanges = null
    }

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
        val song = songs.find { it.id == songId } ?: return null
        // v1.0.16 (M3): reads through the content Uri (temp file), not
        // the MediaStore DATA column — works on Android 10+ too.
        return TagEditor.readTags(this, song)
    }

    private fun save() {
        val changes = TagEditor.TagChanges(
            binding.titleInput.text.toString().trim().ifEmpty { null },
            binding.artistInput.text.toString().trim().ifEmpty { null },
            binding.albumInput.text.toString().trim().ifEmpty { null },
            binding.genreInput.text.toString().trim().ifEmpty { null },
            binding.yearInput.text.toString().trim().ifEmpty { null }
        )

        binding.saveButton.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.tag_edit_saving)
        pendingChanges = changes
        permissionRetryDone = false
        doSave(changes)
    }

    /** Runs the actual tag write. On a missing write grant (v1.0.16 M3)
     * the grant is requested once and the save retried. */
    private fun doSave(changes: TagEditor.TagChanges) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                TagEditor.applyTags(this@TagEditActivity, songs, changes)
            }
            if (result.needsPermission && !permissionRetryDone) {
                permissionRetryDone = true
                pendingChanges = changes
                if (Build.VERSION.SDK_INT < 30) {
                    if (ContextCompat.checkSelfPermission(
                            this@TagEditActivity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // Permission granted but the file is still not
                        // writable — run the save so the failure is logged.
                        pendingChanges = null
                        doSave(changes)
                    } else {
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                } else {
                    try {
                        val uris = result.failedUris.ifEmpty { songs.map { it.uri } }
                        val pi = MediaStore.createWriteRequest(contentResolver, uris)
                        writeRequestLauncher.launch(
                            IntentSenderRequest.Builder(pi.intentSender).build()
                        )
                    } catch (e: Exception) {
                        pendingChanges = null
                        binding.saveButton.isEnabled = true
                        binding.statusText.text = getString(R.string.tag_edit_need_permission)
                    }
                }
                return@launch
            }
            binding.saveButton.isEnabled = true
            when {
                result.failed.isEmpty() -> {
                    // MediaStore's cached tags are updated by TagEditor
                    // itself (contentResolver.update); MainActivity also
                    // reloads on resume (TagEditor.lastSuccessfulEditMs).
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
