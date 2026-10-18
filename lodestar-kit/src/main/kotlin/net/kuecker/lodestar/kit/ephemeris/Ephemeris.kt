package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs

/**
 * Apparent positions and physical ephemerides of the Sun, the Moon and the planets.
 *
 * Sources, all with light-time applied:
 * - **Sun and planets**: truncated VSOP87B series for the Earth and each planet
 *   (`PlanetaryTheory`; a few arcseconds against JPL's DE440s).
 * - **Moon**: the truncated ELP-2000/82 series of Meeus, chapter 47 (`LunarTheory`; about 10″).
 *
 * Times are [JulianDate]s; TT drives the theories, so a [JulianDate] built from an [java.time.Instant]
 * carries the estimated ΔT.
 */
object Ephemeris {
    /** Light-time per astronomical unit in days. */
    internal const val lightTimePerAU: Double = SkyFrame.kilometersPerAU / 299_792.458 / JulianDate.secondsPerDay

    /** The position, distance, phase and brightness of [body] at [julianDate]. */
    fun state(body: SolarSystemBody, julianDate: JulianDate): BodyState {
        val earth = EarthState(julianDate)
        val eclipticFrame = EclipticOfDate(julianDate)
        return when (body) {
            SolarSystemBody.SUN -> {
                val sun = sunGeometry(earth)
                BodyState(
                    body = SolarSystemBody.SUN,
                    julianDate = julianDate,
                    geocentricJ2000 = sun.apparent,
                    heliocentricDistanceAU = null,
                    elongation = null,
                    phaseAngle = null,
                    apparentMagnitude = Photometry.sun(sun.apparent.length),
                    isWaxing = null,
                    ecliptic = eclipticFrame.coordinate(sun.apparent),
                )
            }
            SolarSystemBody.MOON -> moonState(earth, eclipticFrame)
            SolarSystemBody.MERCURY ->
                planetState(SolarSystemBody.MERCURY, PlanetaryTheory.Planet.MERCURY, earth, eclipticFrame)
            SolarSystemBody.VENUS ->
                planetState(SolarSystemBody.VENUS, PlanetaryTheory.Planet.VENUS, earth, eclipticFrame)
            SolarSystemBody.MARS ->
                planetState(SolarSystemBody.MARS, PlanetaryTheory.Planet.MARS, earth, eclipticFrame)
            SolarSystemBody.JUPITER ->
                planetState(SolarSystemBody.JUPITER, PlanetaryTheory.Planet.JUPITER, earth, eclipticFrame)
            SolarSystemBody.SATURN ->
                planetState(SolarSystemBody.SATURN, PlanetaryTheory.Planet.SATURN, earth, eclipticFrame)
            SolarSystemBody.URANUS ->
                planetState(SolarSystemBody.URANUS, PlanetaryTheory.Planet.URANUS, earth, eclipticFrame)
            SolarSystemBody.NEPTUNE ->
                planetState(SolarSystemBody.NEPTUNE, PlanetaryTheory.Planet.NEPTUNE, earth, eclipticFrame)
        }
    }

    /**
     * The geocentric apparent position of [body] in AU, as [BodyState.geocentricJ2000], without
     * the physical quantities. Much cheaper than [state].
     */
    fun geocentricJ2000(body: SolarSystemBody, julianDate: JulianDate): Vector3 =
        when (body) {
            SolarSystemBody.MOON -> LunarTheory.geocentricJ2000(julianDate)
            SolarSystemBody.SUN -> sunGeometry(EarthState(julianDate)).apparent
            SolarSystemBody.MERCURY ->
                planetGeometry(PlanetaryTheory.Planet.MERCURY, EarthState(julianDate)).apparent
            SolarSystemBody.VENUS -> planetGeometry(PlanetaryTheory.Planet.VENUS, EarthState(julianDate)).apparent
            SolarSystemBody.MARS -> planetGeometry(PlanetaryTheory.Planet.MARS, EarthState(julianDate)).apparent
            SolarSystemBody.JUPITER ->
                planetGeometry(PlanetaryTheory.Planet.JUPITER, EarthState(julianDate)).apparent
            SolarSystemBody.SATURN ->
                planetGeometry(PlanetaryTheory.Planet.SATURN, EarthState(julianDate)).apparent
            SolarSystemBody.URANUS ->
                planetGeometry(PlanetaryTheory.Planet.URANUS, EarthState(julianDate)).apparent
            SolarSystemBody.NEPTUNE ->
                planetGeometry(PlanetaryTheory.Planet.NEPTUNE, EarthState(julianDate)).apparent
        }

    /**
     * The apparent geocentric ecliptic longitude of the Moon minus that of the Sun, in degrees,
     * [0, 360): 0 at new moon, 90 at first quarter, 180 at full moon, 270 at last quarter.
     */
    internal fun lunarElongation(julianDate: JulianDate): Double {
        val eclipticFrame = EclipticOfDate(julianDate)
        val moon = eclipticFrame.coordinate(LunarTheory.geocentricJ2000(julianDate)).longitude
        val sun = eclipticFrame.coordinate(sunGeometry(EarthState(julianDate)).apparent).longitude
        return AstroMath.normalizeDegrees(moon - sun)
    }

    // MARK: Geometry

    /** The Earth's heliocentric position and orbital velocity at the observation time. */
    private class EarthState(val julianDate: JulianDate) {
        /** Heliocentric position in AU, J2000 equatorial. */
        val position: Vector3 = PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, julianDate.tt)

        /** Velocity divided by the speed of light, J2000 equatorial. */
        val velocity: Vector3 = Aberration.earthVelocity(julianDate)

