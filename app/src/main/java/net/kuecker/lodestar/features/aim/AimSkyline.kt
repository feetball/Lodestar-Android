package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.horizon.HorizonProfile

/**
 * The site's recorded skyline as the geometry the overlay draws: the line itself and the ground below it, as pieces
 * that the viewport can clip. Made once per profile off the main thread; immutable.
 *
 * The skyline is a polyline through one point per whole degree of azimuth (the profile's own nodes, apparent altitudes).
 * The ground is cut into [FAN_COUNT] fans: each is the polygon from the nadir up to the skyline over a stretch of
 * azimuth. A fan spans well under half a circle, so its chords clip like any other ring (see [AimPolygonClip]); together
 * the fans cover exactly the sky below the skyline, also when the view looks down.
 */
class AimSkyline(profile: HorizonProfile) {
    /** The skyline from azimuth 0° to 360° as East–North–Up unit vectors; the last point repeats the first. */
    val line: List<Vector3> = profile.polyline().map { HorizontalCoordinate(it.azimuth, it.altitude).unitVector }

    /**
     * The ground as polygons: for each fan the nadir followed by the skyline points of its azimuth range, packed as
     * x, y, z.
     */
    val fans: List<DoubleArray> = (0 until FAN_COUNT).map { fan ->
        val first = fan * AZIMUTH_STEPS / FAN_COUNT
        val last = (fan + 1) * AZIMUTH_STEPS / FAN_COUNT
        val packed = DoubleArray(3 * (last - first + 2))
        packed[2] = -1.0 // the nadir
        for ((offset, degree) in (first..last).withIndex()) {
            val vector = line[degree]
            packed[3 * (offset + 1)] = vector.x
            packed[3 * (offset + 1) + 1] = vector.y
            packed[3 * (offset + 1) + 2] = vector.z
        }
        packed
    }

    private companion object {
        const val AZIMUTH_STEPS = 360
        const val FAN_COUNT = 8
    }
}
