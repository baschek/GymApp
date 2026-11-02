package de.baschek.gymapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Accent colour choices (Settings > Akzentfarbe). The colours live in `ui/theme`. */
enum class Accent(val key: String, val label: String) {
    PAPIER("papier", "Papier"),
    SALBEI("salbei", "Papier + Salbei"),
    DUNST("dunst", "Papier + Dunstblau"),
    SAND("sand", "Papier + Sand");

    companion object {
        fun fromKey(key: String?): Accent = entries.firstOrNull { it.key == key } ?: PAPIER
    }
}

private val Context.appPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/** Per-device display preferences. They are never synchronized. */
class AppPrefs(context: Context) {
    private val store = context.applicationContext.appPrefsStore

    val accent: Flow<Accent> = store.data.map { Accent.fromKey(it[ACCENT]) }

    suspend fun setAccent(accent: Accent) {
        store.edit { it[ACCENT] = accent.key }
    }

    /** Vibrate once when the rest countdown reaches zero. */
    val restVibration: Flow<Boolean> = store.data.map { it[REST_VIBRATION] ?: true }

    suspend fun setRestVibration(on: Boolean) {
        store.edit { it[REST_VIBRATION] = on }
    }

    private companion object {
        val ACCENT = stringPreferencesKey("accent")
        val REST_VIBRATION = booleanPreferencesKey("rest_vibration")
    }
}
