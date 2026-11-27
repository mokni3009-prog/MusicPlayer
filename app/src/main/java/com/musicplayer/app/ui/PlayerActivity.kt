package com.musicplayer.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivityPlayerBinding
import com.musicplayer.app.player.PlayerConnection

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateSongInfo()
            updateShuffleRepeat()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateShuffleRepeat()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateShuffleRepeat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.playPauseButton.setOnClickListener {
            val c = PlayerConnection.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.nextButton.setOnClickListener {
            PlayerConnection.controller?.seekToNext()
        }
        binding.previousButton.setOnClickListener {
            PlayerConnection.controller?.seekToPrevious()
        }
        binding.shuffleButton.setOnClickListener {
            val c = PlayerConnection.controller ?: return@setOnClickListener
            c.shuffleModeEnabled = !c.shuffleModeEnabled
        }
        binding.repeatButton.setOnClickListener {
            val c = PlayerConnection.controller ?: return@setOnClickListener
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
        binding.addToPlaylistButton.setOnClickListener {
            val c = PlayerConnection.controller ?: return@setOnClickListener
            val mediaId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return@setOnClickListener
            val song = PlayerConnection.songs.find { it.id == mediaId } ?: return@setOnClickListener
            AddToPlaylistDialog.show(this, song)
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    PlayerConnection.controller?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        PlayerConnection.controller?.addListener(playerListener)
        updateSongInfo()
        updatePlayButton()
        updateShuffleRepeat()
        handler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateSongInfo() {
        val c = PlayerConnection.controller
        val metadata = c?.currentMediaItem?.mediaMetadata
        binding.songTitle.text = metadata?.title?.toString() ?: getString(R.string.no_song)
        binding.songArtist.text = metadata?.artist?.toString() ?: ""
        val duration = c?.duration?.takeIf { it > 0 } ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.totalTime.text = formatTime(duration)
    }

    private fun updateProgress() {
        val c = PlayerConnection.controller ?: return
        val pos = c.currentPosition
        if (!binding.seekBar.isPressed) {
            binding.seekBar.progress = pos.toInt()
        }
        binding.currentTime.text = formatTime(pos)
    }

    private fun updatePlayButton() {
        val playing = PlayerConnection.controller?.isPlaying == true
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateShuffleRepeat() {
        val c = PlayerConnection.controller ?: return
        binding.shuffleButton.alpha = if (c.shuffleModeEnabled) 1.0f else 0.4f
        binding.repeatBadge.visibility =
            if (c.repeatMode == Player.REPEAT_MODE_ONE) View.VISIBLE else View.GONE
        binding.repeatButton.alpha = if (c.repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1.0f
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
