package com.musicplayer.app.ui

import android.content.ComponentName
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicplayer.app.R
import com.musicplayer.app.data.AlbumArtLoader
import com.musicplayer.app.databinding.ActivityLockBinding
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection

/**
 * Full lock screen — shown over the lock screen (showWhenLocked) when the
 * user taps the playback notification while the phone is locked, if the
 * lock-screen option is on. Blurred cover background + full controls:
 * play/pause, previous/next, seek, volume, close.
 */
class LockActivity : FullScreenActivity() {

    // v1.0.16 (m24): the seek bar used to be set once per track and
    // then frozen — add a 1 s ticker so it follows playback.
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var seekTracking = false
    private val seekTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val controller = PlayerConnection.controller
            if (controller != null && !seekTracking) {
                val duration = controller.duration.takeIf { it > 0 }
                    ?: PlayerConnection.currentSong()?.durationMs ?: 0L
                if (duration > 0) binding.seekBar.max = duration.toInt()
                binding.seekBar.progress =
                    controller.currentPosition.toInt().coerceIn(0, binding.seekBar.max)
            }
            seekHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var binding: ActivityLockBinding

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateSongInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.playPauseButton.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.nextButton.setOnClickListener { PlayerConnection.next() }
        binding.previousButton.setOnClickListener { PlayerConnection.previous() }
        binding.closeButton.setOnClickListener { finish() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekTracking = false
            }
        })
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.setVolumePercent(progress, this@LockActivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.volumeBar.progress = com.musicplayer.app.data.PlaybackStateStore.loadVolume(this)
        connectController()
    }

    // v1.0.8: the slider follows the DEVICE volume (hardware keys,
    // notification, Android Auto) — updated live via PlayerConnection.
    private val volumeSyncListener: (Int) -> Unit = { percent ->
        binding.volumeBar.progress = percent
    }

    override fun onStart() {
        super.onStart()
        PlayerConnection.addVolumeListener(volumeSyncListener)
        connectController()
        seekHandler.post(seekTicker)
    }

    override fun onStop() {
        super.onStop()
        seekHandler.removeCallbacks(seekTicker)
        PlayerConnection.removeVolumeListener(volumeSyncListener)
        PlayerConnection.controller?.removeListener(playerListener)
    }

    private fun connectController() {
        val existing = PlayerConnection.controller
        if (existing != null) {
            existing.addListener(playerListener)
            updateSongInfo()
            updatePlayButton()
            return
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                PlayerConnection.controller = c
                c.addListener(playerListener)
                updateSongInfo()
                updatePlayButton()
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateSongInfo() {
        val song = PlayerConnection.currentSong()
        binding.songTitle.text = song?.title ?: getString(R.string.no_song)
        binding.songTitle.isSelected = true
        binding.songArtist.text = song?.artist ?: getString(R.string.from_device)
        val duration = PlayerConnection.controller?.duration?.takeIf { it > 0 }
            ?: song?.durationMs ?: 0L
        binding.seekBar.max = duration.toInt()
        if (song != null) {
            binding.coverView.tag = song.id
            Thread {
                try {
                    val bitmap = AlbumArtLoader.loadTrackCover(this, song)
                    runOnUiThread {
                        if (binding.coverView.tag != song.id) return@runOnUiThread
                        if (bitmap != null) {
                            binding.coverView.setImageBitmap(bitmap)
                            binding.bgCover.setImageBitmap(bitmap)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    binding.bgCover.setRenderEffect(
                                        RenderEffect.createBlurEffect(
                                            28f, 28f, Shader.TileMode.CLAMP
                                        )
                                    )
                                } catch (e: Exception) {
                                }
                            }
                            binding.bgCover.alpha = 0.55f
                        } else {
                            binding.coverView.setImageResource(R.drawable.zeno_logo_transparent)
                            binding.bgCover.setImageDrawable(null)
                        }
                    }
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    private fun updatePlayButton() {
        val playing = PlayerConnection.controller?.isPlaying == true
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }
}
