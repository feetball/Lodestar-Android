package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.asin

/**
 * Position and physical appearance of a solar-system body at one instant, as seen from the
 * centre of the Earth.
 *
 * Instances are produced by [Ephemeris.state]; like Swift's internal initializer, the constructor is
 * not public. Equality compares every field.
 *
 * @property body The body this state describes.
 * @property julianDate The instant of the state (TT drives the ephemeris, UT the Earth's rotation).
 * @property geocentricJ2000 Geocentric apparent position in AU, in the mean equatorial frame of J2000.0.
 * Light-time is applied to every body. Annual aberration is applied to the Sun and the
 * planets but not to the Moon: for the Moon it is cancelled to first order by the Earth's
 * motion during the light-time, so the result is the Moon's geocentric direction one
 * light-time ago. This is the input `SkyFrame.horizontal(geocentricJ2000, refraction)` and
 * `SkyFrame.topocentricEquatorial(geocentricJ2000)` expect.
 * @property distanceAU Geocentric distance in AU (light-time corrected).
 * @property heliocentricDistanceAU Distance from the Sun in AU; `null` for the Sun itself.
 * @property elongation Angular distance from the Sun in degrees, [0, 180]; `null` for the Sun.
 * @property phaseAngle The Sun–body–Earth angle in degrees, [0, 180] (0° = fully lit); `null` for the Sun.
 * @property illuminatedFraction Fraction of the visible disc that is sunlit, [0, 1]; `null` for the Sun.
 * @property apparentMagnitude Apparent visual magnitude. The planets follow Mallama & Hilton (2018) and are good to a
 * few hundredths of a magnitude (Mars to about 0.05, because its surface markings are not
 * modelled); the Moon and the Sun are good to about 0.2 magnitude.
 * @property angularDiameterArcseconds Apparent angular diameter of the body's mean disc in arcseconds, as seen from
 * the geocentre.
 * @property isWaxing For the Moon, whether it is waxing (east of the Sun: its ecliptic longitude exceeds the
 * Sun's by less than 180°); `null` for every other body.
 * @property eclipticLongitudeOfDate Apparent ecliptic longitude in degrees, [0, 360): geocentric, referred to the
 * true equinox of date (includes nutation).
 * @property eclipticLatitudeOfDate Apparent ecliptic latitude in degrees, [-90, 90]: geocentric, referred to the
 * ecliptic of date.
 */
@ConsistentCopyVisibility
data class BodyState private constructor(
    val body: SolarSystemBody,
    val julianDate: JulianDate,
    val geocentricJ2000: Vector3,
    val distanceAU: Double,
    val heliocentricDistanceAU: Double?,
    val elongation: Double?,
    val phaseAngle: Double?,
    val illuminatedFraction: Double?,
    val apparentMagnitude: Double?,
    val angularDiameterArcseconds: Double,
    val isWaxing: Boolean?,
    val eclipticLongitudeOfDate: Double,
    val eclipticLatitudeOfDate: Double,
) {
    /** Geocentric distance in kilometres. */
    val distanceKilometers: Double get() = distanceAU * SkyFrame.kilometersPerAU

    companion object {
        /**
         * Derives the distance, the illuminated fraction and the angular diameter from the given geometry
         * (Swift's internal `init(body:julianDate:geocentricJ2000:heliocentricDistanceAU:elongation:phaseAngle:
         * apparentMagnitude:isWaxing:ecliptic:)`).
         */
        internal operator fun invoke(
            body: SolarSystemBody,
            julianDate: JulianDate,
            geocentricJ2000: Vector3,
            heliocentricDistanceAU: Double?,
            elongation: Double?,
            phaseAngle: Double?,
            apparentMagnitude: Double?,
            isWaxing: Boolean?,
            ecliptic: EclipticCoordinate,
        ): BodyState {
            val distance = geocentricJ2000.length
            val sine = AstroMath.clamp(body.meanRadiusKilometers / (distance * SkyFrame.kilometersPerAU), 0.0, 1.0)
            return BodyState(
                body = body,
                julianDate = julianDate,
                geocentricJ2000 = geocentricJ2000,
                distanceAU = distance,
                heliocentricDistanceAU = heliocentricDistanceAU,
                elongation = elongation,
                phaseAngle = phaseAngle,
                illuminatedFraction = phaseAngle?.let { (1 + AstroMath.cosDeg(it)) / 2 },
                apparentMagnitude = apparentMagnitude,
                angularDiameterArcseconds = 2 * asin(sine) * AstroMath.arcsecondsPerRadian,
                isWaxing = isWaxing,
                eclipticLongitudeOfDate = ecliptic.longitude,
                eclipticLatitudeOfDate = ecliptic.latitude,
            )
        }
    }
}
