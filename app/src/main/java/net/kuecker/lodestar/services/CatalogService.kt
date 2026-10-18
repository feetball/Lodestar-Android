package net.kuecker.lodestar.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.BundledAsteroids
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSearchResult
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.comets.SmallBody

/**
 * The bundled object catalog, decoded once off the main thread, with the bundled bright asteroids and any comets and
 * asteroids downloaded since (see [setDownloadedSmallBodies]).
 */
@Suppress("unused") // the scope is kept for parity with the other services' constructors
class CatalogService(private val scope: CoroutineScope) {
    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    var loadState by mutableStateOf<LoadState>(LoadState.Idle)
        private set

    /** The catalog in use; a new value whenever the downloaded small bodies change. */
    var catalog by mutableStateOf<Catalog?>(null)
        private set

    private var base: Catalog? = null
    private var bundledSmallBodies: List<SmallBody> = emptyList()
    private var downloadedSmallBodies: List<SmallBody> = emptyList()

    /** Decodes the bundled catalog on a background thread. Idempotent. */
    suspend fun load() {
        if (catalog != null || loadState == LoadState.Loading) return
        loadState = LoadState.Loading
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val loaded = Catalog.loadBundled()
                // The bundled asteroids are a convenience: the catalog works without them.
                val asteroids = runCatching { BundledAsteroids.load().bodies }.getOrDefault(emptyList())
                loaded to asteroids
            }
        }
        result.fold(
            onSuccess = { (loaded, asteroids) ->
                base = loaded
                bundledSmallBodies = asteroids
                // On the main thread, so a download that arrived during the load is included.
                catalog = loaded.withSmallBodies(asteroids + downloadedSmallBodies)
                loadState = LoadState.Loaded
            },
            onFailure = { loadState = LoadState.Failed(it.message ?: "The star catalog could not be loaded.") },
        )
    }

    /**
     * Replaces the downloaded comets and asteroids (an empty list removes them). They join the bundled asteroids;
     * a downloaded body replaces a bundled one with the same identifier. Call on the main thread; costs a few ms.
     */
    fun setDownloadedSmallBodies(bodies: List<SmallBody>) {
        downloadedSmallBodies = bodies
        val loaded = base ?: return
        catalog = loaded.withSmallBodies(bundledSmallBodies + bodies)
    }

    fun objectWithID(id: String): CelestialObject? = catalog?.objectWithID(id)

    fun search(query: String, limit: Int): List<CatalogSearchResult> = catalog?.search(query, limit) ?: emptyList()
}
