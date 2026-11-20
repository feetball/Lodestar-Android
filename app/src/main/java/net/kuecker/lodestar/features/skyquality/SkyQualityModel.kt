package net.kuecker.lodestar.features.skyquality

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.skyquality.BortleEstimate
import net.kuecker.lodestar.kit.skyquality.DirectionBrightness
import net.kuecker.lodestar.kit.skyquality.LightPollutionMap
import kotlin.math.floor

/**
 * The light pollution map, loaded the first time a screen asks for it (about 10 ms and 6.5 MB, on a background thread) and
 * kept for as long as the process lives. `null` when the bundled file cannot be read: the sky-quality lines then stay out.
 */
internal object SkyQualityMap {
    private val loaded: Lazy<LightPollutionMap?> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { runCatching { LightPollutionMap.load() }.getOrNull() }

    /** The map, loaded off the main thread if it has not been yet. */
    suspend fun get(): LightPollutionMap? = withContext(Dispatchers.Default) { loaded.value }
}

/**
 * What the app knows about the sky quality of one place.
 *
 * @property estimate The Bortle estimate of the place.
 * @property directions The 16 compass directions with the light along each, 30 km out, north first.
 */
internal class SkyReading(val estimate: BortleEstimate, val directions: List<DirectionBrightness>)

/**
 * The sky quality of [location], worked out off the main thread; `null` while it is being worked out, for no location and
 * when the map is unavailable. A new reading is made when the place moves by more than about a kilometre.
 */
@Composable
internal fun rememberSkyReading(location: GeographicLocation?): State<SkyReading?> {
    val key = location?.let { Pair(floor(it.latitude * 100).toInt(), floor(it.longitude * 100).toInt()) }
    return produceState<SkyReading?>(initialValue = null, key) {
        value = if (location == null) {
            null
        } else {
            val map = SkyQualityMap.get()
            withContext(Dispatchers.Default) {
                map?.let { SkyReading(it.bortleEstimate(location), it.darkestDirection(location)) }
            }
        }
    }
}
