package eu.kanade.tachiyomi.lib.lk21extractor

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen

object Lk21Preferences {
    const val PREF_BASE_URL_KEY = "base_url"
    const val PREF_QUALITY_KEY = "preferred_quality"
    const val DEFAULT_BASE_URL_MOVIES = "https://tv8.lk21official.cc"
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    fun setupPreferences(screen: PreferenceScreen, preferences: SharedPreferences, defaultUrl: String) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Manual Base URL"
            setDefaultValue(defaultUrl)
            summary = preferences.getString(PREF_BASE_URL_KEY, defaultUrl)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"
        }.also(screen::addPreference)
    }

    fun getBaseUrl(preferences: SharedPreferences, defaultUrl: String): String =
        preferences.getString(PREF_BASE_URL_KEY, defaultUrl) ?: defaultUrl

    fun getUserAgent(): String = DEFAULT_USER_AGENT
}
