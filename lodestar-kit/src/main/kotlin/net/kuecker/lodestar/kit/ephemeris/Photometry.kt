package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Apparent visual magnitudes of the Sun, Moon and planets.
 *
 * The planets follow A. Mallama & J. L. Hilton, "Computing Apparent Planetary Magnitudes for
 * The Astronomical Almanac" (*Astronomy and Computing* 25, 2018; arXiv:1808.01973), section 3.
 * There `r` is the Sun–planet distance (`sunDistance`) and `d` the Earth–planet distance
 * (`earthDistance`) in AU and `α` the Sun–planet–Earth phase angle (`phaseAngle`) in degrees.
 * Mars omits the sub-Earth-longitude and Ls corrections (about ±0.05 mag).
 * Jupiter, Saturn, Uranus and Neptune use the equations for the phase angles seen from the
 * Earth (up to 12°, 6.5°, 3.1° and 1.9°).
 *
 * In Swift this is the internal `Ephemeris.Photometry`; Kotlin cannot add nested types from another file,
 * so it is a top-level object of the same package, and it is public. The Swift internal parameter names
 * `r`, `d` and `α` are replaced by their labels (`sunDistance`, `earthDistance`, `phaseAngle`).
 */
object Photometry {
    /** Visual magnitude of the Sun, −26.74 at 1 AU. */
    fun sun(distanceAU: Double): Double = -26.74 + 5 * log10(distanceAU)

