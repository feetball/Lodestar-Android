package net.kuecker.lodestar.model

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persists [AppSettings] as one JSON blob in shared preferences (debounced writes). [settings] is observable
 * Compose state; change it with [update].
 */
class SettingsStore(private val preferences: SharedPreferences, private val scope: CoroutineScope) {
    private companion object {
        const val STORAGE_KEY = "lodestar.settings.v1"
        const val SAVE_DELAY_MS = 400L
    }

    var settings by mutableStateOf(load())
        private set

    private var saveJob: Job? = null

    /** Replaces the settings with `transform(settings)` (sanitised) and schedules a save. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings).sanitized()
        if (next == settings) return
        settings = next
        scheduleSave()
    }

    /** Writes immediately (call when the app moves to the background). */
    fun saveNow() {
        saveJob?.cancel()
        saveJob = null
        preferences.edit { putString(STORAGE_KEY, AppSettings.encode(settings)) }
    }

    // Convenience mutations

    fun toggleFavorite(objectID: String) = update { s ->
        if (objectID in s.favoriteObjectIDs) {
            s.copy(favoriteObjectIDs = s.favoriteObjectIDs - objectID)
        } else if (s.favoriteObjectIDs.size < AppSettings.MAXIMUM_FAVORITES) {
            s.copy(favoriteObjectIDs = s.favoriteObjectIDs + objectID)
        } else {
            s
        }
    }

    fun noteRecent(objectID: String) = update { s ->
        s.copy(recentObjectIDs = (listOf(objectID) + s.recentObjectIDs.filter { it != objectID }).take(AppSettings.MAXIMUM_RECENTS))
    }

    /** Adds [site]; `false` when the maximum number of sites is reached. */
    fun addSite(site: SavedSite): Boolean {
        if (settings.savedSites.size >= AppSettings.MAXIMUM_SAVED_SITES) return false
        update { it.copy(savedSites = it.savedSites + site) }
        return true
    }

    fun updateSite(site: SavedSite) = update { s ->
        s.copy(savedSites = s.savedSites.map { if (it.id == site.id) site else it })
    }

    fun removeSites(ids: Set<String>) = update { s -> s.copy(savedSites = s.savedSites.filter { it.id !in ids }) }

    private fun load(): AppSettings =
        preferences.getString(STORAGE_KEY, null)?.let(AppSettings::decode) ?: AppSettings()

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }
}
