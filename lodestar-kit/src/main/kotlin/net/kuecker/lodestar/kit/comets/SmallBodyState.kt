package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*

/**
 * Position and appearance of a comet or asteroid at one instant, as seen from the centre of the Earth; the counterpart
 * of `BodyState` for the planets. Instances are produced by [SmallBodyEphemeris.state]. Equality compares every field.
 *
 * @property body The body this state describes.
 * @property julianDate The instant of the state.
 * @property geocentricJ2000 Geocentric apparent position in AU in the mean equatorial frame of J2000.0, with
 * light-time and annual aberration applied exactly as for the planets: the input of
 * `SkyFrame.horizontal(geocentricJ2000, refraction)`.
 * @property astrometricJ2000 The same position without aberration (what star charts and JPL Horizons' "astrometric"
 * column show): the direction in which the body was when the light left it.
 * @property heliocentricJ2000 The position relative to the Sun when the light left the body, in AU, J2000 equatorial.
 * @property distanceAU The distance from the Earth Δ in AU (light-time corrected).
 * @property heliocentricDistanceAU The distance from the Sun r in AU.
 * @property elongation The angular distance from the Sun in degrees, [0, 180].
 * @property phaseAngle The Sun–body–Earth angle in degrees, [0, 180].
 * @property apparentMagnitude The total magnitude of a comet or the visual magnitude of an asteroid; `null` without
 * magnitude parameters.
 * @property nuclearMagnitude The magnitude of the bare nucleus of a comet that has nuclear parameters; `null` otherwise.
 * @property tailPositionAngle The position angle of the anti-solar direction at the body in degrees, [0, 360): the
 * direction of the Sun-to-body line extended on the sky, measured from celestial north through east (J2000 equator).
 * The ion tail of a comet points roughly this way; the dust tail curves from it towards the trail of the orbit.
 * `null` when the Sun-body line points at the Earth or away from it. The tail appears foreshortened by the factor
 * `sin(phaseAngle)`.
 */
@ConsistentCopyVisibility
data class SmallBodyState private constructor(
    val body: SmallBody,
    val julianDate: JulianDate,
    val geocentricJ2000: Vector3,
    val astrometricJ2000: Vector3,
    val heliocentricJ2000: Vector3,
    val distanceAU: Double,
    val heliocentricDistanceAU: Double,
    val elongation: Double,
    val phaseAngle: Double,
    val apparentMagnitude: Double?,
    val nuclearMagnitude: Double?,
    val tailPositionAngle: Double?,
) {
    /** Geocentric distance in kilometres. */
    val distanceKilometers: Double get() = distanceAU * SkyFrame.kilometersPerAU

    /**
     * Whether the body is worth a photograph: its magnitude is known and at most [magnitudeLimit], and it stands at
     * least [minimumElongation] degrees from the Sun (closer in, twilight and glare hide it).
     */
    fun isPhotographable(
        magnitudeLimit: Double = SmallBodyEphemeris.defaultMagnitudeLimit,
        minimumElongation: Double = SmallBodyEphemeris.defaultMinimumElongation,
    ): Boolean {
        val magnitude = apparentMagnitude ?: return false
        return magnitude <= magnitudeLimit && elongation >= minimumElongation
    }

    companion object {
        /** Assembles a state from the geometry (Swift's internal memberwise initializer). */
        internal operator fun invoke(
            body: SmallBody,
            julianDate: JulianDate,
            astrometric: Vector3,
            apparent: Vector3,
            heliocentric: Vector3,
            elongation: Double,
            phaseAngle: Double,
            apparentMagnitude: Double?,
            nuclearMagnitude: Double?,
            tailPositionAngle: Double?,
        ): SmallBodyState = SmallBodyState(
            body = body,
            julianDate = julianDate,
            geocentricJ2000 = apparent,
            astrometricJ2000 = astrometric,
            heliocentricJ2000 = heliocentric,
            distanceAU = apparent.length,
            heliocentricDistanceAU = heliocentric.length,
            elongation = elongation,
            phaseAngle = phaseAngle,
            apparentMagnitude = apparentMagnitude,
            nuclearMagnitude = nuclearMagnitude,
            tailPositionAngle = tailPositionAngle,
        )
    }
}