    /**
     * Visual magnitude of the Moon from Allen's phase law, −12.73 + 0.026 α + 4×10⁻⁹ α⁴ at the
     * mean Earth–Moon distance, scaled for the actual Sun–Moon and Earth–Moon distances.
     */
    fun moon(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double {
        val meanEarthDistanceAU = 384_400 / SkyFrame.kilometersPerAU
        return -12.73 + 0.026 * phaseAngle + 4e-9 * phaseAngle.pow(4.0) +
            5 * log10(sunDistance * earthDistance / meanEarthDistanceAU)
    }

    /**
     * Visual magnitude of a planet, or `null` for the Sun and the Moon.
     *
     * @param body The planet.
     * @param heliocentric The planet's position relative to the Sun (J2000 equatorial, AU).
     * @param geocentric The planet's position relative to the Earth (J2000 equatorial, AU).
     * @param phaseAngle The Sun–planet–Earth angle in degrees.
     * @param julianDate The instant (the rotation pole of Saturn and the year for Neptune depend on it).
     */
    fun planet(
        body: SolarSystemBody,
        heliocentric: Vector3,
        geocentric: Vector3,
        phaseAngle: Double,
        julianDate: JulianDate,
    ): Double? {
        val r = heliocentric.length
        val d = geocentric.length
        return when (body) {
            SolarSystemBody.SUN, SolarSystemBody.MOON -> null
            SolarSystemBody.MERCURY -> mercury(r, d, phaseAngle)
            SolarSystemBody.VENUS -> venus(r, d, phaseAngle)
            SolarSystemBody.MARS -> mars(r, d, phaseAngle)
            SolarSystemBody.JUPITER -> jupiter(r, d, phaseAngle)
            SolarSystemBody.SATURN -> {
                val pole = poleDirection(
                    40.589 - 0.036 * julianDate.centuriesTT,
                    83.537 - 0.004 * julianDate.centuriesTT,
                )
                saturn(
                    r, d, phaseAngle,
                    sunLatitude = subLatitude(-heliocentric, pole),
                    earthLatitude = subLatitude(-geocentric, pole),
                )
            }
            SolarSystemBody.URANUS -> {
                val pole = poleDirection(257.311, -15.175)
                uranus(
                    r, d,
                    sunLatitude = planetographic(subLatitude(-heliocentric, pole)),
                    earthLatitude = planetographic(subLatitude(-geocentric, pole)),
                )
            }
            SolarSystemBody.NEPTUNE -> neptune(r, d, julianDate.julianYearTT)
        }
    }

    // MARK: Mallama & Hilton equations

    /** Equation 2. */
    fun mercury(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double =
        5 * log10(sunDistance * earthDistance) - 0.613 +
            AstroMath.polynomial(phaseAngle, 0.0, 6.3280e-2, -1.6336e-3, 3.3644e-5, -3.4265e-7, 1.6893e-9, -3.0334e-12)

    /** Equations 3 (α ≤ 163.7°) and 4. */
    fun venus(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double =
        5 * log10(sunDistance * earthDistance) + (
            if (phaseAngle <= 163.7) {
                AstroMath.polynomial(phaseAngle, -4.384, -1.044e-3, 3.687e-4, -2.814e-6, 8.938e-9)
            } else {
                AstroMath.polynomial(phaseAngle, 236.05828, -2.81914, 8.39034e-3)
            }
            )

    /** Equations 6 (α ≤ 50°) and 7, without the sub-Earth-longitude and Ls terms. */
    fun mars(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double =
        5 * log10(sunDistance * earthDistance) + (
            if (phaseAngle <= 50.0) {
                AstroMath.polynomial(phaseAngle, -1.601, 0.02267, -0.0001302)
            } else {
                AstroMath.polynomial(phaseAngle, -0.367, -0.02573, 0.0003445)
            }
            )

    /** Equation 8 (α ≤ 12°, which covers every geocentric view). */
    fun jupiter(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double =
        5 * log10(sunDistance * earthDistance) + AstroMath.polynomial(phaseAngle, -9.395, -3.7e-4, 6.16e-4)

    /**
     * Equation 10 for the globe and rings. The latitudes are the planetocentric sub-solar and
     * sub-Earth latitudes with respect to the ring plane, in degrees; the effective inclination
     * is their geometric mean, or zero when the rings are lit from the other side than seen.
     */
    fun saturn(
        sunDistance: Double,
        earthDistance: Double,
        phaseAngle: Double,
        sunLatitude: Double,
        earthLatitude: Double,
    ): Double {
        val product = sunLatitude * earthLatitude
        val sineOfInclination = if (product > 0) AstroMath.sinDeg(sqrt(product)) else 0.0
        return 5 * log10(sunDistance * earthDistance) - 8.914 - 1.825 * sineOfInclination + 0.026 * phaseAngle -
            0.378 * sineOfInclination * exp(-2.25 * phaseAngle)
    }

    /**
     * Equation 14. The latitudes are the planetographic sub-solar and sub-Earth latitudes, in
     * degrees; the phase angle is ignored (Uranus never exceeds 3.1°).
     */
    fun uranus(sunDistance: Double, earthDistance: Double, sunLatitude: Double, earthLatitude: Double): Double =
        5 * log10(sunDistance * earthDistance) - 7.110 - 8.4e-4 * (abs(sunLatitude) + abs(earthLatitude)) / 2

    /** Equation 16 (the phase angle is ignored; Neptune never exceeds 1.9°). */
    fun neptune(sunDistance: Double, earthDistance: Double, year: Double): Double {
        val brightness = if (year < 1980) -6.89 else if (year <= 2000) -6.89 - 0.0054 * (year - 1980) else -7.00
        return 5 * log10(sunDistance * earthDistance) + brightness
    }

    // MARK: Rotation poles

    /**
     * Planetocentric latitude in degrees of the sub-point seen along [direction] (from the
     * planet's centre), relative to the planet's equator.
     */
    private fun subLatitude(direction: Vector3, pole: Vector3): Double =
        AstroMath.asinDeg(direction.normalized.dot(pole))

    /**
     * Converts a planetocentric latitude of Uranus to a planetographic one (equation 13,
     * flattening 0.0022927).
     */
    private fun planetographic(planetocentric: Double): Double {
        val flattening = 0.0022927
        return AstroMath.degrees(atan(AstroMath.tanDeg(planetocentric) / ((1 - flattening) * (1 - flattening))))
    }

    /** Unit vector towards a rotation pole given in J2000 equatorial coordinates (IAU 2009 values). */
    private fun poleDirection(rightAscension: Double, declination: Double): Vector3 =
        EquatorialCoordinate(rightAscension, declination).unitVector
}