        /** Applies annual aberration to a geocentric vector, preserving its length. */
        fun aberrated(vector: Vector3): Vector3 =
            Aberration.apply(vector.normalized, velocity) * vector.length
    }

    /**
     * A body's position as seen from the Earth's centre (J2000 equatorial, AU).
     *
     * @property geometric Direction and distance at the instant the light left the body, before aberration.
     * @property apparent With annual aberration (identical to [geometric] for the Moon).
     * @property heliocentric The body's position relative to the Sun when the light left it.
     */
    private class Geometry(val geometric: Vector3, val apparent: Vector3, val heliocentric: Vector3)

    private fun sunGeometry(earth: EarthState): Geometry {
        // The Sun hardly moves in the solar-system barycentric frame during the light-time, so its
        // astrometric direction is the instantaneous one; only aberration shifts it.
        val geometric = -earth.position
        return Geometry(geometric, earth.aberrated(geometric), Vector3.zero)
    }

    private fun moonGeometry(earth: EarthState): Geometry {
        val geometric = LunarTheory.geocentricJ2000(earth.julianDate)
        return Geometry(geometric, geometric, earth.position + geometric)
    }

    private fun planetGeometry(planet: PlanetaryTheory.Planet, earth: EarthState): Geometry {
        // Light-time: the planet is seen where it was `lightTime` ago, the Earth where it is now.
        var lightTime = 0.0
        var position = Vector3.zero
        for (iteration in 0 until 4) {
            position = PlanetaryTheory.heliocentricJ2000(planet, earth.julianDate.tt - lightTime)
            val updated = (position - earth.position).length * lightTimePerAU
            val converged = abs(updated - lightTime) < 2e-6
            lightTime = updated
            if (converged) break
        }
        val geometric = position - earth.position
        return Geometry(geometric, earth.aberrated(geometric), position)
    }

    // MARK: Assembling states

    private fun moonState(earth: EarthState, eclipticFrame: EclipticOfDate): BodyState {
        val moon = moonGeometry(earth)
        val sun = sunGeometry(earth)
        val phaseAngle = moon.heliocentric.angle(moon.geometric)
        val ecliptic = eclipticFrame.coordinate(moon.apparent)
        val sunLongitude = eclipticFrame.coordinate(sun.apparent).longitude
        return BodyState(
            body = SolarSystemBody.MOON,
            julianDate = earth.julianDate,
            geocentricJ2000 = moon.apparent,
            heliocentricDistanceAU = moon.heliocentric.length,
            elongation = moon.apparent.angle(sun.apparent),
            phaseAngle = phaseAngle,
            apparentMagnitude = Photometry.moon(moon.heliocentric.length, moon.geometric.length, phaseAngle),
            isWaxing = AstroMath.normalizeDegrees(ecliptic.longitude - sunLongitude) < 180,
            ecliptic = ecliptic,
        )
    }

    private fun planetState(
        body: SolarSystemBody,
        theory: PlanetaryTheory.Planet,
        earth: EarthState,
        eclipticFrame: EclipticOfDate,
    ): BodyState {
        val planet = planetGeometry(theory, earth)
        val sun = sunGeometry(earth)
        val phaseAngle = planet.heliocentric.angle(planet.geometric)
        return BodyState(
            body = body,
            julianDate = earth.julianDate,
            geocentricJ2000 = planet.apparent,
            heliocentricDistanceAU = planet.heliocentric.length,
            elongation = planet.apparent.angle(sun.apparent),
            phaseAngle = phaseAngle,
            apparentMagnitude = Photometry.planet(
                body, planet.heliocentric, planet.geometric, phaseAngle, earth.julianDate,
            ),
            isWaxing = null,
            ecliptic = eclipticFrame.coordinate(planet.apparent),
        )
    }

    /**
     * Rotation from the J2000 mean equatorial frame to the ecliptic of date with the true equinox
     * of date (precession, nutation, then the true obliquity).
     */
    private class EclipticOfDate(julianDate: JulianDate) {
        val matrix: Matrix3

        init {
            val nutation = Nutation(julianDate)
            val meanObliquity = Obliquity.mean(julianDate)
            matrix = Matrix3.rotationX(-AstroMath.radians(meanObliquity + nutation.obliquity)) *
                nutation.matrix(meanObliquity) *
                Precession.matrixFromJ2000(julianDate)
        }

        fun coordinate(vector: Vector3): EclipticCoordinate = EclipticCoordinate(matrix * vector)
    }
}

/** The state of [body] at this frame's instant. */
fun SkyFrame.state(body: SolarSystemBody): BodyState = Ephemeris.state(body, julianDate)

/** Topocentric horizontal coordinates of [body]'s centre, optionally including refraction. */
fun SkyFrame.horizontal(body: SolarSystemBody, refraction: Boolean = true): HorizontalCoordinate =
    horizontal(Ephemeris.geocentricJ2000(body, julianDate), refraction)

/** Topocentric apparent right ascension and declination of [body] (true equator and equinox of date). */
fun SkyFrame.topocentricEquatorial(body: SolarSystemBody): EquatorialCoordinate =
    topocentricEquatorial(Ephemeris.geocentricJ2000(body, julianDate))

/** Geocentric apparent right ascension and declination of [body] (true equator and equinox of date). */
fun SkyFrame.geocentricApparentEquatorial(body: SolarSystemBody): EquatorialCoordinate =
    EquatorialCoordinate(precessionNutationMatrix * Ephemeris.geocentricJ2000(body, julianDate))
