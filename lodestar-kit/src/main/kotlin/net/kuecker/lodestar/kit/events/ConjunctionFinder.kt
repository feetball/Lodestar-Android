package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The bright stars near the ecliptic, and the Pleiades, that the Moon passes close to.
 *
 * Places are Hipparcos (new reduction) J2000 positions and proper motions as in the kit's star catalog; the Pleiades
 * are the centre of the cluster M 45 (SIMBAD, 03h 47m, +24° 07′) with the proper motion of Alcyone.
 *
 * @property rawValue The camel-case name used as the serialized form, e.g. `"antares"`.
 */
@Serializable
enum class ReferenceStar(val rawValue: String) {
    /** α Leonis. */
    @SerialName("regulus")
    REGULUS("regulus"),

    /** α Virginis. */
    @SerialName("spica")
    SPICA("spica"),

    /** α Scorpii. */
    @SerialName("antares")
    ANTARES("antares"),

    /** α Tauri. */
    @SerialName("aldebaran")
    ALDEBARAN("aldebaran"),

    /** The Pleiades (M 45). */
    @SerialName("pleiades")
    PLEIADES("pleiades"),
    ;

    /** The English name, e.g. "Spica". */
    val displayName: String
        get() = when (this) {
            REGULUS -> "Regulus"
            SPICA -> "Spica"
            ANTARES -> "Antares"
            ALDEBARAN -> "Aldebaran"
            PLEIADES -> "The Pleiades"
        }

    /** The J2000.0 catalog place. */
    val position: EquatorialCoordinate
        get() = when (this) {
            REGULUS -> EquatorialCoordinate(152.092967, 11.967207)
            SPICA -> EquatorialCoordinate(201.298243, -11.161322)
            ANTARES -> EquatorialCoordinate(247.351917, -26.432002)
            ALDEBARAN -> EquatorialCoordinate(68.980162, 16.509301)
            PLEIADES -> EquatorialCoordinate(56.75, 24.1167)
        }

    /** The proper motion in mas/yr. */
    val properMotion: ProperMotion
        get() = when (this) {
            REGULUS -> ProperMotion(-249.4, 4.91)
            SPICA -> ProperMotion(-42.5, -31.73)
            ANTARES -> ProperMotion(-10.16, -23.21)
            ALDEBARAN -> ProperMotion(62.78, -189.36)
            PLEIADES -> ProperMotion(19.35, -43.11)
        }

