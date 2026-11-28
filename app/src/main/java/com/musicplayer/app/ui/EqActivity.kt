package com.musicplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.musicplayer.app.R
import com.musicplayer.app.data.PerSongAudioStore
import com.musicplayer.app.data.PlaybackStateStore
import com.musicplayer.app.databinding.ActivityEqBinding
import com.musicplayer.app.player.EqManager
import com.musicplayer.app.player.PlayerConnection
import com.musicplayer.app.player.ReverbManager
import com.musicplayer.app.player.VolumeBoost

/**
 * Real equalizer — 5 bands + bass boost + presets, with a setting per
 * output (speaker / headphones / bluetooth / other). Bands are mirrored to
 * the VLC engine when a rare-format track plays through it.
 */
class EqActivity : FullScreenActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(intent(context))
        }

        fun intent(context: Context) = Intent(context, EqActivity::class.java)
    }

    private lateinit var binding: ActivityEqBinding
    private var bands: FloatArray = FloatArray(5)
    private var bass = 0
    private var enabled = false
    private var preset = "Normal"
    private val bandViews = mutableListOf<BandRow>()
    /** The song being edited: its EQ is remembered per song. */
    private var currentSongId: Long = -1L
    private var reverbPreset = ReverbManager.PRESET_NONE

    private data class BandRow(val value: TextView, val seek: SeekBar)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        // Sync the current output from the AudioManager, then read its settings.
        EqManager.onOutputChanged(this, EqManager.detectOutput(this))
        bands = EqManager.currentBandsDb(this)
        bass = EqManager.currentBass(this)
        enabled = EqManager.isEnabled(this)
        preset = EqManager.currentPresetName(this)

        // Per-song settings: if the current song remembers its own EQ,
        // show (and apply) that instead of the global settings.
        currentSongId = PlayerConnection.currentSong()?.id ?: -1L
        val savedPerSong = if (currentSongId >= 0) {
            PerSongAudioStore.get(this, currentSongId)
        } else {
            null
        }
        if (savedPerSong != null) {
            savedPerSong.bandsDb?.let {
                if (it.size == 5) {
                    bands = it.copyOf()
                    EqManager.setLevels(this, bands)
                }
            }
            savedPerSong.bass?.let {
                bass = it
                EqManager.setBass(this, it)
            }
            savedPerSong.preset?.let { preset = it }
        }

        // Stereo balance + Mono (live via the gain processor; Mono on VLC
        // applies from the next VLC track).
        VolumeBoost.balance = PlaybackStateStore.loadBalance(this)
        VolumeBoost.mono = PlaybackStateStore.loadMono(this)
        binding.balanceSeek.progress =
            ((VolumeBoost.balance + 1f) / 2f * 200f).toInt().coerceIn(0, 200)
        updateBalanceLabel(VolumeBoost.balance)
        binding.balanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val balance = progress / 100f - 1f
                VolumeBoost.balance = balance
                PlaybackStateStore.saveBalance(this@EqActivity, balance)
                updateBalanceLabel(balance)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.monoSwitch.isChecked = VolumeBoost.mono
        binding.monoSwitch.setOnCheckedChangeListener { _, checked ->
            VolumeBoost.mono = checked
            PlaybackStateStore.saveMono(this, checked)
        }

        // Reverb sound effects (Media3 only).
        reverbPreset = ReverbManager.loadPreset(this)
        val reverbs = listOf(
            ReverbManager.PRESET_NONE to R.string.reverb_none,
            ReverbManager.PRESET_SMALL_ROOM to R.string.reverb_small_room,
            ReverbManager.PRESET_STUDIO to R.string.reverb_studio,
            ReverbManager.PRESET_CONCERT_HALL to R.string.reverb_concert_hall
        )
        binding.reverbContainer.removeAllViews()
        for ((value, labelRes) in reverbs) {
            val chip = layoutInflater.inflate(
                R.layout.item_eq_preset, binding.reverbContainer, false
            ) as TextView
            chip.text = getString(labelRes)
            chip.tag = value
            chip.isSelected = value == reverbPreset
            chip.setOnClickListener {
                reverbPreset = value
                ReverbManager.setPreset(this, value)
                refreshReverbChips()
            }
            binding.reverbContainer.addView(chip)
        }

        binding.outputLabel.text = getString(R.string.eq_output_label, outputLabel(EqManager.currentOutput))
        binding.enableSwitch.isChecked = enabled
        binding.enableSwitch.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            EqManager.setEnabled(this, checked)
            binding.eqStatus.visibility = if (checked) View.GONE else View.VISIBLE
        }
        binding.eqStatus.visibility = if (enabled) View.GONE else View.VISIBLE

        val labels = resources.getStringArray(R.array.eq_band_labels)
        val container = binding.bandsContainer
        for (i in 0 until 5) {
            val row = layoutInflater.inflate(R.layout.item_eq_band, container, false)
            val label = row.findViewById<TextView>(R.id.bandLabel)
            val value = row.findViewById<TextView>(R.id.bandValue)
            val seek = row.findViewById<SeekBar>(R.id.bandSeek)
            label.text = labels.getOrNull(i) ?: "${i + 1}"
            seek.max = 24 // -12 .. +12 dB
            seek.progress = (bands[i] + 12).toInt().coerceIn(0, 24)
            value.text = formatDb(bands[i])
            val index = i
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    bands[index] = (progress - 12).toFloat()
                    value.text = formatDb(bands[index])
                    preset = getString(R.string.eq_preset_custom)
                    EqManager.setLevels(this@EqActivity, bands)
                    savePerSong()
                    refreshPresetChips()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            container.addView(row)
            bandViews.add(BandRow(value, seek))
        }

        binding.bassSeek.max = 100
        binding.bassSeek.progress = bass
        binding.bassValue.text = "$bass%"
        binding.bassSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                bass = progress
                binding.bassValue.text = "$progress%"
                EqManager.setBass(this@EqActivity, progress)
                savePerSong()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val presets = listOf(
            "Normal" to R.string.eq_preset_normal,
            "Pop" to R.string.eq_preset_pop,
            "Rock" to R.string.eq_preset_rock,
            "Jazz" to R.string.eq_preset_jazz,
            "Classique" to R.string.eq_preset_classical,
            "Basses" to R.string.eq_preset_bass,
            "Voix" to R.string.eq_preset_vocal,
            "Aigus" to R.string.eq_preset_treble
        )
        binding.presetsContainer.removeAllViews()
        for ((name, labelRes) in presets) {
            val chip = layoutInflater.inflate(R.layout.item_eq_preset, binding.presetsContainer, false) as TextView
            chip.text = getString(labelRes)
            chip.tag = name
            chip.isSelected = name == preset
            chip.setOnClickListener {
                preset = name
                EqManager.setPreset(this, name)
                bands = EqManager.currentBandsDb(this)
                savePerSong()
                refreshBandViews()
                refreshPresetChips()
            }
            binding.presetsContainer.addView(chip)
        }
        binding.resetButton.setOnClickListener {
            preset = "Normal"
            EqManager.setPreset(this, "Normal")
            EqManager.setBass(this, 0)
            EqManager.setEnabled(this, true)
            enabled = true
            binding.enableSwitch.isChecked = true
            binding.eqStatus.visibility = View.GONE
            bands = EqManager.currentBandsDb(this)
            bass = 0
            binding.bassSeek.progress = 0
            binding.bassValue.text = "0%"
            refreshBandViews()
            refreshPresetChips()
        }
        refreshPresetChips()
    }

    private fun outputLabel(output: String): String = when (output) {
        "headphones" -> getString(R.string.eq_output_headphones)
        "bluetooth" -> getString(R.string.eq_output_bluetooth)
        "other" -> getString(R.string.eq_output_other)
        else -> getString(R.string.eq_output_speaker)
    }

    private fun formatDb(db: Float): String {
        val v = db.toInt()
        return if (v > 0) "+${v} dB" else "$v dB"
    }

    private fun refreshBandViews() {
        for (i in bandViews.indices) {
            bandViews[i].seek.progress = (bands[i] + 12).toInt().coerceIn(0, 24)
            bandViews[i].value.text = formatDb(bands[i])
        }
    }

    /** Remembers the EQ for the current song (bands + bass + preset). */
    private fun savePerSong() {
        if (currentSongId < 0) return
        PerSongAudioStore.saveEq(this, currentSongId, bands.copyOf(), bass, preset)
    }

    private fun updateBalanceLabel(balance: Float) {
        binding.balanceValue.text = when {
            balance < -0.05f -> getString(R.string.balance_left)
            balance > 0.05f -> getString(R.string.balance_right)
            else -> getString(R.string.balance_center)
        }
    }

    private fun refreshReverbChips() {
        for (i in 0 until binding.reverbContainer.childCount) {
            val chip = binding.reverbContainer.getChildAt(i) as TextView
            chip.isSelected = (chip.tag as? Int) == reverbPreset
        }
    }

    private fun refreshPresetChips() {
        for (i in 0 until binding.presetsContainer.childCount) {
            val chip = binding.presetsContainer.getChildAt(i) as TextView
            chip.isSelected = chip.tag == preset
        }
    }
}
