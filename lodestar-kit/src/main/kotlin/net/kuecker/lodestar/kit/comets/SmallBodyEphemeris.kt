package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Apparent positions, distances and magnitudes of comets and asteroids from their osculating elements.
 *
 * The heliocentric motion is the two-body motion of [OrbitPropagator]; the Earth comes from the same truncated VSOP87B
 * theory as the planets of `Ephemeris`, and the geocentric position is built exactly as for a planet: light-time by
 * iteration (the body is seen where it was a light-time ago, the Earth where it is now), then annual aberration, so
 * `SkyFrame.horizontal(geocentricJ2000, refraction)` works unchanged.
 *
 * **Accuracy.** The Earth contributes a few arcseconds (VSOP87B) and the frame about 0.02″. The dominant error is that
 * real bodies are perturbed by the planets while the elements describe a Kepler orbit that is exact only at the epoch
 * of the elements. Measured against JPL Horizons (osculating elements taken at the test epoch itself, so only the
 * perturbation of the following weeks remains): below one arcsecond on the epoch, about one arcsecond after 15 days for
 * a comet 0.5 AU from the Earth and 0.6 arcseconds after 60 days for Halley; main-belt asteroids are within 4 arcseconds
 * after four months. Published elements that are 200 days away from the date are still good to 30 arcseconds (12P in
 * April 2024 from the Small-Body Database elements of September 2023), elements 18 years old are not (Halley in 1986 from
 * the elements of 1968: 0.7° to 6°). MPC and JPL publish new elements every few weeks to months, so refresh the element
 * files regularly (see `API.md`). Non-gravitational forces of comets are not modelled either.
 * The magnitudes are the catalogs' formulas and are uncertain by a magnitude or more for comets.
 *
 * **Cost.** One [state] (Earth theory, aberration, Kepler solution, light-time) takes about 5 microseconds
 * on a desktop CPU, [geocentricJ2000] about 4.
 */
object SmallBodyEphemeris {
    /** The default faintest magnitude for [SmallBodyState.isPhotographable]: 10, within reach of a camera on a tripod. */
    const val defaultMagnitudeLimit: Double = 10.0

    /** The default smallest elongation for [SmallBodyState.isPhotographable]: 25° from the Sun. */
    const val defaultMinimumElongation: Double = 25.0

    /** The Earth's heliocentric position, orbital velocity and apparent Sun at one instant. */
    internal class EarthReference(val julianDate: JulianDate) {
        /** Heliocentric position in AU, J2000 equatorial. */
        val position: Vector3 = PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, julianDate.tt)

        /** Velocity divided by the speed of light, J2000 equatorial. */
        val velocity: Vector3 = Aberration.earthVelocity(julianDate)

        /** The Sun's apparent geocentric position (light-time and aberration), AU, J2000 equatorial. */
        val sunApparent: Vector3 = aberrated(-position)