    companion object {
        /** The star whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ReferenceStar? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** One of the two things that come close in a [Conjunction]: a solar-system body or a reference star. */
sealed interface ConjunctionTarget {
    /** The Moon or a planet (Swift `.body(_)`). */
    data class Body(val body: SolarSystemBody) : ConjunctionTarget

    /** A reference star or the Pleiades (Swift `.star(_)`). */
    data class Star(val star: ReferenceStar) : ConjunctionTarget

    /** The English name, e.g. "Venus" or "Antares". */
    val displayName: String
        get() = when (this) {
            is Body -> body.displayName
            is Star -> star.displayName
        }

    /** What to point a camera at for this target. */
    val aimTarget: AimTarget
        get() = when (this) {
            is Body -> AimTarget.Body(body)
            is Star -> AimTarget.Fixed(star.position, star.properMotion)
        }
}

/**
 * The closest approach of the Moon or a planet as seen at an observer on the ground.
 *
 * @property time The instant of the least topocentric separation.
 * @property separation The least separation of the centres in degrees.
 * @property isOccultation `true` when the Moon's disc covers the centre of the other body at that instant.
 */
data class TopocentricApproach(
    val time: Instant,
    val separation: Double,
    val isOccultation: Boolean,
)

/**
 * A close approach of two bodies on the sky.
 *
 * @property first The first body (the Moon when it takes part, otherwise the one that comes first among the planets).
 * @property second The second body: a planet or a reference star.
 * @property separation The least geocentric angular separation in degrees; the event's date is its instant.
 * @property solarElongation The angular distance from the Sun in degrees (of the Moon, or of the midpoint of two
 * planets); small values mean the pair stands in the glare of the Sun.
 * @property occultationPossible `true` for the Moon when its disc can cover the other body somewhere on Earth: the
 * geocentric separation is below the Moon's semidiameter plus its horizontal parallax plus the other body's radius.
 * @property topocentric For the Moon with an observer: the closest approach as seen from the observer's place, where
 * the Moon's parallax (up to about 1°) shifts it; `null` otherwise.
 */
data class Conjunction(
    val first: ConjunctionTarget,
    val second: ConjunctionTarget,
    val separation: Double,
    val solarElongation: Double,
    val occultationPossible: Boolean,
    val topocentric: TopocentricApproach?,
) {
    /** `true` when the Moon is the first body. */
    val involvesMoon: Boolean get() = first == ConjunctionTarget.Body(SolarSystemBody.MOON)
}

/**
 * Finds conjunctions: close approaches of the Moon with the planets and the reference stars, and of the planets with
 * each other.
 *
 * The planets are sampled daily and the Moon every six hours; each local minimum of the separation that comes within a
 * safety margin of the threshold is refined by golden-section search on the exact geocentric apparent positions
 * (about 25 positions each). Cost for one year: about 30 ms on a desktop JVM, dominated by the planetary positions.
 */
object ConjunctionFinder {
    private val planets: List<SolarSystemBody> = listOf(
        SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS, SolarSystemBody.JUPITER,
        SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
    )

    private const val planetStep: Double = 1.0
    private const val moonStep: Double = 0.25
    private const val pruneMargin: Double = 0.5
    private const val refineTolerance: Double = 1e-5
    private const val topocentricHalfWindow: Double = 0.125

    /**
     * The conjunctions from [from] to [to] (both inclusive) whose least separation is within the thresholds of
     * [options] and whose solar elongation is at least [EventOptions.minimumSolarElongation], in time order. With an
     * [observer], conjunctions of the Moon carry the topocentric approach.
     */
    fun events(
        from: Instant,
        to: Instant,
        observer: Observer? = null,
        options: EventOptions = EventOptions(),
    ): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        val scan = Scan(fromUT, toUT)
        val found = ArrayList<AstronomicalEvent>()

        // Planet with planet.
        for (i in planets.indices) {
            for (j in i + 1 until planets.size) {
                val separations = DoubleArray(scan.dayCount + 1) { scan.planetVectors[i][it].angle(scan.planetVectors[j][it]) }
                for (index in EventSearch.localMinima(separations)) {
                    if (estimatedMinimum(separations, index) > options.planetConjunctionThreshold + pruneMargin) continue
                    val first = ConjunctionTarget.Body(planets[i])
                    val second = ConjunctionTarget.Body(planets[j])
                    refine(first, second, scan.startUT + (index - 1) * planetStep, scan.startUT + (index + 1) * planetStep)
                        ?.let { appendIfAccepted(found, it, first, second, options, observer, fromUT, toUT) }
                }
            }
        }

        // The Moon with the planets and the stars.
        val moon = ConjunctionTarget.Body(SolarSystemBody.MOON)
        val targets = ArrayList<ConjunctionTarget>()
        for (planet in planets) targets.add(ConjunctionTarget.Body(planet))
        for (star in ReferenceStar.entries) targets.add(ConjunctionTarget.Star(star))
        for ((targetIndex, target) in targets.withIndex()) {
            val separations = DoubleArray(scan.moonCount + 1) { scan.moonSeparation(targetIndex, it) }
            for (index in EventSearch.localMinima(separations)) {
                if (estimatedMinimum(separations, index) > options.moonConjunctionThreshold + pruneMargin) continue
                refine(moon, target, scan.startUT + (index - 1) * moonStep, scan.startUT + (index + 1) * moonStep)
                    ?.let { appendIfAccepted(found, it, moon, target, options, observer, fromUT, toUT) }
            }
        }
        return found.sortedBy { it.date }
    }

    /** The grids of the scan: daily planet directions, six-hourly Moon directions, fixed star directions. */
    private class Scan(fromUT: Double, toUT: Double) {
        val startUT: Double = fromUT - 3.0
        val dayCount: Int = EventSearch.intervalCount(toUT + 3.0 - startUT, planetStep)
        val moonCount: Int = EventSearch.intervalCount(toUT + 3.0 - startUT, moonStep)
        val planetVectors: Array<Array<Vector3>> = geometricPlanetVectors(startUT, dayCount)
        private val moonVectors: Array<Vector3> =
            Array(moonCount + 1) { Ephemeris.geocentricJ2000(SolarSystemBody.MOON, JulianDate(startUT + it * moonStep)) }
        private val starVectors: List<Vector3> = ReferenceStar.entries.map { it.position.unitVector }

        /** Separation in degrees of the Moon at Moon sample [moonIndex] from planet or star [targetIndex]. */
        fun moonSeparation(targetIndex: Int, moonIndex: Int): Double {
            val moon = moonVectors[moonIndex]
            if (targetIndex >= planets.size) return moon.angle(starVectors[targetIndex - planets.size])
            val position = moonIndex * moonStep / planetStep
            val node = minOf(floor(position).toInt(), dayCount - 1)
            val fraction = position - node
            val planet = planetVectors[targetIndex][node] * (1 - fraction) + planetVectors[targetIndex][node + 1] * fraction
            return moon.angle(planet)
        }
    }

    /**
     * Geocentric directions of the planets every [planetStep] days from [startUT], from the heliocentric series alone
     * (no light-time or aberration: at most 0.02° off, which the coarse scan allows for). The Earth's position is
     * computed once per date and shared by the seven planets.
     */
    private fun geometricPlanetVectors(startUT: Double, dayCount: Int): Array<Array<Vector3>> {
        val theories = planets.map { theory(it) }
        val vectors = Array(planets.size) { Array(dayCount + 1) { Vector3.zero } }
        for (day in 0..dayCount) {
            val tt = JulianDate(startUT + day * planetStep).tt
            val earth = PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, tt)
            for (index in planets.indices) {
                vectors[index][day] = PlanetaryTheory.heliocentricJ2000(theories[index], tt) - earth
            }
        }
        return vectors
    }

