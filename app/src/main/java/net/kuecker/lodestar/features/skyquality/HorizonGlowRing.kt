package net.kuecker.lodestar.features.skyquality

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.skyquality.DirectionBrightness
import net.kuecker.lodestar.kit.skyquality.horizonGlow
import kotlin.math.floor

/**
 * The brightness of the horizon by azimuth for the thin glow ring of the sky chart: [SEGMENTS] values from 0 (dark) to 1,
 * the first centred on north, one every 5°. Made once per place, so that drawing the ring allocates nothing.
 *
 * @property glow One value per segment, 0 to 1.
 * @property spokenSummary One sentence for screen readers: where the horizon is brightest.
 */
internal class HorizonGlowRing(val glow: FloatArray, val spokenSummary: String) {
    companion object {
        /** The number of segments of the ring. */
        const val SEGMENTS = 72

        /** The ring of a list from `LightPollutionMap.darkestDirection`; `null` when no direction has any light. */
        fun from(directions: List<DirectionBrightness>): HorizonGlowRing? {
            val brightest = directions.maxByOrNull { it.meanLevel } ?: return null
            if (brightest.meanLevel <= 0.0) return null
            val values = FloatArray(SEGMENTS) { directions.horizonGlow(it * 360.0 / SEGMENTS).toFloat() }
            val direction = AngleFormatter.compassPoint(brightest.azimuth).fullName.lowercase()
            return HorizonGlowRing(values, "A thin glow ring at the horizon marks the artificial light; it is brightest toward the $direction.")
        }
    }
}

/**
 * The glow ring around [location], worked out off the main thread; `null` while it is being worked out, where there is no
 * light, for no location and when the map is unavailable.
 */
@Composable
internal fun rememberHorizonGlowRing(location: GeographicLocation?): State<HorizonGlowRing?> {
    val key = location?.let { Pair(floor(it.latitude * 100).toInt(), floor(it.longitude * 100).toInt()) }
    return produceState<HorizonGlowRing?>(initialValue = null, key) {
        value = if (location == null) {
            null
        } else {
            val map = SkyQualityMap.get()
            withContext(Dispatchers.Default) { map?.let { HorizonGlowRing.from(it.darkestDirection(location)) } }
        }
    }
}
