package com.musicplayer.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.TagEditor
import com.musicplayer.app.databinding.ActivityRingtoneBinding
import com.musicplayer.app.report.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Ringtone cutter — picks a segment (start/end) of a song and saves it as
 * a ringtone in the system Ringtones folder, then offers to set it as the
 * default ringtone. The cut is a byte-range copy (no re-encoding); for
 * exact trimming the user can refine the range with the sliders.
 */
class RingtoneActivity : FullScreenActivity() {

    companion object {
        const val EXTRA_SONG_ID = "song_id"

        fun start(context: Context, songId: Long) {
            context.startActivity(intent(context, songId))
        }

        fun intent(context: Context, songId: Long): Intent {
            val intent = Intent(context, RingtoneActivity::class.java)
            intent.putExtra(EXTRA_SONG_ID, songId)
            return intent
        }
    }

    private lateinit var binding: ActivityRingtoneBinding
    private var songId: Long = -1L
    private var durationMs: Long = 0L
    private var startMs: Long = 0L
    private var endMs: Long = 30_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRingtoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1L)
        binding.backButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            val song = withContext(Dispatchers.IO) {
                MusicRepository.loadSongs(this@RingtoneActivity).find { it.id == songId }
            }
            if (song == null) {
                Toast.makeText(this@RingtoneActivity, R.string.ringtone_no_song, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            binding.songTitle.text = song.title
            binding.songTitle.isSelected = true
            binding.songArtist.text = song.artist
            durationMs = song.durationMs
            endMs = minOf(30_000L, durationMs)
            setupSliders()
            binding.createButton.setOnClickListener { createRingtone(song.title) }
        }
    }

    private fun setupSliders() {
        val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
        binding.startSeek.max = seconds
        binding.endSeek.max = seconds
        binding.startSeek.progress = 0
        binding.endSeek.progress = (endMs / 1000).toInt()
        updateLabels()
        binding.startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                startMs = progress * 1000L
                if (startMs >= endMs) {
                    endMs = minOf(startMs + 1000, durationMs)
                    binding.endSeek.progress = (endMs / 1000).toInt()
                }
                updateLabels()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.endSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                endMs = progress * 1000L
                if (endMs <= startMs) {
                    startMs = maxOf(0L, endMs - 1000)
                    binding.startSeek.progress = (startMs / 1000).toInt()
                }
                updateLabels()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun updateLabels() {
        binding.startValue.text = formatTime(startMs)
        binding.endValue.text = formatTime(endMs)
        binding.lengthValue.text = getString(
            R.string.ringtone_length,
            formatTime(endMs - startMs)
        )
    }

    private fun createRingtone(title: String) {
        lifecycleScope.launch {
            binding.createButton.isEnabled = false
            binding.statusText.text = getString(R.string.ringtone_creating)
            val result = withContext(Dispatchers.IO) { cutAndSave(title) }
            binding.createButton.isEnabled = true
            when (result) {
                is CutResult.Ok -> {
                    binding.statusText.text = getString(R.string.ringtone_done)
                    Toast.makeText(this@RingtoneActivity, R.string.ringtone_done, Toast.LENGTH_SHORT).show()
                    RingtoneManager.setActualDefaultRingtoneUri(
                        this@RingtoneActivity,
                        RingtoneManager.TYPE_RINGTONE,
                        result.uri
                    )
                }
                is CutResult.Fail -> {
                    binding.statusText.text = getString(R.string.ringtone_failed)
                    ErrorReporter.log(
                        ErrorReporter.TYPE_LIBRARY,
                        "Ringtone cut failed: ${result.message}"
                    )
                }
            }
        }
    }

    private sealed class CutResult {
        class Ok(val uri: Uri) : CutResult()
        class Fail(val message: String) : CutResult()
    }

    private fun cutAndSave(title: String): CutResult {
        return try {
            val song = MusicRepository.loadSongs(this).find { it.id == songId }
                ?: return CutResult.Fail("song not found")
            // Resolve the file path through MediaStore (may be empty on
            // Android 10+ — those songs cannot be cut and are reported).
            val path = TagEditor.filePathsFor(this, listOf(songId))[songId]
                ?: return CutResult.Fail("file path unavailable")
            val src = File(path)
            if (!src.exists()) return CutResult.Fail("source file missing")
            val ext = src.extension.ifEmpty { "mp3" }
            val fileName = "${title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()}_sonnerie.$ext"

            // Byte-range cut: estimate byte offsets from the duration ratio.
            // (MP3 frames are not re-encoded; the cut lands on a frame boundary
            // only approximately — acceptable for a ringtone.)
            val totalBytes = src.length()
            val startByte = (totalBytes * startMs / durationMs.coerceAtLeast(1)).coerceIn(0, totalBytes)
            val endByte = (totalBytes * endMs / durationMs.coerceAtLeast(1)).coerceIn(startByte + 1, totalBytes)

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(ext))
                put(MediaStore.Audio.Media.IS_RINGTONE, true)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                put(MediaStore.Audio.Media.IS_ALARM, false)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/")
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return CutResult.Fail("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { input ->
                    input.skip(startByte)
                    var remaining = endByte - startByte
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.flush()
                }
            } ?: return CutResult.Fail("output stream failed")
            CutResult.Ok(uri)
        } catch (e: Exception) {
            CutResult.Fail(e.message ?: "unknown error")
        }
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }
}