    private fun theory(body: SolarSystemBody): PlanetaryTheory.Planet =
        when (body) {
            SolarSystemBody.MERCURY -> PlanetaryTheory.Planet.MERCURY
            SolarSystemBody.VENUS -> PlanetaryTheory.Planet.VENUS
            SolarSystemBody.MARS -> PlanetaryTheory.Planet.MARS
            SolarSystemBody.JUPITER -> PlanetaryTheory.Planet.JUPITER
            SolarSystemBody.SATURN -> PlanetaryTheory.Planet.SATURN
            SolarSystemBody.URANUS -> PlanetaryTheory.Planet.URANUS
            SolarSystemBody.NEPTUNE -> PlanetaryTheory.Planet.NEPTUNE
            SolarSystemBody.SUN, SolarSystemBody.MOON -> PlanetaryTheory.Planet.EARTH
        }

    /**
     * The least separation the samples around the local minimum at [index] imply: the vertex of the parabola through
     * the squares of the three separations (exact for motion along a straight line at constant speed).
     */
    private fun estimatedMinimum(separations: DoubleArray, index: Int): Double {
        val before = separations[index - 1] * separations[index - 1]
        val here = separations[index] * separations[index]
        val after = separations[index + 1] * separations[index + 1]
        val curvature = before - 2 * here + after
        if (!(curvature > 0)) return separations[index]
        val vertex = here - (after - before) * (after - before) / (8 * curvature)
        return sqrt(maxOf(vertex, 0.0))
    }

    /** The geocentric apparent direction of [target] at [jd]; planets and the Moon keep their distance (AU), stars are unit vectors. */
    private fun vector(target: ConjunctionTarget, jd: JulianDate): Vector3 =
        when (target) {
            is ConjunctionTarget.Body -> Ephemeris.geocentricJ2000(target.body, jd)
            is ConjunctionTarget.Star -> {
                val moved = target.star.properMotion.apply(target.star.position, (jd.tt - JulianDate.j2000) / 365.25)
                Aberration.apply(moved.unitVector, Aberration.earthVelocity(jd))
            }
        }

    private fun separation(first: ConjunctionTarget, second: ConjunctionTarget, ut: Double): Double {
        val jd = JulianDate(ut)
        return vector(first, jd).angle(vector(second, jd))
    }

    /** The instant of least separation in the bracket and that separation. */
    private fun refine(first: ConjunctionTarget, second: ConjunctionTarget, lower: Double, upper: Double): Pair<Double, Double>? {
        val result = EventSearch.minimum({ separation(first, second, it) }, lower, upper, refineTolerance)
        return if (result.first > lower && result.first < upper) result else null
    }

