package com.musicplayer.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.data.FavoritesStore
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.data.QueueStore
import com.musicplayer.app.data.Song
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.musicplayer.app.databinding.ActivityPlayerBinding
import com.musicplayer.app.player.LyricsManager
import com.musicplayer.app.player.PlaybackService
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.VisualizerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Full player — Zeno Play v1.0.4.
 * Rotating circular cover, live visualizer (real on Media3 tracks, simulated
 * on VLC tracks), transport, volume 0-200% + BOOST, speed, sleep timer,
 * A-B repeat, play-once-then-next, synced lyrics (tap a line to seek),
 * reorderable queue (drag + swipe to remove) with save/load of named queues.
 */
class PlayerActivity : FullScreenActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var lyricsAdapter: LyricsAdapter
    private var discAnimator: ObjectAnimator? = null
    private var currentLyrics: List<LyricsManager.LyricLine> = emptyList()
    private var currentLyricsSongId: Long = -1L
    private var currentSpeed = 1.0f
    private var upcomingSongs: MutableList<Song> = mutableListOf()
    /** True when the disc shows the song's real cover (it rotates); the
     * logo fallback stays still so the Z and "Zeno Play" stay readable. */
    private var hasRealCover = false
    /** Beat-pulse level (0..1): fast attack on the bass hit, slow release. */
    private var pulseLevel = 0f
    /** Gyroscope tilt: the disc leans with the phone (luxury option). */
    private var sensorManager: SensorManager? = null
    private var gyroListener: SensorEventListener? = null
    private var tiltX = 0f
    private var tiltY = 0f
    private var lastBeatHapticMs = 0L
    private var kenBurnsAnimator: ObjectAnimator? = null

    private val pulseRunnable = object : Runnable {
        override fun run() {
            applyBeatPulse(binding.smokeView.bassEnergy())
            handler.postDelayed(this, 80)
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            highlightLyricLine()
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
            updateAbStatus()
            updatePlayOnceButton()
            loadLyricsForCurrent()
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
        // Required glue for the MaterialContainerTransform shared-element
        // transition (v1.0.6 fix): without this callback the library never
        // tags the snapshot views, and the container transform crashes the
        // app when opening the full player.
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
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
        attachQueueDragAndSwipe()

        lyricsAdapter = LyricsAdapter(emptyList()) { line ->
            PlayerConnection.seekTo(line.timeMs)
        }
        lyricsAdapter.karaokeWordMode = LuxuryPrefs.karaokeMode(this) == 1
        binding.lyricsRecycler.layoutManager = LinearLayoutManager(this)
        binding.lyricsRecycler.adapter = lyricsAdapter

        binding.backButton.setOnClickListener { finish() }

        binding.playPauseButton.setOnClickListener { PlayerConnection.togglePlayPause() }
        binding.nextButton.setOnClickListener { PlayerConnection.next() }
        binding.previousButton.setOnClickListener { PlayerConnection.previous() }
        binding.minus10Button.setOnClickListener {
            val pos = PlayerConnection.currentPositionMs()
            PlayerConnection.seekTo((pos - 10_000).coerceAtLeast(0))
            Toast.makeText(this, R.string.minus_10s_toast, Toast.LENGTH_SHORT).show()
        }
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
        binding.eqButton.setOnClickListener {
            Haptics.tap(it, this)
            TransitionHelper.startActivity(this, EqActivity.intent(this))
        }
        binding.ringtoneButton.setOnClickListener {
            val song = PlayerConnection.currentSong() ?: return@setOnClickListener
            Haptics.tap(it, this)
            TransitionHelper.startActivity(this, RingtoneActivity.intent(this, song.id))
        }
        binding.tagsButton.setOnClickListener {
            val song = PlayerConnection.currentSong() ?: return@setOnClickListener
            Haptics.tap(it, this)
            TransitionHelper.startActivity(this, TagEditActivity.intent(this, listOf(song.id)))
        }
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.sleepButton.setOnClickListener { showSleepDialog() }
        binding.abAButton.setOnClickListener {
            PlayerConnection.setA(PlayerConnection.currentPositionMs())
            updateAbStatus()
        }
        binding.abBButton.setOnClickListener {
            PlayerConnection.setB(PlayerConnection.currentPositionMs())
            updateAbStatus()
        }
        binding.playOnceButton.setOnClickListener {
            PlayerConnection.playOnceThenNext = !PlayerConnection.playOnceThenNext
            updatePlayOnceButton()
            Toast.makeText(
                this,
                if (PlayerConnection.playOnceThenNext) R.string.play_once_on else R.string.play_once_off,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.saveQueueButton.setOnClickListener { showSaveQueueDialog() }
        binding.queuesButton.setOnClickListener { showQueuesDialog() }
        binding.lyricsSearchOnline.setOnClickListener { loadLyricsForCurrent(forceOnline = true) }

        // Shared-element entry: the cover flew in from the mini player.
        if (intent.getBooleanExtra(TransitionHelper.EXTRA_SHARED_USED, false)) {
            window.sharedElementEnterTransition = TransitionHelper.sharedTransition()
            window.sharedElementReturnTransition = TransitionHelper.sharedTransition()
        }

        // Swipe left/right on the disc to change the song + double-tap to like.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (kotlin.math.abs(dx) > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (dx < 0) PlayerConnection.next() else PlayerConnection.previous()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!LuxuryPrefs.doubleTapLikeEnabled(this@PlayerActivity)) return false
                val song = PlayerConnection.currentSong() ?: return false
                if (FavoritesStore.isFavorite(this@PlayerActivity, song.id)) {
                    FavoritesStore.removeFavorite(this@PlayerActivity, song.id)
                } else {
                    FavoritesStore.addFavorite(this@PlayerActivity, song.id)
                    showFlyingHeart()
                }
                updateFavButton()
                return true
            }
        })
        binding.discContainer.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            true
        }

        // Lyrics search: filter lines, jump to the next match.
        binding.lyricsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                lyricsAdapter.setQuery(q)
                val match = lyricsAdapter.firstMatch()
                if (match >= 0) {
                    binding.lyricsRecycler.scrollToPosition(match)
                }
            }
        })
        binding.lyricsNextMatch.setOnClickListener {
            val next = lyricsAdapter.nextMatch(lyricsAdapter.activeIndex)
            if (next >= 0) {
                binding.lyricsRecycler.scrollToPosition(next)
            }
        }

        // Luxury: golden particles around the disc.
        val motionStyle = MotionStyle.effective(this)
        if (LuxuryPrefs.particlesEnabled(this) || motionStyle == MotionStyle.PARTICLES) {
            binding.particlesView.visibility = View.VISIBLE
            binding.particlesView.setDensity(LuxuryPrefs.particlesDensity(this))
        }
        // Motion: slow Ken Burns zoom on the disc.
        if (motionStyle == MotionStyle.KEN_BURNS) {
            TransitionHelper.applyKenBurns(binding.discCard)
        }
        // Theme colours: smoke ring + disc stroke follow the theme.
        val (accent, secondary) = ThemeColors.current(this)
        binding.smokeView.setColors(accent, secondary)
        binding.smokeView.setMode(WaveformStyle.current(this))
        binding.discCard.strokeColor = accent

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerConnection.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    PlayerConnection.setVolumePercent(progress, this@PlayerActivity)
                    updateVolumeUi(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        connectController()
        VisualizerHub.listener = { magnitudes -> binding.smokeView.updateMagnitudes(magnitudes) }
        // Waveform shape (Settings): applies immediately when the player opens.
        binding.smokeView.setMode(WaveformStyle.current(this))
        handler.post(updateRunnable)
        handler.post(pulseRunnable)
        updateSleepButton()
        updateAbStatus()
        updatePlayOnceButton()
        // Golden particles keep drifting while the player is open.
        if (binding.particlesView.visibility == View.VISIBLE) {
            binding.particlesView.setRunning(true)
        }
        // Gyroscope tilt (luxury option).
        startGyro()
    }

    override fun onStop() {
        super.onStop()
        VisualizerHub.listener = null
        PlayerConnection.controller?.removeListener(playerListener)
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(pulseRunnable)
        binding.particlesView.setRunning(false)
        stopGyro()
        // Back to the resting size when leaving the player.
        pulseLevel = 0f
        binding.discCard.scaleX = 1f
        binding.discCard.scaleY = 1f
        binding.discCard.rotationX = 0f
        binding.discCard.rotationY = 0f
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
        val volume = PlaybackStateStore.loadVolume(this)
        binding.volumeBar.progress = volume
        updateVolumeUi(volume)
        currentSpeed = PlayerConnection.controller?.playbackParameters?.speed ?: 1.0f
        binding.speedButton.text = formatSpeed(currentSpeed)
        loadLyricsForCurrent()
    }

    private fun updateSongInfo() {
        val song = PlayerConnection.currentSong()
        val c = PlayerConnection.controller
        binding.songTitle.text = song?.title ?: getString(R.string.no_song)
        binding.songTitle.isSelected = true
        binding.songArtist.text = song?.artist ?: getString(R.string.from_device)
        val duration = c?.duration?.takeIf { it > 0 } ?: song?.durationMs ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.totalTime.text = formatTime(duration)
        if (song != null) {
            // The disc shows the song's REAL cover (embedded picture).
            // Without a cover, the Zeno logo WITHOUT any square frame
            // (the transparent logo: Z + "Zeno Play") is the fallback.
            binding.discArt.tag = song.id
            Thread {
                try {
                    val bitmap = com.musicplayer.app.data.AlbumArtLoader.loadTrackCover(this, song)
                    runOnUiThread {
                        if (binding.discArt.tag != song.id) return@runOnUiThread
                        if (bitmap != null) {
                            hasRealCover = true
                            binding.discArt.setPadding(0, 0, 0, 0)
                            binding.discArt.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            binding.discArt.setImageBitmap(bitmap)
                            // Cover colours: smoke ring, disc stroke, theme.
                            val palette = CoverColors.extract(bitmap)
                            ThemeColors.setCoverPalette(palette)
                            val (accent, secondary) = ThemeColors.current(this@PlayerActivity)
                            binding.smokeView.setColors(accent, secondary)
                            binding.discCard.strokeColor = accent
                            // Blurred cover background (luxury option).
                            applyBlurBackground(bitmap)
                        } else {
                            showLogoFallback()
                        }
                        updateDiscRotation()
                    }
                } catch (e: Exception) {
                    runOnUiThread { showLogoFallback() }
                }
            }.start()
        } else {
            showLogoFallback()
        }
    }

    private fun showLogoFallback() {
        hasRealCover = false
        val pad = (28 * resources.displayMetrics.density).toInt()
        binding.discArt.setPadding(pad, pad, pad, pad)
        binding.discArt.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        binding.discArt.setImageResource(R.drawable.zeno_logo_transparent)
        // No cover: back to the default palette, no blurred background.
        ThemeColors.setCoverPalette(CoverColors.DEFAULT)
        val (accent, secondary) = ThemeColors.current(this)
        binding.smokeView.setColors(accent, secondary)
        binding.discCard.strokeColor = accent
        binding.playerBg.visibility = View.GONE
        binding.playerBgDim.visibility = View.GONE
        updateDiscRotation()
    }

    /** Blurred cover background behind the player (luxury option). */
    private fun applyBlurBackground(bitmap: android.graphics.Bitmap) {
        if (!LuxuryPrefs.blurEnabled(this)) {
            binding.playerBg.visibility = View.GONE
            binding.playerBgDim.visibility = View.GONE
            return
        }
        binding.playerBg.setImageBitmap(bitmap)
        val radius = when (LuxuryPrefs.blurStrength(this)) {
            0 -> 10f
            2 -> 28f
            else -> 18f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                binding.playerBg.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            } catch (e: Exception) {
            }
        }
        binding.playerBg.visibility = View.VISIBLE
        binding.playerBgDim.visibility = View.VISIBLE
    }

    /** Gyroscope: the disc leans with the phone (luxury option). */
    private fun startGyro() {
        stopGyro()
        if (!LuxuryPrefs.gyroEnabled(this)) return
        val manager = getSystemService(android.content.Context.SENSOR_SERVICE) as? SensorManager
            ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = manager
        val factor = when (LuxuryPrefs.gyroSensitivity(this)) {
            0 -> 4f
            2 -> 12f
            else -> 8f
        }
        gyroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Tilt from gravity: x = roll, y = pitch.
                val tx = (-event.values[0] / 9.81f * factor).coerceIn(-factor, factor)
                val ty = (event.values[1] / 9.81f * factor).coerceIn(-factor, factor)
                tiltX += (tx - tiltX) * 0.15f
                tiltY += (ty - tiltY) * 0.15f
                binding.discCard.rotationY = tiltX
                binding.discCard.rotationX = -tiltY
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyro() {
        try {
            gyroListener?.let { sensorManager?.unregisterListener(it) }
        } catch (e: Exception) {
        }
        gyroListener = null
        sensorManager = null
        tiltX = 0f
        tiltY = 0f
    }

    /** Flying heart for double-tap to like (luxury option). */
    private fun showFlyingHeart() {
        val heart = binding.flyingHeart
        heart.visibility = View.VISIBLE
        heart.alpha = 1f
        heart.scaleX = 0.5f
        heart.scaleY = 0.5f
        heart.translationY = 0f
        heart.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .translationY(-(90 * resources.displayMetrics.density))
            .alpha(0f)
            .setDuration(800)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    heart.visibility = View.GONE
                }
            })
            .start()
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
        binding.smokeView.setPlaying(playing)
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
        upcomingSongs = if (current in songs.indices) {
            songs.subList(current + 1, songs.size).toMutableList()
        } else {
            mutableListOf()
        }
        queueAdapter.submitList(upcomingSongs.toList())
    }

    private fun attachQueueDragAndSwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val current = PlayerConnection.currentIndex()
                PlayerConnection.controller?.moveMediaItem(current + 1 + from, current + 1 + to)
                Collections.swap(upcomingSongs, from, to)
                queueAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val current = PlayerConnection.currentIndex()
                PlayerConnection.controller?.removeMediaItem(current + 1 + pos)
                upcomingSongs.removeAt(pos)
                queueAdapter.submitList(upcomingSongs.toList())
                Toast.makeText(this@PlayerActivity, R.string.removed_from_queue, Toast.LENGTH_SHORT).show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.queueRecycler)
    }

    private fun updateDiscRotation() {
        // Only the real cover rotates. The logo fallback stays still so
        // the Z and "Zeno Play" stay upright and readable.
        val playing = PlayerConnection.controller?.isPlaying == true && hasRealCover
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
        } else {
            if (anim.isStarted) anim.pause()
            if (!hasRealCover) {
                anim.cancel()
                binding.discCard.rotation = 0f
            }
        }
    }

    // ---- Beat pulse: the cover disc breathes with the bass ----
    // Light and elegant: at most +3.5% in size, fast attack on the beat
    // and a slow release, so the cover never shakes violently.
    private fun applyBeatPulse(bass: Float) {
        pulseLevel = if (bass > pulseLevel) {
            bass.coerceIn(0f, 1f)
        } else {
            pulseLevel * 0.75f + bass * 0.25f
        }
        if (pulseLevel < 0.005f) pulseLevel = 0f
        val scale = 1f + pulseLevel * 0.035f
        binding.discCard.scaleX = scale
        binding.discCard.scaleY = scale
        // Haptic beat (luxury option): a light vibration on strong bass hits.
        if (bass > 0.55f) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastBeatHapticMs > 300) {
                lastBeatHapticMs = now
                Haptics.beat(this)
            }
        }
    }

    // ---- Volume 0-200% ----
    private fun updateVolumeUi(volumePercent: Int) {
        binding.volumeValue.text = "$volumePercent%"
        if (volumePercent > 100) {
            binding.boostBadge.visibility = View.VISIBLE
            binding.boostBadge.text = getString(R.string.boost_value, volumePercent - 100)
        } else {
            binding.boostBadge.visibility = View.GONE
        }
    }

    // ---- Speed ----
    private fun cycleSpeed() {
        currentSpeed = when (currentSpeed) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            2.0f -> 0.5f
            else -> 1.0f
        }
        PlayerConnection.setSpeed(currentSpeed, this)
        binding.speedButton.text = formatSpeed(currentSpeed)
        Toast.makeText(this, getString(R.string.speed_toast, formatSpeed(currentSpeed)), Toast.LENGTH_SHORT).show()
    }

    private fun formatSpeed(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0" else "$speed"
        return "${text}×"
    }

    // ---- Sleep timer ----
    private fun showSleepDialog() {
        val items = arrayOf(
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_45),
            getString(R.string.sleep_60),
            getString(R.string.sleep_after_1_song),
            getString(R.string.sleep_after_2_songs),
            getString(R.string.sleep_after_3_songs),
            getString(R.string.sleep_off)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> PlayerConnection.setSleepMinutes(15)
                    1 -> PlayerConnection.setSleepMinutes(30)
                    2 -> PlayerConnection.setSleepMinutes(45)
                    3 -> PlayerConnection.setSleepMinutes(60)
                    4 -> PlayerConnection.setSleepAfterSongs(1)
                    5 -> PlayerConnection.setSleepAfterSongs(2)
                    6 -> PlayerConnection.setSleepAfterSongs(3)
                    7 -> PlayerConnection.cancelSleep()
                }
                updateSleepButton()
            }
            .show()
    }

    private fun updateSleepButton() {
        binding.sleepButton.text = when (PlayerConnection.sleepMode) {
            PlayerConnection.SLEEP_MINUTES -> {
                val remaining = (PlayerConnection.sleepRemainingMs() / 60_000).coerceAtLeast(1)
                getString(R.string.sleep_minutes_left, remaining.toInt())
            }
            PlayerConnection.SLEEP_SONGS -> {
                getString(R.string.sleep_songs_left, PlayerConnection.sleepSongsRemaining)
            }
            else -> getString(R.string.sleep_timer)
        }
    }

    // ---- A-B repeat ----
    private fun updateAbStatus() {
        if (PlayerConnection.abEnabled) {
            binding.abStatus.visibility = View.VISIBLE
            binding.abStatus.text = getString(
                R.string.ab_active,
                formatTime(PlayerConnection.abStartMs),
                formatTime(PlayerConnection.abEndMs)
            )
        } else {
            binding.abStatus.visibility = View.GONE
        }
    }

    private fun updatePlayOnceButton() {
        binding.playOnceButton.alpha = if (PlayerConnection.playOnceThenNext) 1.0f else 0.45f
        binding.playOnceButton.setTextColor(
            getColor(if (PlayerConnection.playOnceThenNext) R.color.zeno_cyan else R.color.zeno_ink)
        )
    }

    // ---- Lyrics ----
    private fun loadLyricsForCurrent(forceOnline: Boolean = false) {
        val song = PlayerConnection.currentSong() ?: return
        if (song.id == currentLyricsSongId && !forceOnline) return
        currentLyricsSongId = song.id
        binding.lyricsSearch.setText("")
        lyricsAdapter.setQuery("")
        binding.lyricsSearchRow.visibility = View.GONE
        lifecycleScope.launch {
            binding.lyricsStatus.text = getString(R.string.lyrics_loading)
            binding.lyricsRecycler.visibility = View.GONE
            binding.lyricsSearchOnline.visibility = View.GONE
            val result = withContext(Dispatchers.IO) {
                LyricsManager.loadLyrics(
                    this@PlayerActivity,
                    song,
                    forceOnline || LyricsManager.isAutoFetchEnabled(this@PlayerActivity)
                )
            }
            if (result == null) {
                currentLyrics = emptyList()
                lyricsAdapter.submitList(emptyList())
                binding.lyricsStatus.visibility = View.VISIBLE
                binding.lyricsStatus.text = getString(R.string.lyrics_none)
                binding.lyricsRecycler.visibility = View.GONE
                binding.lyricsSearchOnline.visibility = View.VISIBLE
                return@launch
            }
            currentLyrics = result.lines
            if (result.instrumental) {
                binding.lyricsStatus.visibility = View.VISIBLE
                binding.lyricsStatus.text = getString(R.string.lyrics_instrumental)
                lyricsAdapter.submitList(emptyList())
                binding.lyricsRecycler.visibility = View.GONE
                binding.lyricsSearchOnline.visibility = View.GONE
            } else if (result.lines.isNotEmpty()) {
                binding.lyricsStatus.visibility = View.GONE
                binding.lyricsRecycler.visibility = View.VISIBLE
                lyricsAdapter.submitList(result.lines)
                binding.lyricsSearchRow.visibility = View.VISIBLE
                binding.lyricsSearchOnline.visibility = View.GONE
            } else if (!result.plainText.isNullOrBlank()) {
                // Embedded plain lyrics (no timestamps): show as text.
                binding.lyricsStatus.visibility = View.VISIBLE
                binding.lyricsStatus.text = result.plainText
                lyricsAdapter.submitList(emptyList())
                binding.lyricsRecycler.visibility = View.GONE
                binding.lyricsSearchOnline.visibility = View.GONE
            } else {
                binding.lyricsStatus.visibility = View.VISIBLE
                binding.lyricsStatus.text = getString(R.string.lyrics_none)
                lyricsAdapter.submitList(emptyList())
                binding.lyricsRecycler.visibility = View.GONE
                binding.lyricsSearchOnline.visibility = View.VISIBLE
            }
        }
    }

    private fun highlightLyricLine() {
        if (currentLyrics.isEmpty()) return
        val pos = PlayerConnection.currentPositionMs()
        var index = -1
        for (i in currentLyrics.indices) {
            if (currentLyrics[i].timeMs <= pos) index = i else break
        }
        if (index >= 0) {
            // Karaoke word-by-word: how far through the current line we are.
            var wordProgress = 0f
            if (LuxuryPrefs.karaokeMode(this) == 1) {
                val start = currentLyrics[index].timeMs
                val end = if (index + 1 < currentLyrics.size) {
                    currentLyrics[index + 1].timeMs
                } else {
                    start + 4000
                }
                if (end > start) {
                    wordProgress = ((pos - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                }
            }
            lyricsAdapter.setActive(index, wordProgress)
        }
    }

    // ---- Queues ----
    private fun showSaveQueueDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.queue_name_hint)
            setTextColor(getColor(R.color.zeno_ink))
            setHintTextColor(getColor(R.color.zeno_ink3))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_queue)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val songs = PlayerConnection.songs
                QueueStore.create(
                    this, name, songs.map { it.id },
                    PlayerConnection.currentIndex(),
                    PlayerConnection.currentPositionMs()
                )
                Toast.makeText(
                    this,
                    R.string.queue_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQueuesDialog() {
        val queues = QueueStore.load(this)
        if (queues.isEmpty()) {
            Toast.makeText(this, R.string.no_saved_queues, Toast.LENGTH_SHORT).show()
            return
        }
        val names = queues.map { "${it.name} (${it.songIds.size})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.my_queues)
            .setItems(names) { _, which ->
                val queue = queues[which]
                val library = MusicRepositoryHolder.library
                val songs = queue.songIds.mapNotNull { id -> library.find { it.id == id } }
                if (songs.isEmpty()) {
                    Toast.makeText(this, R.string.queue_empty, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                PlayerConnection.playSongs(songs, queue.index)
                if (queue.positionMs > 0) PlayerConnection.seekTo(queue.positionMs)
            }
            .setNeutralButton(R.string.delete_queue) { _, _ -> showDeleteQueueDialog(queues) }
            .show()
    }

    private fun showDeleteQueueDialog(queues: List<QueueStore.SavedQueue>) {
        val names = queues.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_queue)
            .setItems(names) { _, which ->
                QueueStore.delete(this, queues[which].id)
                Toast.makeText(this, R.string.queue_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** Holds the last library snapshot so the queue loader can resolve ids. */
    object MusicRepositoryHolder {
        var library: List<Song> = emptyList()
    }
}
