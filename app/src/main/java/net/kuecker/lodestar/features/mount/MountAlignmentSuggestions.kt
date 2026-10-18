package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min

/** A bright star that could be used to align a mount, with where it stands now. */
internal data class MountStarCandidate(
    val obj: CelestialObject,
    val j2000: EquatorialCoordinate,
    /** Where the star stands in the sky now (refraction included). */
    val horizontal: HorizontalCoordinate,
    val magnitude: Double,
)

/** A candidate recommended as the next alignment star. */
internal data class MountStarSuggestion(
    val candidate: MountStarCandidate,
    /** The distance in degrees to the nearest star already used; null for the first star. */
    val separationDegrees: Double?,
)

/**
 * Chooses stars for aligning a Sky-Watcher mount. The fit needs stars that are bright enough to centre, high enough to be clear
 * of the haze but not so high that an alt-azimuth mount struggles, away from the pole (where right ascension means little), and
 * far apart: the first anywhere, the next ones at least 30° from each other and from the first, on other sides of the sky.
 */
internal object MountAlignmentSuggestions {
    /** Stars lower than this are in the haze and hard to centre. */
    const val MINIMUM_ALTITUDE = 25.0

    /** Stars higher than this are close to the zenith, where an alt-azimuth mount turns fast. */
    const val MAXIMUM_ALTITUDE = 75.0

    /** Stars closer to the celestial pole than this are a poor reference for an equatorial mount. */
    const val MAXIMUM_DECLINATION = 80.0

    /** Stars at least this bright are offered. */
    const val STAR_MAGNITUDE_LIMIT = 2.5

    /** The next star is at least this far from every star already used. */
    const val MINIMUM_SEPARATION = 30.0

    /** A star this close to a used one is that star. */
    const val SAME_STAR = 3.0

    /** How many stars the list shows. */
    const val COUNT = 3

    /** The bright stars that are in a good part of the sky at [date]. Searches the catalog, so run it off the main thread. */
    fun candidates(catalog: Catalog, observer: Observer, date: Instant): List<MountStarCandidate> {
        val frame = SkyFrame(date, observer)
        val result = ArrayList<MountStarCandidate>()
        for (star in catalog.renderStars) {
            if (star.magnitude > STAR_MAGNITUDE_LIMIT) break
            val obj = catalog.objectWithID(star.id) ?: continue
            val place = obj.j2000 ?: continue
            if (abs(place.declination) > MAXIMUM_DECLINATION) continue
            val horizontal = frame.horizontal(obj, refraction = true)
            if (horizontal.altitude !in MINIMUM_ALTITUDE..MAXIMUM_ALTITUDE) continue
            result.add(MountStarCandidate(obj, place, horizontal, star.magnitude))
        }
        return result
    }

    /**
     * The best [count] candidates for the next star, best first, given the places of the stars already used ([used]). With none
     * used the brightest high stars come first; after that the stars farthest from all used ones, each at least
     * [MINIMUM_SEPARATION] away, the brighter first among equals.
     */
    fun suggest(candidates: List<MountStarCandidate>, used: List<EquatorialCoordinate>, count: Int = COUNT): List<MountStarSuggestion> {
        val scored = ArrayList<Pair<Double, MountStarSuggestion>>()
        for (candidate in candidates) {
            val separation = used.minOfOrNull { it.angularSeparation(candidate.j2000) }
            if (separation != null && (separation < MINIMUM_SEPARATION)) continue
            val score = if (separation == null) {
                -BRIGHTNESS_WEIGHT * candidate.magnitude - abs(candidate.horizontal.altitude - PREFERRED_ALTITUDE) / ALTITUDE_DIVISOR
            } else {
                min(separation, FAR_ENOUGH) - BRIGHTNESS_WEIGHT * candidate.magnitude
            }
            scored.add(score to MountStarSuggestion(candidate, separation))
        }
        return scored.sortedByDescending { it.first }.take(count).map { it.second }
    }

    private const val BRIGHTNESS_WEIGHT = 8.0
    private const val PREFERRED_ALTITUDE = 50.0
    private const val ALTITUDE_DIVISOR = 5.0
    private const val FAR_ENOUGH = 90.0
}
