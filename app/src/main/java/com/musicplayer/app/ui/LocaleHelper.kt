package com.musicplayer.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App language: French (default), Arabic or English — full UI translation
 * with full direction support (Arabic = RTL, French/English = LTR).
 *
 * The language is chosen in Settings and stored in SharedPreferences.
 * It is applied in [FullScreenActivity.attachBaseContext] (and in the
 * Application) by wrapping the base context with the saved locale, so
 * every screen — including dialogs — uses the chosen language and the
 * right layout direction from the first frame.
 *
 * Song / album / artist names are never translated: they are shown exactly
 * as stored in the audio files, and search finds them in any language.
 */
object LocaleHelper {

    const val LANG_FR = "fr"
    const val LANG_AR = "ar"
    const val LANG_EN = "en"

    private const val PREFS = "zeno_language"
    private const val KEY_LANGUAGE = "language"

    /** The saved language, or French (the default) when nothing is saved. */
    fun savedLanguage(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANG_FR) ?: LANG_FR
        } catch (e: Exception) {
            LANG_FR
        }
    }

    fun isRtl(context: Context): Boolean {
        return savedLanguage(context) == LANG_AR
    }

    /**
     * Changes the language: saves it, tells AppCompat (so it is applied
     * on every API level), and the caller recreates the activity.
     */
    fun setLanguage(context: Context, language: String) {
        val lang = when (language) {
            LANG_AR, LANG_EN -> language
            else -> LANG_FR
        }
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, lang).apply()
        } catch (e: Exception) {
        }
        try {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(lang)
            )
        } catch (e: Exception) {
        }
    }

    /** Wraps [base] with the saved locale (language + layout direction). */
    fun wrapContext(base: Context): Context {
        return wrapContext(base, savedLanguage(base))
    }

    fun wrapContext(base: Context, language: String): Context {
        return try {
            val locale = Locale.forLanguageTag(language)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            base.createConfigurationContext(config)
        } catch (e: Exception) {
            base
        }
    }
}
