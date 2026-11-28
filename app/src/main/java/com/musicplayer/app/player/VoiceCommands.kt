package com.musicplayer.app.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AlertDialog
import com.musicplayer.app.R

/**
 * v1.0.16 — voice commands (feature 8), in Tunisian Darija AND
 * Modern Standard Arabic (a few French commands work too, since the
 * app's UI is French).
 *
 * How it works: the phone's built-in speech recognizer listens once
 * (locale ar-TN first, then plain ar as a fallback — Darija
 * recognition depends on the device's speech service and is weaker
 * than MSA, so the matcher is deliberately loose), and the heard
 * text is matched against a fixed command set on the device.
 *
 * Privacy: Zeno Play records nothing and keeps nothing — only the
 * spoken command goes to the phone's own speech-recognition service,
 * exactly what the recognizer needs to work. The listening dialog
 * says so.
 */
object VoiceCommands {

    sealed class Command {
        object Play : Command()
        object Pause : Command()
        object Next : Command()
        object Previous : Command()
        object VolumeUp : Command()
        object VolumeDown : Command()
        object Stop : Command()
        data class PlaySong(val query: String) : Command()
    }

    /** Strips Arabic diacritics + tatweel and lower-cases, for matching. */
    fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه')
            .trim()
    }

    /**
     * Matches recognized text against the command set. "Play a song"
     * is detected when the text starts with a play verb and the rest
     * names a song; the caller resolves [Command.PlaySong.query]
     * against the library.
     */
    fun matchCommand(text: String): Command? {
        val n = normalize(text)
        if (n.isBlank()) return null
        // Play a specific song: "شغل <song>" / "شغل أغنية <song>".
        for (prefix in PLAY_SONG_PREFIXES) {
            if (n.startsWith(prefix)) {
                val query = n.removePrefix(prefix).trim()
                    .removePrefix("اغنيه").removePrefix("اغنية").trim()
                if (query.isNotBlank()) return Command.PlaySong(query)
            }
        }
        return when {
            containsAny(n, NEXT_WORDS) -> Command.Next
            containsAny(n, PREVIOUS_WORDS) -> Command.Previous
            containsAny(n, VOLUME_UP_WORDS) -> Command.VolumeUp
            containsAny(n, VOLUME_DOWN_WORDS) -> Command.VolumeDown
            containsAny(n, STOP_WORDS) -> Command.Stop
            containsAny(n, PAUSE_WORDS) -> Command.Pause
            containsAny(n, PLAY_WORDS) -> Command.Play
            else -> null
        }
    }

    private fun containsAny(text: String, words: List<String>): Boolean {
        return words.any { text.contains(it) }
    }

    // Longest first: "شغل لي X" must not match the bare "شغل" prefix.
    private val PLAY_SONG_PREFIXES = listOf("شغل اغنيه", "شغل اغنية", "شغل لي", "شغل", "jouer", "play")

    private val PLAY_WORDS = listOf(
        "شغل", "تشغيل", "العب", "العبها", "اعزف", "joue", "jouer", "play", "reprendre", "resume"
    )
    private val PAUSE_WORDS = listOf(
        "وقف", "اوقف", "ايقاف", "واقف", "pause", "suspendre", "متوقف"
    )
    private val NEXT_WORDS = listOf(
        "التالي", "التالية", "اللي بعد", "اللي بعدها", "بعدها", "suivant", "suivante", "next"
    )
    private val PREVIOUS_WORDS = listOf(
        "السابق", "السابقه", "السابقة", "اللي قبل", "اللي قبلها", "precedent", "précédent", "previous"
    )
    private val VOLUME_UP_WORDS = listOf(
        "علي الصوت", "زيد الصوت", "ارفع الصوت", "صوت اعلى", "صوت أعلى",
        "volume plus", "monter le volume", "volume up", "plus fort"
    )
    private val VOLUME_DOWN_WORDS = listOf(
        "نقص الصوت", "وطي الصوت", "خفض الصوت", "صوت اقل", "صوت أقل",
        "volume moins", "baisser le volume", "volume down", "moins fort"
    )
    private val STOP_WORDS = listOf(
        "اوقف التشغيل", "قف", "stop", "arrêter", "arreter", "سكتها"
    )

    fun isAvailable(context: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * One listening session: shows the dialog, listens once (ar-TN,
     * then ar on failure), matches the heard text and calls
     * [onCommand]. [onUnrecognized] runs when the text matched no
     * command; [onUnavailable] when recognition cannot start at all.
     * Call [stop] to cancel (e.g. from onDestroy).
     */
    class Session(
        private val activity: Activity,
        private val onCommand: (Command) -> Unit,
        private val onUnrecognized: (String) -> Unit,
        private val onUnavailable: () -> Unit
    ) {
        private var recognizer: SpeechRecognizer? = null
        private var dialog: AlertDialog? = null
        private var triedFallback = false
        private var finished = false

        fun start() {
            if (!isAvailable(activity)) {
                onUnavailable()
                return
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (finished) return
                    // Darija first, MSA as a fallback.
                    if (!triedFallback && error != SpeechRecognizer.ERROR_CLIENT) {
                        triedFallback = true
                        startListening("ar")
                        return
                    }
                    finish()
                    onUnavailable()
                }

                override fun onResults(results: Bundle?) {
                    if (finished) return
                    finished = true
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    finish()
                    val command = matchCommand(heard)
                    if (command != null) onCommand(command) else onUnrecognized(heard)
                }
            })
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.voice_listening_title)
                .setMessage(R.string.voice_listening_message)
                .setNegativeButton(R.string.cancel) { _, _ -> stop() }
                .setOnDismissListener { stop() }
                .show()
            startListening("ar-TN")
        }

        private fun startListening(localeTag: String) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                finish()
                onUnavailable()
            }
        }

        fun stop() {
            finish()
        }

        private fun finish() {
            finished = true
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
            }
            recognizer = null
            try {
                dialog?.dismiss()
            } catch (e: Exception) {
            }
            dialog = null
        }
    }
}
