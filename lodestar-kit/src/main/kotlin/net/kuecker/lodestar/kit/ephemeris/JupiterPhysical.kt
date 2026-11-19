package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jupiter's physical ephemeris for one instant.
 *
 * @property systemILongitude Longitude of the System I central meridian (the equatorial belt, 9h 50m rotation) of the
 * illuminated disc as seen from the Earth, degrees, [0, 360).
 * @property systemIILongitude Longitude of the System II central meridian (the rest of the planet, 9h 55m rotation; the
 * Great Red Spot's system), degrees, [0, 360).
 * @property earthDeclination Planetocentric declination of the Earth, degrees (how far the equator is tilted towards us).
 * @property sunDeclination Planetocentric declination of the Sun, degrees.
 * @property positionAngle Position angle of Jupiter's north rotation pole on the sky, degrees from celestial north
 * through east, [0, 360).
 * @property distanceAU Geocentric distance of the planet in AU (light-time corrected).
 */
data class JupiterPhysicalState(
    val systemILongitude: Double,
    val systemIILongitude: Double,
    val earthDeclination: Double,
    val sunDeclination: Double,
    val positionAngle: Double,
    val distanceAU: Double,
)

/**
 * The physical ephemeris of Jupiter, after Jean Meeus, *Astronomical Algorithms*, 2nd ed., chapter 43 (steps 1 to 18,
 * the more accurate of the book's two methods, which also gives the position angle): the rotation elements of the
 * planet (pole of 1950.0 with its precession, System I and II rotation rates), the light-time corrected geometry from
 * the kit's VSOP87 positions of the Earth and Jupiter, the phase correction, and the corrections for aberration and
 * nutation of the pole's direction.
 *
 * Accuracy against the book's worked example (1992 December 16, 0h TD): the central meridians agree to 0.02°, the
 * declinations to 0.01° and the position angle to 0.03° (the book prints two decimals and uses the full VSOP87).
 * One call costs about 20 µs.
 */
object JupiterPhysical {
    private const val epochJD = 2_433_282.5
    private const val systemIRate = 877.90003539
    /** System II's rotation rate in degrees per day. */
    internal const val systemIIRate = 870.27003539
    private const val systemIPhaseDegreesPerAU = 5.07033
    private const val systemIIPhaseDegreesPerAU = 5.02626
    private const val aberrationConstant = 0.005693

    /** The physical ephemeris of Jupiter at [julianDate] (its TT drives the theory). */
    fun state(julianDate: JulianDate): JupiterPhysicalState {
        val jde = julianDate.tt
        // Steps 1 and 2: the pole (equinox 1950.0) and the rotation phases.
        val d = jde - epochJD
        val t1 = d / JulianDate.daysPerJulianCentury
        val alpha0 = AstroMath.radians(268.0 + 0.1061 * t1)
        val delta0 = AstroMath.radians(64.5 - 0.0164 * t1)
        val w1 = 17.710 + systemIRate * d
        val w2 = 16.838 + systemIIRate * d

        // Steps 3 to 7: heliocentric ecliptic positions of date, light-time of Jupiter, geocentric rectangular coordinates.
        val meanObliquity = Obliquity.mean(julianDate)
        val toEcliptic = Matrix3.rotationX(AstroMath.radians(meanObliquity)).transposed * Precession.matrixFromJ2000(julianDate)
        val earth = toEcliptic * PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, jde)
        val earthDistance = earth.length
        val earthLongitude = atan2(earth.y, earth.x)
        var delta = 4.0
        var jupiter = Vector3.zero
        var geocentric = Vector3.zero
        for (iteration in 0 until 4) {
            val lightTime = delta * Ephemeris.lightTimePerAU
            jupiter = toEcliptic * PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.JUPITER, jde - lightTime)
            geocentric = jupiter - earth
            val updated = geocentric.length
            val converged = abs(updated - delta) < 1e-9
            delta = updated
            if (converged) break
        }
        val radius = jupiter.length
        val longitude = atan2(jupiter.y, jupiter.x)
        val latitude = atan2(jupiter.z, sqrt(jupiter.x * jupiter.x + jupiter.y * jupiter.y))

        // Steps 8 to 10: the Sun's declination seen from Jupiter.
        val epsilon0 = AstroMath.radians(meanObliquity)
        val sinEpsilon0 = sin(epsilon0)
        val cosEpsilon0 = cos(epsilon0)
        val sinLongitude = sin(longitude)
        val cosLongitude = cos(longitude)
        val sinLatitude = sin(latitude)
        val cosLatitude = cos(latitude)
        val alphaS = atan2(cosEpsilon0 * sinLongitude - sinEpsilon0 * sinLatitude / cosLatitude, cosLongitude)
        val deltaS = asin(cosEpsilon0 * sinLatitude + sinEpsilon0 * cosLatitude * sinLongitude)
        val sinDelta0 = sin(delta0)
        val cosDelta0 = cos(delta0)
        val sunDeclination = asin(
            AstroMath.clamp(-sinDelta0 * sin(deltaS) - cosDelta0 * cos(deltaS) * cos(alpha0 - alphaS), -1.0, 1.0),
        )

        // Steps 11 to 13: the Earth's declination and the central meridians.
        val u = geocentric.y * cosEpsilon0 - geocentric.z * sinEpsilon0
        val v = geocentric.y * sinEpsilon0 + geocentric.z * cosEpsilon0
        var alpha = atan2(u, geocentric.x)
        var delta1 = atan(v / sqrt(geocentric.x * geocentric.x + u * u))
        val sinDelta = sin(delta1)
        val cosDelta = cos(delta1)
        val sinAlpha0Alpha = sin(alpha0 - alpha)
        val cosAlpha0Alpha = cos(alpha0 - alpha)
        val zeta = atan2(sinDelta0 * cosDelta * cosAlpha0Alpha - sinDelta * cosDelta0, cosDelta * sinAlpha0Alpha)
        val earthDeclination = asin(AstroMath.clamp(-sinDelta0 * sinDelta - cosDelta0 * cosDelta * cosAlpha0Alpha, -1.0, 1.0))
        var omega1 = w1 - AstroMath.degrees(zeta) - systemIPhaseDegreesPerAU * delta
        var omega2 = w2 - AstroMath.degrees(zeta) - systemIIPhaseDegreesPerAU * delta

        // Step 14: the phase correction.
        var phase = AstroMath.degrees(
            (2 * radius * delta + earthDistance * earthDistance - radius * radius - delta * delta) / (4 * radius * delta),
        )
        if (sin(longitude - earthLongitude) < 0) phase = -phase
        omega1 += phase
        omega2 += phase

        // Steps 15 to 17: aberration and nutation of the direction of Jupiter and of the pole.
        val nutation = Nutation(julianDate)
        val dPsi = AstroMath.radians(nutation.longitude)
        val dEpsilon = AstroMath.radians(nutation.obliquity)
        val epsilon = epsilon0 + dEpsilon
        val sinEpsilon = sin(epsilon)
        val cosEpsilon = cos(epsilon)
        val sinAlpha = sin(alpha)
        val cosAlpha = cos(alpha)
        val sinEarthLongitude = sin(earthLongitude)
        val cosEarthLongitude = cos(earthLongitude)
        val aberration = AstroMath.radians(aberrationConstant)
        alpha += aberration * (cosAlpha * cosEarthLongitude * cosEpsilon + sinAlpha * sinEarthLongitude) / cosDelta
        delta1 += aberration * (
            cosEarthLongitude * cosEpsilon * (sinEpsilon / cosEpsilon * cosDelta - sinAlpha * sinDelta) +
                cosAlpha * sinDelta * sinEarthLongitude
            )
        val tanDelta = sinDelta / cosDelta
        val deltaAlpha = (cosEpsilon + sinEpsilon * sinAlpha * tanDelta) * dPsi - cosAlpha * tanDelta * dEpsilon
        val deltaDelta = sinEpsilon * cosAlpha * dPsi + sinAlpha * dEpsilon
        val alphaApparent = alpha + deltaAlpha
        val deltaApparent = delta1 + deltaDelta
        val sinAlpha0 = sin(alpha0)
        val cosAlpha0 = cos(alpha0)
        val tanDelta0 = sinDelta0 / cosDelta0
        val deltaAlpha0 = (cosEpsilon + sinEpsilon * sinAlpha0 * tanDelta0) * dPsi - cosAlpha0 * tanDelta0 * dEpsilon
        val deltaDelta0 = sinEpsilon * cosAlpha0 * dPsi + sinAlpha0 * dEpsilon
        val alpha0Apparent = alpha0 + deltaAlpha0
        val delta0Apparent = delta0 + deltaDelta0

        // Step 18: the position angle of the pole.
        val sinDeltaApparent = sin(deltaApparent)
        val cosDeltaApparent = cos(deltaApparent)
        val sinDelta0Apparent = sin(delta0Apparent)
        val cosDelta0Apparent = cos(delta0Apparent)
        val sinDifference = sin(alpha0Apparent - alphaApparent)
        val cosDifference = cos(alpha0Apparent - alphaApparent)
        val positionAngle = atan2(
            cosDelta0Apparent * sinDifference,
            sinDelta0Apparent * cosDeltaApparent - cosDelta0Apparent * sinDeltaApparent * cosDifference,
        )
        return JupiterPhysicalState(
            systemILongitude = AstroMath.normalizeDegrees(omega1),
            systemIILongitude = AstroMath.normalizeDegrees(omega2),
            earthDeclination = AstroMath.degrees(earthDeclination),
            sunDeclination = AstroMath.degrees(sunDeclination),
            positionAngle = AstroMath.normalizeDegrees(AstroMath.degrees(positionAngle)),
            distanceAU = delta,
        )
    }
}
