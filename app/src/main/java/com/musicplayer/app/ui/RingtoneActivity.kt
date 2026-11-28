package com.musicplayer.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
 * default ringtone. MP3 is cut as a byte range (no re-encoding); every
 * other format is decoded and re-encoded to AAC (.m4a) so the result is a
 * valid file no matter the source container. For exact trimming the user
 * can refine the range with the sliders.
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

    /** Pending ringtone creation while a write-permission request is in flight. */
    private var pendingCreateTitle: String? = null

    /** Android 9 and below: WRITE_EXTERNAL_STORAGE, requested just in time
     * (same pattern as the tag editor, v1.0.9). Without it the MediaStore
     * insert throws SecurityException "Permission Denial" and the cut fails
     * with a bare "Impossible de créer la sonnerie" — 7 identical reports
     * on v1.0.10, fixed here in v1.0.12. */
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCreateTitle?.let { createRingtone(it) }
        } else {
            binding.createButton.isEnabled = true
            binding.statusText.text = getString(R.string.ringtone_need_permission)
        }
        pendingCreateTitle = null
    }

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
        // Android 9 and below: writing a new entry to MediaStore needs
        // WRITE_EXTERNAL_STORAGE. Ask just in time (the v1.0.9 tag-editor
        // fix added the permission to the manifest but this screen never
        // requested it, so every cut failed with Permission Denial).
        // On Android 10+ the permission is not declared (maxSdkVersion 28)
        // and the app's own MediaStore entry needs no permission.
        if (Build.VERSION.SDK_INT < 30 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCreateTitle = title
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
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
                    // The failure reason is shown, not just the generic line:
                    // a denied permission and an unsupported format each get
                    // their own message (v1.0.12).
                    binding.statusText.text = when (result.kind) {
                        CutResult.Kind.PERMISSION ->
                            getString(R.string.ringtone_need_permission)
                        CutResult.Kind.UNSUPPORTED_FORMAT ->
                            getString(R.string.ringtone_format_unsupported, result.detail ?: "")
                        CutResult.Kind.GENERIC -> getString(R.string.ringtone_failed)
                    }
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

        class Fail(
            val message: String,
            val kind: Kind = Kind.GENERIC,
            /** Extra detail for the UI message (e.g. the file extension). */
            val detail: String? = null
        ) : CutResult()

        enum class Kind { GENERIC, PERMISSION, UNSUPPORTED_FORMAT }
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
            val isMp3 = ext.equals("mp3", ignoreCase = true)
            // A byte-range cut only works for MP3: on any other container
            // it produces a file without a header that no player can open.
            // Non-MP3 sources are re-encoded to AAC (.m4a) instead.
            val outExt = if (isMp3) ext else "m4a"
            val fileName = "${title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()}_sonnerie.$outExt"

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(outExt))
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
            try {
                if (isMp3) byteCut(src, uri) else transcodeCut(src, uri)
            } catch (e: Exception) {
                // Roll back the half-written entry so the library does not
                // keep a broken ringtone file.
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                throw e
            }
            CutResult.Ok(uri)
        } catch (e: SecurityException) {
            // Missing WRITE_EXTERNAL_STORAGE on Android 9 (the v1.0.10 bug:
            // 7 identical "Permission Denial" reports). The permission gate
            // in createRingtone normally prevents this; this is the safety
            // net for a permission revoked mid-flight.
            CutResult.Fail(e.message ?: "permission denial", CutResult.Kind.PERMISSION)
        } catch (e: UnsupportedFormatException) {
            CutResult.Fail(
                e.message ?: "unsupported format",
                CutResult.Kind.UNSUPPORTED_FORMAT,
                e.format
            )
        } catch (e: Exception) {
            CutResult.Fail(e.message ?: "unknown error")
        }
    }

    /** MP3 byte-range cut (no re-encoding): estimate byte offsets from the
     * duration ratio. MP3 frames are not re-encoded; the cut lands on a
     * frame boundary only approximately — acceptable for a ringtone. */
    private fun byteCut(src: File, uri: Uri) {
        // Byte-range cut: estimate byte offsets from the duration ratio.
        // (MP3 frames are not re-encoded; the cut lands on a frame boundary
        // only approximately — acceptable for a ringtone.)
        val totalBytes = src.length()
        val startByte = (totalBytes * startMs / durationMs.coerceAtLeast(1)).coerceIn(0, totalBytes)
        val endByte = (totalBytes * endMs / durationMs.coerceAtLeast(1)).coerceIn(startByte + 1, totalBytes)

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
        } ?: throw IllegalStateException("output stream failed")
    }

    /** Thrown when MediaExtractor cannot open the source (a format only the
     * VLC engine reads — APE, DSD, WavPack, MPC, tracker, TAK…): the cut
     * cannot be done on-device, and the user gets a clear message naming
     * the format instead of the generic failure. */
    private class UnsupportedFormatException(val format: String) :
        Exception("unsupported format for cutting: $format")

    /**
     * Trims a non-MP3 source to [startMs, endMs] by decoding it and
     * re-encoding the range to AAC in an M4A container (MediaCodec +
     * MediaMuxer). Works for every format MediaExtractor can read
     * (AAC/M4A, FLAC, OGG/Vorbis, Opus, WAV — WAV is already PCM so it
     * skips the decoder). Runs on the caller's thread; call from a
     * background thread.
     */
    private fun transcodeCut(src: File, uri: Uri) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(src.absolutePath)
            } catch (e: Exception) {
                throw UnsupportedFormatException(src.extension.ifEmpty { "?" })
            }
            var trackIndex = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val m = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    break
                }
            }
            if (trackIndex < 0) throw UnsupportedFormatException(src.extension.ifEmpty { "?" })
            extractor.selectTrack(trackIndex)
            val srcFormat = extractor.getTrackFormat(trackIndex)
            val sampleRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channels = if (srcFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val needsDecoder = mime != "audio/raw" // WAV is already PCM
            val decoder = if (needsDecoder) {
                try {
                    MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    throw UnsupportedFormatException(src.extension.ifEmpty { "?" })
                }
            } else null
            val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("output stream failed")
            try {
                val muxer = MediaMuxer(
                    pfd.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                var muxerTrack = -1
                var muxerStarted = false
                try {
                    decoder?.configure(srcFormat, null, null, 0)
                    encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    decoder?.start()
                    encoder.start()
                    val startUs = startMs * 1000L
                    val endUs = endMs * 1000L
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    var decoderEos = false
                    var encoderEos = false
                    var sawEncoderFormat = false
                    val bufferInfo = MediaCodec.BufferInfo()
                    val deadlineMs = System.currentTimeMillis() + 120_000L
                    while (!encoderEos && System.currentTimeMillis() < deadlineMs) {
                        // Feed the decoder (or the encoder directly for PCM).
                        if (!decoderEos) {
                            if (needsDecoder) {
                                val inIndex = decoder!!.dequeueInputBuffer(10_000)
                                if (inIndex >= 0) {
                                    val buf = decoder.getInputBuffer(inIndex)!!
                                    val size = extractor.readSampleData(buf, 0)
                                    if (size < 0 || extractor.sampleTime < 0 ||
                                        extractor.sampleTime > endUs
                                    ) {
                                        decoder.queueInputBuffer(
                                            inIndex, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        decoderEos = true
                                    } else {
                                        decoder.queueInputBuffer(
                                            inIndex, 0, size, extractor.sampleTime, 0
                                        )
                                        extractor.advance()
                                    }
                                }
                            } else {
                                val inIndex = encoder.dequeueInputBuffer(10_000)
                                if (inIndex >= 0) {
                                    val buf = encoder.getInputBuffer(inIndex)!!
                                    val size = extractor.readSampleData(buf, 0)
                                    if (size < 0 || extractor.sampleTime < 0 ||
                                        extractor.sampleTime > endUs
                                    ) {
                                        encoder.queueInputBuffer(
                                            inIndex, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        decoderEos = true
                                    } else {
                                        encoder.queueInputBuffer(
                                            inIndex, 0, size, extractor.sampleTime, 0
                                        )
                                        extractor.advance()
                                    }
                                }
                            }
                        }
                        // Drain the decoder into the encoder.
                        if (needsDecoder) {
                            val outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 10_000)
                            if (outIndex >= 0) {
                                val outBuf = decoder.getOutputBuffer(outIndex)
                                if (bufferInfo.size > 0 && outBuf != null) {
                                    val inIndex = encoder.dequeueInputBuffer(10_000)
                                    if (inIndex >= 0) {
                                        val inBuf = encoder.getInputBuffer(inIndex)!!
                                        inBuf.clear()
                                        val chunk = outBuf.duplicate()
                                        chunk.position(bufferInfo.offset)
                                        chunk.limit(bufferInfo.offset + bufferInfo.size)
                                        inBuf.put(chunk)
                                        encoder.queueInputBuffer(
                                            inIndex, 0, bufferInfo.size,
                                            bufferInfo.presentationTimeUs, 0
                                        )
                                    }
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    val inIndex = encoder.dequeueInputBuffer(10_000)
                                    if (inIndex >= 0) {
                                        encoder.queueInputBuffer(
                                            inIndex, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                    }
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                            }
                        }
                        // Drain the encoder into the muxer.
                        val encIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                        when {
                            encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!sawEncoderFormat) {
                                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                    sawEncoderFormat = true
                                }
                            }
                            encIndex >= 0 -> {
                                val outBuf = encoder.getOutputBuffer(encIndex)
                                if (bufferInfo.size > 0 && outBuf != null && muxerStarted) {
                                    val pts = bufferInfo.presentationTimeUs - startUs
                                    if (pts in 0..(endUs - startUs)) {
                                        bufferInfo.presentationTimeUs = pts
                                        muxer.writeSampleData(muxerTrack, outBuf, bufferInfo)
                                    }
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    encoderEos = true
                                }
                                encoder.releaseOutputBuffer(encIndex, false)
                            }
                        }
                    }
                    if (!encoderEos) throw IllegalStateException("transcode timed out")
                    if (!muxerStarted) throw IllegalStateException("no audio encoded")
                } finally {
                    try { if (muxerStarted) muxer.stop() } catch (_: Exception) { }
                    try { muxer.release() } catch (_: Exception) { }
                    try { decoder?.stop() } catch (_: Exception) { }
                    try { decoder?.release() } catch (_: Exception) { }
                    try { encoder.stop() } catch (_: Exception) { }
                    try { encoder.release() } catch (_: Exception) { }
                }
            } finally {
                try { pfd.close() } catch (_: Exception) { }
            }
        } finally {
            extractor.release()
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
