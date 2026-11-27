package com.musicplayer.app.ui

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.R
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityPlayerBinding
import com.musicplayer.app.player.FavoritesStore
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.VisualizerHub

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var queueAdapter: QueueAdapter
    private var discAnimator: ObjectAnimator? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            updateDiscRotation()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateSongInfo()
            updateShuffleRepeat()
            updateQueue()
            updateFavButton()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateShuffleRepeat()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateShuffleRepeat()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            updateQueue()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (PlayerConnection.controller == controller) PlayerConnection.controller = null
            controller.removeListener(playerListener)
            // The service rebuilt its session (playback fallback). Reconnect.
            if (!isFinishing && !isDestroyed) connectController()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        queueAdapter = QueueAdapter(emptyList()) { song ->
            val c = PlayerConnection.controller ?: return@QueueAdapter
            val index = PlayerConnection.songs.indexOf(song)
            if (index >= 0) {
                c.seekToDefaultPosition(index)
                c.play()
            }
        }
        binding.queueRecycler.layoutManager = LinearLayoutManager(this)
        binding.queueRecycler.adapter = queueAdapter

        binding.backButton.setOnClickListener { finish() }

        binding.playPauseButton.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.nextButton.setOnClickListener { PlayerConnection.next() }
        binding.previousButton.setOnClickListener { PlayerConnection.previous() }
        binding.shuffleButton.setOnClickListener {
            PlayerConnection.toggleShuffle()
            updateShuffleRepeat()
        }
        binding.repeatButton.setOnClickListener {
            PlayerConnection.cycleRepeat()
            updateShuffleRepeat()
        }
        binding.favButton.setOnClickListener {
            val song = PlayerConnection.currentSong() ?: return@setOnClickListener
            if (FavoritesStore.isFavorite(this, song.id)) {
                FavoritesStore.removeFavorite(this, song.id)
            } else {
                FavoritesStore.addFavorite(this, song.id)
            }
            updateFavButton()
        }
        binding.addToPlaylistButton.setOnClickListener {
            val song = PlayerConnection.currentSong() ?: return@setOnClickListener
            AddToPlaylistDialog.show(this, song)
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.setVolume(progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        connectController()
        VisualizerHub.listener = { magnitudes -> binding.visualizerView.updateMagnitudes(magnitudes) }
        handler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        VisualizerHub.listener = null
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        discAnimator?.cancel()
        discAnimator = null
        super.onDestroy()
    }

    private fun connectController() {
        val existing = PlayerConnection.controller
        if (existing != null) {
            existing.addListener(playerListener)
            refreshAll()
            return
        }
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        future.addListener({
            try {
                val c = future.get()
                PlayerConnection.controller = c
                c.addListener(playerListener)
                refreshAll()
            } catch (e: Exception) {
                // Controller build failed; the mini player / library still works.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshAll() {
        updateSongInfo()
        updatePlayButton()
        updateShuffleRepeat()
        updateQueue()
        updateFavButton()
        updateDiscRotation()
        val volume = PlayerConnection.controller?.volume ?: 0.8f
        binding.volumeBar.progress = (volume * 100).toInt()
    }

    private fun updateSongInfo() {
        val song = PlayerConnection.currentSong()
        val c = PlayerConnection.controller
        binding.songTitle.text = song?.title ?: getString(R.string.no_song)
        binding.songArtist.text = song?.artist ?: getString(R.string.from_device)
        val duration = c?.duration?.takeIf { it > 0 } ?: song?.durationMs ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.totalTime.text = formatTime(duration)
        if (song != null) {
            binding.discArt.setImageResource(R.drawable.zeno_logo)
        }
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
        binding.visualizerView.setPlaying(playing)
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateShuffleRepeat() {
        val c = PlayerConnection.controller ?: return
        binding.shuffleButton.alpha = if (c.shuffleModeEnabled) 1.0f else 0.4f
        binding.shuffleButton.setColorFilter(
            getColor(if (c.shuffleModeEnabled) R.color.zeno_cyan else R.color.zeno_ink2)
        )
        binding.repeatBadge.visibility =
            if (c.repeatMode == Player.REPEAT_MODE_ONE) View.VISIBLE else View.GONE
        binding.repeatButton.alpha = if (c.repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1.0f
        binding.repeatButton.setColorFilter(
            getColor(if (c.repeatMode == Player.REPEAT_MODE_OFF) R.color.zeno_ink2 else R.color.zeno_cyan)
        )
    }

    private fun updateFavButton() {
        val song = PlayerConnection.currentSong()
        val fav = song != null && FavoritesStore.isFavorite(this, song.id)
        binding.favButton.setImageResource(
            if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        binding.favButton.setColorFilter(
            getColor(if (fav) R.color.zeno_cyan else R.color.zeno_ink3)
        )
    }

    private fun updateQueue() {
        val songs = PlayerConnection.songs
        val current = PlayerConnection.currentIndex()
        val upcoming: List<Song> = if (current in songs.indices) {
            songs.subList(current + 1, songs.size)
        } else {
            emptyList()
        }
        queueAdapter.submitList(upcoming)
    }

    private fun updateDiscRotation() {
        val playing = PlayerConnection.controller?.isPlaying == true
        if (discAnimator == null) {
            discAnimator = ObjectAnimator.ofFloat(binding.discCard, "rotation", 0f, 360f).apply {
                duration = 12000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        val anim = discAnimator!!
        if (playing) {
            if (!anim.isStarted) anim.start() else if (anim.isPaused) anim.resume()
        } else if (anim.isStarted && !anim.isPaused) {
            anim.pause()
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