        /** Applies annual aberration to a geocentric vector, preserving its length. */
        fun aberrated(vector: Vector3): Vector3 = Aberration.apply(vector.normalized, velocity) * vector.length
    }

    /**
     * A body's position as seen from the Earth's centre (J2000 equatorial, AU).
     *
     * @property geometric Direction and distance at the instant the light left the body, before aberration.
     * @property apparent With annual aberration.
     * @property heliocentric The body's position relative to the Sun when the light left it.
     */
    internal class Geometry(val geometric: Vector3, val apparent: Vector3, val heliocentric: Vector3)

    /** The position, distance, phase and brightness of [body] at [julianDate]; `null` if its orbit cannot be evaluated then. */
    fun state(body: SmallBody, julianDate: JulianDate): SmallBodyState? = state(body, EarthReference(julianDate))

    /**
     * The geocentric apparent position of [body] in AU, as [SmallBodyState.geocentricJ2000], without the physical
     * quantities; `null` if the orbit cannot be evaluated at [julianDate] (an unbound orbit more than
     * [OrbitPropagator.maximumUnboundSpanDays] from perihelion). Cheaper than [state].
     */
    fun geocentricJ2000(body: SmallBody, julianDate: JulianDate): Vector3? =
        geometry(body.orbit, EarthReference(julianDate))?.apparent

    internal fun geometry(orbit: SmallBodyElements, earth: EarthReference): Geometry? {
        // Light-time: the body is seen where it was `lightTime` ago, the Earth where it is now.
        var lightTime = 0.0
        var position = Vector3.zero
        for (iteration in 0 until 4) {
            position = OrbitPropagator.heliocentricJ2000(orbit, earth.julianDate.tt - lightTime) ?: return null
            val updated = (position - earth.position).length * Ephemeris.lightTimePerAU
            val converged = abs(updated - lightTime) < 2e-6
            lightTime = updated
            if (converged) break
        }
        val geometric = position - earth.position
        return Geometry(geometric, earth.aberrated(geometric), position)
    }

    internal fun state(body: SmallBody, earth: EarthReference): SmallBodyState? {
        val geometry = geometry(body.orbit, earth) ?: return null
        val phaseAngle = geometry.heliocentric.angle(geometry.geometric)
        val sunDistance = geometry.heliocentric.length
        val earthDistance = geometry.apparent.length
        return SmallBodyState(
            body = body,
            julianDate = earth.julianDate,
            astrometric = geometry.geometric,
            apparent = geometry.apparent,
            heliocentric = geometry.heliocentric,
            elongation = geometry.apparent.angle(earth.sunApparent),
            phaseAngle = phaseAngle,
            apparentMagnitude = body.photometry?.apparentMagnitude(sunDistance, earthDistance, phaseAngle),
            nuclearMagnitude = body.photometry?.nuclearMagnitude(sunDistance, earthDistance, phaseAngle),
            tailPositionAngle = antiSolarPositionAngle(geometry.geometric, geometry.heliocentric),
        )
    }

    /**
     * The position angle in degrees, [0, 360), from celestial north through east, of the direction [away] seen from the
     * direction [toward] (both J2000 equatorial); `null` if the direction lies along the line of sight.
     */
    internal fun antiSolarPositionAngle(toward: Vector3, away: Vector3): Double? {
        val line = toward.normalized
        val eastRaw = Vector3(-line.y, line.x, 0.0)
        val eastLength = eastRaw.length
        if (!(eastLength > 1e-12)) return null
        val east = eastRaw / eastLength
        val north = line.cross(east)
        val direction = away.normalized
        val towardsEast = direction.dot(east)
        val towardsNorth = direction.dot(north)
        if (!(sqrt(towardsEast * towardsEast + towardsNorth * towardsNorth) > 1e-9)) return null
        return AstroMath.normalizeDegrees(AstroMath.atan2Deg(towardsEast, towardsNorth))
    }

    /**
     * The angle of the anti-solar direction (the tail) against the vertical in the sky seen from the observer of
     * [frame], in degrees, (−180, 180]: 0° means the tail points straight up, +90° to the observer's right when facing
     * the body, 180° straight down. Meant for framing: tilt the camera by this angle to put the tail along an image
     * axis. Parallax is included; refraction is not. `null` at the zenith or when the tail points along the line of
     * sight.
     */
    fun tailAngleFromVertical(state: SmallBodyState, frame: SkyFrame): Double? {
        val topocentric = frame.precessionNutationMatrix * state.geocentricJ2000 - frame.observerPositionOfDate
        val direction = (frame.equatorialOfDateToHorizontal * topocentric).normalized
        val tail = (frame.equatorialOfDateToHorizontal * (frame.precessionNutationMatrix * state.heliocentricJ2000)).normalized
        val upRaw = Vector3(0.0, 0.0, 1.0) - direction * direction.z
        val upLength = upRaw.length
        if (!(upLength > 1e-9)) return null
        val up = upRaw / upLength
        val right = direction.cross(up)
        val projected = tail - direction * tail.dot(direction)
        val towardsRight = projected.dot(right)
        val towardsUp = projected.dot(up)
        if (!(sqrt(towardsRight * towardsRight + towardsUp * towardsUp) > 1e-9)) return null
        return AstroMath.atan2Deg(towardsRight, towardsUp)
    }

    // MARK: Rise, transit and set

    /**
     * The geometric altitude in degrees (topocentric, no refraction) of [body]'s centre for [observer] as a function of
     * time, in the form `RiseSetCalculator`'s generic searches expect. It is `NaN` where the orbit cannot be evaluated.
     * Each call builds a `SkyFrame` and one [state] worth of geometry (about 10 microseconds).
     */
    fun altitude(body: SmallBody, observer: Observer): (Instant) -> Double = { instant ->
        val frame = SkyFrame(instant, observer)
        val position = geocentricJ2000(body, frame.julianDate)
        if (position == null) Double.NaN else frame.horizontal(position, refraction = false).altitude
    }

    /**
     * The first rise, transit and set of [body] inside [interval] for [observer], with the standard horizon altitude
     * (`RiseSetCalculator.standardHorizonAltitude`). Samples the altitude every ten minutes and refines the crossings
     * (about 150 evaluations per day plus the refinement; a few milliseconds).
     */
    fun riseTransitSet(body: SmallBody, observer: Observer, interval: DateInterval): RiseTransitSet =
        RiseSetCalculator.riseTransitSet(altitude(body, observer), RiseSetCalculator.standardHorizonAltitude, interval)

    /** All crossings of the standard horizon by [body] inside [interval]. */
    fun crossings(body: SmallBody, observer: Observer, interval: DateInterval): List<AltitudeCrossing> =
        RiseSetCalculator.crossings(altitude(body, observer), RiseSetCalculator.standardHorizonAltitude, interval)

    /** All upper culminations of [body] inside [interval]. */
    fun upperCulminations(body: SmallBody, observer: Observer, interval: DateInterval): List<AltitudeCulmination> =
        RiseSetCalculator.upperCulminations(altitude(body, observer), interval)
}

/** The state of [body] at this frame's instant; `null` if its orbit cannot be evaluated then. */
fun SkyFrame.state(body: SmallBody): SmallBodyState? = SmallBodyEphemeris.state(body, julianDate)

/** Topocentric horizontal coordinates of [body]'s centre, optionally including refraction; `null` as for [state]. */
fun SkyFrame.horizontal(body: SmallBody, refraction: Boolean = true): HorizontalCoordinate? =
    SmallBodyEphemeris.geocentricJ2000(body, julianDate)?.let { horizontal(it, refraction) }

/** Topocentric apparent right ascension and declination of [body] (true equator and equinox of date); `null` as for [state]. */
fun SkyFrame.topocentricEquatorial(body: SmallBody): EquatorialCoordinate? =
    SmallBodyEphemeris.geocentricJ2000(body, julianDate)?.let { topocentricEquatorial(it) }

/** Geocentric apparent right ascension and declination of [body] (true equator and equinox of date); `null` as for [state]. */
fun SkyFrame.geocentricApparentEquatorial(body: SmallBody): EquatorialCoordinate? =
    SmallBodyEphemeris.geocentricJ2000(body, julianDate)?.let { EquatorialCoordinate(precessionNutationMatrix * it) }
