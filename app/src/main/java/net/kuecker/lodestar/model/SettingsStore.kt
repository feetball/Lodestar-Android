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

    /**
     * Replaces the settings with `transform(settings)` (sanitised) and schedules a save. A change that only touches the
     * single camera keys goes to the active camera profile, and the keys follow the profile otherwise.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings).reconcilingCamera(settings).sanitized()
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

    /** Adds [profile] and makes it the active camera; `false` when the maximum number of profiles is reached. */
    fun addCameraProfile(profile: CameraProfile): Boolean {
        if (settings.cameraProfiles.size >= AppSettings.MAXIMUM_CAMERA_PROFILES) return false
        update { it.copy(cameraProfiles = it.cameraProfiles + profile, activeCameraProfileID = profile.id) }
        return true
    }

    /** Replaces the profile that has the identifier of [profile]. */
    fun updateCameraProfile(profile: CameraProfile) = update { s ->
        s.copy(cameraProfiles = s.cameraProfiles.map { if (it.id == profile.id) profile else it })
    }

    /** Makes the profile with [id] the active camera. */
    fun activateCameraProfile(id: String) = update { s ->
        if (s.cameraProfiles.any { it.id == id }) s.copy(activeCameraProfileID = id) else s
    }

    /** Deletes the profile with [id]; `false` (and nothing deleted) when it is the only one. */
    fun removeCameraProfile(id: String): Boolean {
        if (settings.cameraProfiles.size <= 1) return false
        update { s -> s.copy(cameraProfiles = s.cameraProfiles.filter { it.id != id }) }
        return true
    }

    private fun load(): AppSettings =
        preferences.getString(STORAGE_KEY, null)?.let(AppSettings::decode) ?: AppSettings().sanitized()

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }
}
