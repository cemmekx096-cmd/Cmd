package eu.kanade.tachiyomi.animeextension.id.lk21series

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen

object LK21SeriesPreferences {
    // ═══════════════════════════════════════════════════════════════════════
    // Keys & Defaults
    // ═══════════════════════════════════════════════════════════════════════
    private const val PREF_USER_AGENT_KEY = "user_agent"
    private const val PREF_USER_AGENT_DEFAULT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val PREF_TIMEOUT_KEY = "network_timeout"
    private const val PREF_TIMEOUT_DEFAULT = "90"

    private val TIMEOUT_ENTRIES = arrayOf("15 detik", "30 detik", "60 detik", "90 detik", "120 detik")
    private val TIMEOUT_VALUES = arrayOf("15", "30", "60", "90", "120")

    // ═══════════════════════════════════════════════════════════════════════
    // Setup Preferences
    // ═══════════════════════════════════════════════════════════════════════
    fun setupPreferences(screen: PreferenceScreen, preferences: SharedPreferences) {
        // ── User Agent ────────────────────────────────────────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_USER_AGENT_KEY
            title = "Custom User Agent"
            summary = "Kosongkan untuk menggunakan default"
            setDefaultValue(PREF_USER_AGENT_DEFAULT)
            dialogTitle = "Masukkan User Agent"
            dialogMessage = "Kosongkan untuk kembali ke default."

            setOnPreferenceChangeListener { _, newValue ->
                val ua = (newValue as String).ifBlank { PREF_USER_AGENT_DEFAULT }
                preferences.edit().putString(PREF_USER_AGENT_KEY, ua).apply()
                true
            }
        }.also(screen::addPreference)

        // ── Timeout ───────────────────────────────────────────────────────
        ListPreference(screen.context).apply {
            key = PREF_TIMEOUT_KEY
            title = "Timeout Koneksi"
            summary = "Waktu tunggu sebelum request gagal: %s"
            entries = TIMEOUT_ENTRIES
            entryValues = TIMEOUT_VALUES
            setDefaultValue(PREF_TIMEOUT_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_TIMEOUT_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }
}
