package com.musicplayer.app.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.AudioManager
import android.media.AudioDeviceInfo
import com.musicplayer.app.report.ErrorReporter

/**
 * Real equalizer (Android audio effects) attached to the ExoPlayer audio
 * session, with presets, bass boost and per-output settings.
 *
 * The EQ is applied per audio output: speaker, wired headphones and
 * Bluetooth each keep their own preset/levels/bass (like Musicolet), and
 * the settings are re-applied automatically when the output changes.
 *
 * When the device has no EQ (0 bands) the manager reports unavailable and
 * the EQ screen shows a message instead of crashing.
 *
 * VLC tracks use [VlcEngine.applyEqualizer] with the same band levels.
 */
object EqManager {

    private const val PREFS = "zeno_eq"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset_"
    private const val KEY_LEVELS = "levels_"
    private const val KEY_BASS = "bass_"
    private const val KEY_LAST_OUTPUT = "last_output"

    /** 5-band preset levels in dB (60 / 230 / 910 / 3600 / 14000 Hz). */
    val PRESETS: Map<String, FloatArray> = mapOf(
        "Normal" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
        "Pop" to floatArrayOf(-1f, 2f, 4f, 3f, -1f),
        "Rock" to floatArrayOf(4f, 2f, -1f, 3f, 4f),
        "Jazz" to floatArrayOf(3f, 2f, 0f, 2f, 3f),
        "Classique" to floatArrayOf(3f, 2f, 0f, 2f, 3f),
        "Basses" to floatArrayOf(6f, 4f, 1f, -1f, -2f),
        "Voix" to floatArrayOf(-2f, 1f, 4f, 4f, 1f),
        "Aigus" to floatArrayOf(-2f, -1f, 1f, 4f, 6f)
    )

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var attachedSessionId: Int = 0

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var bandCount: Int = 0
        private set

    /** Current output key: "speaker", "headphones", "bluetooth", "other". */
    @Volatile
    var currentOutput: String = "speaker"
        private set

    fun currentBandsDb(context: Context): FloatArray {
        val levels = loadLevels(context, currentOutput)
        return levels ?: PRESETS["Normal"]!!.copyOf()
    }

    /** Attaches the EQ to a player audio session (called on session change). */
    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == attachedSessionId) return
        release()
        attachedSessionId = audioSessionId
        try {
            val eq = Equalizer(0, audioSessionId)
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) {
                available = false
                bandCount = 0
                eq.release()
                return
            }
            equalizer = eq
            bandCount = bands
            available = true
            try {
                bassBoost = BassBoost(0, audioSessionId)
            } catch (e: Exception) {
                bassBoost = null
            }
            applyCurrent(context)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "EQ attach failed: ${e.message}",
                e
            )
            available = false
            bandCount = 0
            release()
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
        }
        try {
            bassBoost?.release()
        } catch (e: Exception) {
        }
        equalizer = null
        bassBoost = null
        attachedSessionId = 0
        available = false
        bandCount = 0
    }

    /** Applies the stored settings for the current output. */
    fun applyCurrent(context: Context) {
        val eq = equalizer ?: return
        try {
            val enabled = isEnabled(context)
            eq.enabled = enabled
            bassBoost?.enabled = enabled
            if (!enabled) return
            val levels = loadLevels(context, currentOutput)
                ?: PRESETS["Normal"]!!
            applyLevels(eq, levels)
            bassBoost?.setStrength((loadBass(context, currentOutput) * 10).toShort())
            // Mirror the same bands on the VLC engine (best effort).
            VlcEngine.applyEqualizer(levels)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "EQ apply failed: ${e.message}",
                e
            )
        }
    }

    private fun applyLevels(eq: Equalizer, levelsDb: FloatArray) {
        val range = eq.bandLevelRange // [min, max] in millibels
        val min = range[0].toInt()
        val max = range[1].toInt()
        val bands = eq.numberOfBands.toInt()
        for (band in 0 until bands) {
            val db = levelsDb.getOrElse(band) { 0f }
            val mb = (db * 100).toInt().coerceIn(min, max).toShort()
            try {
                eq.setBandLevel(band.toShort(), mb)
            } catch (e: Exception) {
                // One band failing must not break the rest.
            }
        }
    }

    /** Applies given levels + bass to the live EQ without persisting them
     * (per-song settings: the song's own EQ, not the global output one). */
    fun applyLevelsNow(context: Context, levelsDb: FloatArray, bass: Int) {
        val eq = equalizer ?: return
        try {
            if (!isEnabled(context)) return
            applyLevels(eq, levelsDb)
            bassBoost?.setStrength((bass.coerceIn(0, 100) * 10).toShort())
            VlcEngine.applyEqualizer(levelsDb)
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "EQ per-song apply failed: ${e.message}",
                e
            )
        }
    }

    fun setLevels(context: Context, levelsDb: FloatArray) {
        saveLevels(context, currentOutput, levelsDb)
        savePreset(context, currentOutput, "Personnalisé")
        val eq = equalizer
        if (eq != null && isEnabled(context)) {
            applyLevels(eq, levelsDb)
            VlcEngine.applyEqualizer(levelsDb)
        }
    }

    fun setPreset(context: Context, presetName: String) {
        val levels = PRESETS[presetName]?.copyOf() ?: return
        savePreset(context, currentOutput, presetName)
        saveLevels(context, currentOutput, levels)
        val eq = equalizer
        if (eq != null && isEnabled(context)) {
            applyLevels(eq, levels)
            VlcEngine.applyEqualizer(levels)
        }
    }

    fun setBass(context: Context, strength0to100: Int) {
        saveBass(context, currentOutput, strength0to100.coerceIn(0, 100))
        try {
            bassBoost?.setStrength((strength0to100.coerceIn(0, 100) * 10).toShort())
        } catch (e: Exception) {
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
        }
        applyCurrent(context)
    }

    fun isEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            false
        }
    }

    fun currentPresetName(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRESET + currentOutput, "Normal") ?: "Normal"
        } catch (e: Exception) {
            "Normal"
        }
    }

    fun currentBass(context: Context): Int {
        return loadBass(context, currentOutput)
    }

    /** Call when the audio output changes (AudioDeviceCallback). */
    fun onOutputChanged(context: Context, output: String) {
        if (output == currentOutput) return
        currentOutput = output
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_OUTPUT, output).apply()
        } catch (e: Exception) {
        }
        applyCurrent(context)
    }

    /** Detects the current output from the AudioManager. */
    fun detectOutput(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var result = "speaker"
            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> return "bluetooth"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> result = "headphones"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> if (result != "headphones") result = "speaker"
                }
            }
            result
        } catch (e: Exception) {
            "speaker"
        }
    }

    private fun loadLevels(context: Context, output: String): FloatArray? {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LEVELS + output, null) ?: return null
            val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
            if (parts.size < 5) null else FloatArray(5) { parts[it] }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveLevels(context: Context, output: String, levels: FloatArray) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LEVELS + output, levels.joinToString(",")).apply()
        } catch (e: Exception) {
        }
    }

    private fun savePreset(context: Context, output: String, name: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PRESET + output, name).apply()
        } catch (e: Exception) {
        }
    }

    private fun loadBass(context: Context, output: String): Int {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BASS + output, 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun saveBass(context: Context, output: String, value: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BASS + output, value).apply()
        } catch (e: Exception) {
        }
    }
}
