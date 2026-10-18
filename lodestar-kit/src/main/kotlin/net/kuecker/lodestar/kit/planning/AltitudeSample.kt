package net.kuecker.lodestar.kit.planning

import java.time.Instant

/**
 * The direction of a target at one instant of an altitude curve.
 *
 * @property date The instant of the sample.
 * @property altitude Altitude above the horizon in degrees. The planner's own curves are geometric (no
 * refraction); a custom position function decides what its own curve reports.
 * @property azimuth Azimuth in degrees from north through east, [0, 360).
 */
data class AltitudeSample(val date: Instant, val altitude: Double, val azimuth: Double)
