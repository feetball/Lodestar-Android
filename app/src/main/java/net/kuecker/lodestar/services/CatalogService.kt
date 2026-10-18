package net.kuecker.lodestar.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSearchResult
import net.kuecker.lodestar.kit.catalog.CelestialObject

/** The bundled object catalog, decoded once off the main thread. */
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
    var catalog by mutableStateOf<Catalog?>(null)
        private set

    /** Decodes the bundled catalog on a background thread. Idempotent. */
    suspend fun load() {
        if (catalog != null || loadState == LoadState.Loading) return
        loadState = LoadState.Loading
        val result = withContext(Dispatchers.Default) { runCatching { Catalog.loadBundled() } }
        result.fold(
            onSuccess = {
                catalog = it
                loadState = LoadState.Loaded
            },
            onFailure = { loadState = LoadState.Failed(it.message ?: "The star catalog could not be loaded.") },
        )
    }

    fun objectWithID(id: String): CelestialObject? = catalog?.objectWithID(id)

    fun search(query: String, limit: Int): List<CatalogSearchResult> = catalog?.search(query, limit) ?: emptyList()
}