    private fun appendIfAccepted(
        found: MutableList<AstronomicalEvent>,
        minimum: Pair<Double, Double>,
        first: ConjunctionTarget,
        second: ConjunctionTarget,
        options: EventOptions,
        observer: Observer?,
        fromUT: Double,
        toUT: Double,
    ) {
        val ut = minimum.first
        if (ut < fromUT || ut > toUT) return
        val involvesMoon = first == ConjunctionTarget.Body(SolarSystemBody.MOON)
        val threshold = if (involvesMoon) options.moonConjunctionThreshold else options.planetConjunctionThreshold
        if (minimum.second > threshold) return
        val jd = JulianDate(ut)
        val sun = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd)
        val firstVector = vector(first, jd)
        val secondVector = vector(second, jd)
        val centre = if (involvesMoon) firstVector else firstVector.normalized + secondVector.normalized
        val elongation = centre.angle(sun)
        if (elongation < options.minimumSolarElongation) return

        var occultationPossible = false
        var topocentric: TopocentricApproach? = null
        if (involvesMoon) {
            val moonDistanceKilometers = firstVector.length * SkyFrame.kilometersPerAU
            val reach = angularRadius(SolarSystemBody.MOON.meanRadiusKilometers, moonDistanceKilometers) +
                AstroMath.asinDeg(SkyFrame.earthEquatorialRadiusKilometers / moonDistanceKilometers) +
                targetRadius(second, secondVector)
            occultationPossible = minimum.second < reach
            if (observer != null) topocentric = topocentricApproach(second, observer, ut)
        }
        val conjunction = Conjunction(first, second, minimum.second, elongation, occultationPossible, topocentric)
        found.add(AstronomicalEvent(jd.date, EventDetails.Conjunction(conjunction), null))
    }

    /** Angular radius in degrees of a sphere of [radiusKilometers] at [distanceKilometers]. */
    private fun angularRadius(radiusKilometers: Double, distanceKilometers: Double): Double =
        AstroMath.asinDeg(AstroMath.clamp(radiusKilometers / distanceKilometers, 0.0, 1.0))

    /** The angular radius in degrees of [target] at the distance of [vector] (AU); 0 for stars. */
    private fun targetRadius(target: ConjunctionTarget, vector: Vector3): Double =
        when (target) {
            is ConjunctionTarget.Body -> angularRadius(target.body.meanRadiusKilometers, vector.length * SkyFrame.kilometersPerAU)
            is ConjunctionTarget.Star -> 0.0
        }

    /**
     * The least topocentric separation of the Moon from [target] within three hours of [ut]. The Moon is evaluated
     * exactly; a planet is placed on the straight line through its positions at the ends of the window (its motion
     * is uniform to better than 0.001° over six hours) and a star at its apparent place at [ut].
     */
    private fun topocentricApproach(target: ConjunctionTarget, observer: Observer, ut: Double): TopocentricApproach {
        val start = ut - topocentricHalfWindow
        val end = ut + topocentricHalfWindow
        val startVector = if (target is ConjunctionTarget.Body) vector(target, JulianDate(start)) else Vector3.zero
        val endVector = if (target is ConjunctionTarget.Body) vector(target, JulianDate(end)) else Vector3.zero
        val starVector = if (target is ConjunctionTarget.Star) vector(target, JulianDate(ut)) else Vector3.zero

        fun evaluate(time: Double): Triple<Double, Double, Double> {
            val frame = SkyFrame(JulianDate(time), observer)
            val matrix = frame.precessionNutationMatrix
            val moon = matrix * Ephemeris.geocentricJ2000(SolarSystemBody.MOON, frame.julianDate) - frame.observerPositionOfDate
            val moonRadius = angularRadius(SolarSystemBody.MOON.meanRadiusKilometers, moon.length * SkyFrame.kilometersPerAU)
            return when (target) {
                is ConjunctionTarget.Body -> {
                    val fraction = (time - start) / (end - start)
                    val other = matrix * (startVector * (1 - fraction) + endVector * fraction) - frame.observerPositionOfDate
                    Triple(moon.angle(other), moonRadius, targetRadius(target, other))
                }
                is ConjunctionTarget.Star -> Triple(moon.angle(matrix * starVector), moonRadius, 0.0)
            }
        }

        val least = EventSearch.minimum({ evaluate(it).first }, start, end, refineTolerance)
        val radii = evaluate(least.first)
        return TopocentricApproach(EventSearch.instant(least.first), least.second, least.second < radii.second + radii.third)
    }
}
